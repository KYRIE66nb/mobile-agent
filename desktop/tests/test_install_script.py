"""Subprocess regressions for the desktop installer prerequisite check."""

import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


INSTALL_SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "install.sh"


class InstallScriptTests(unittest.TestCase):
    def _make_executable(self, path, contents):
        path.write_text(contents, encoding="utf-8")
        path.chmod(path.stat().st_mode | stat.S_IXUSR)

    def _run_install(self, script=INSTALL_SCRIPT, *, include_python=True, include_adb=False,
                     adb_override=None):
        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary_root = Path(temporary_directory)
            bin_directory = temporary_root / "bin"
            bin_directory.mkdir()
            for command in ("dirname", "pwd"):
                command_path = shutil.which(command)
                if command_path:
                    (bin_directory / command).symlink_to(command_path)
            if include_python:
                (bin_directory / "python3").symlink_to(sys.executable)
            if include_adb:
                self._make_executable(bin_directory / "adb", "#!/bin/sh\nexit 0\n")

            environment = os.environ.copy()
            environment["PATH"] = str(bin_directory)
            environment["HOME"] = str(temporary_root / "home")
            if adb_override is None:
                environment.pop("ADB", None)
            else:
                environment["ADB"] = str(adb_override)

            return subprocess.run(
                ["/bin/sh", str(script)],
                cwd=str(temporary_root),
                env=environment,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )

    @staticmethod
    def _registration(stdout):
        decoder = json.JSONDecoder()
        for offset, character in enumerate(stdout):
            if character != "{":
                continue
            try:
                value, _ = decoder.raw_decode(stdout[offset:])
            except json.JSONDecodeError:
                continue
            return value
        raise AssertionError(f"installer did not emit a JSON registration: {stdout!r}")

    def test_configured_adb_executable_is_accepted_when_not_on_path(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            adb_path = Path(temporary_directory) / "custom adb"
            self._make_executable(adb_path, "#!/bin/sh\nexit 0\n")

            result = self._run_install(adb_override=adb_path)

        self.assertEqual(result.returncode, 0, result.stderr)
        registration = self._registration(result.stdout)
        server = registration["mcpServers"]["android_use"]
        self.assertEqual(server["env"], {"ADB": os.path.abspath(adb_path)})

    def test_configured_adb_directory_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            adb_directory = Path(temporary_directory) / "adb-directory"
            adb_directory.mkdir()

            result = self._run_install(adb_override=adb_directory)

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")
        self.assertNotEqual(result.stderr, "")

    def test_missing_adb_fails_without_registration(self):
        result = self._run_install()

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")
        self.assertNotEqual(result.stderr, "")

    def test_missing_python_fails_without_registration(self):
        result = self._run_install(include_python=False, include_adb=True)

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")
        self.assertNotEqual(result.stderr, "")

    def test_special_repository_path_produces_parseable_registration(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            source_root = Path(temporary_directory)
            special_root = source_root / 'repo "quoted" \\ path with spaces'
            special_scripts = special_root / "scripts"
            (special_root / "server").mkdir(parents=True)
            special_scripts.mkdir()
            shutil.copy2(INSTALL_SCRIPT, special_scripts / "install.sh")
            special_script = special_scripts / "install.sh"
            special_script.chmod(special_script.stat().st_mode | stat.S_IXUSR)

            result = self._run_install(script=special_script, include_adb=True)

        self.assertEqual(result.returncode, 0, result.stderr)
        registration = self._registration(result.stdout)
        server = registration["mcpServers"]["android_use"]
        self.assertNotIn("env", server)
        self.assertEqual(server["cwd"], str(special_root))
        self.assertEqual(server["args"], [str(special_root / "server" / "android_use.py")])


if __name__ == "__main__":
    unittest.main()
