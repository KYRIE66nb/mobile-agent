import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "package-release.py"
EXPECTED_CERT = "ab" * 32
REVISION = "12" * 20

VALID_BADGING = """\
package: name='xyz.chouxuewei.mobile_agent' versionCode='20' versionName='0.1.20'
sdkVersion:'24'
targetSdkVersion:'36'
"""
VALID_CERT_OUTPUT = (
    "Signer #1 certificate DN: CN=Mobile Agent\n"
    f"Signer #1 certificate SHA-256 digest: {EXPECTED_CERT}\n"
)


class PackageReleaseCliTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory(prefix="package release test ")
        self.root = Path(self.temp_dir.name)
        self.apk = self.root / "source apk with spaces.apk"
        self.apk.write_bytes(b"controlled fake release apk\0\n")
        self.tools = self.root / "build tools with spaces"
        self.tools.mkdir()
        self._write_fake_tool("aapt")
        self._write_fake_tool("apksigner")
        self.output_dir = self.root / "output dir with spaces"
        self.aapt_arg_log = self.root / "aapt-argv.json"
        self.apksigner_arg_log = self.root / "apksigner-argv.json"

    def tearDown(self):
        self.temp_dir.cleanup()

    def _write_fake_tool(self, name):
        tool = self.tools / name
        tool.write_text(
            "#!/usr/bin/env python3\n"
            "import json\n"
            "import os\n"
            "from pathlib import Path\n"
            "import sys\n"
            f"log_path = os.environ.get('FAKE_{name.upper()}_ARG_LOG')\n"
            "if log_path:\n"
            "    Path(log_path).write_text(json.dumps(sys.argv[1:]), encoding='utf-8')\n"
            f"sys.stdout.write(os.environ.get('FAKE_{name.upper()}_OUTPUT', ''))\n"
            f"sys.stderr.write(os.environ.get('FAKE_{name.upper()}_STDERR', ''))\n"
            f"sys.exit(int(os.environ.get('FAKE_{name.upper()}_EXIT', '0')))\n",
            encoding="utf-8",
        )
        tool.chmod(tool.stat().st_mode | stat.S_IXUSR)

    def run_release(
        self,
        *,
        apk=None,
        output_dir=None,
        badging=VALID_BADGING,
        cert_output=VALID_CERT_OUTPUT,
        aapt_exit=0,
        apksigner_exit=0,
        version="0.1.20",
        version_code="20",
        expected_cert=EXPECTED_CERT,
        revision=REVISION,
    ):
        env = os.environ.copy()
        env.update(
            {
                "FAKE_AAPT_OUTPUT": badging,
                "FAKE_APKSIGNER_OUTPUT": cert_output,
                "FAKE_AAPT_EXIT": str(aapt_exit),
                "FAKE_APKSIGNER_EXIT": str(apksigner_exit),
                "FAKE_AAPT_ARG_LOG": str(self.aapt_arg_log),
                "FAKE_APKSIGNER_ARG_LOG": str(self.apksigner_arg_log),
            }
        )
        args = [
            sys.executable,
            str(SCRIPT),
            "--apk",
            str(apk or self.apk),
            "--build-tools",
            str(self.tools),
            "--version",
            version,
            "--version-code",
            version_code,
            "--expected-cert",
            expected_cert,
            "--revision",
            revision,
            "--output-dir",
            str(output_dir or self.output_dir),
        ]
        return subprocess.run(args, capture_output=True, text=True, env=env)

    def assert_rejected_without_artifacts(self, result, output_dir=None):
        output_dir = output_dir or self.output_dir
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("error:", result.stderr)
        for name in ("app-release.apk", "SHA256SUMS", "release-info.json"):
            self.assertFalse((output_dir / name).exists(), name)

    def test_valid_release_writes_expected_artifacts_and_uses_argument_arrays(self):
        result = self.run_release()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

        output_apk = self.output_dir / "app-release.apk"
        self.assertEqual(output_apk.read_bytes(), self.apk.read_bytes())
        digest = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        self.assertEqual(
            (self.output_dir / "SHA256SUMS").read_text(encoding="utf-8"),
            f"{digest}  app-release.apk\n",
        )
        info_path = self.output_dir / "release-info.json"
        info_text = info_path.read_text(encoding="utf-8")
        info = json.loads(info_text)
        self.assertEqual(
            info,
            {
                "packageName": "xyz.chouxuewei.mobile_agent",
                "versionName": "0.1.20",
                "versionCode": 20,
                "minSdk": 24,
                "targetSdk": 36,
                "sha256": digest,
                "signingCertificateSha256": EXPECTED_CERT,
                "sourceCommit": REVISION,
            },
        )
        self.assertNotIn(str(self.root), info_text)

        aapt_argv = json.loads(self.aapt_arg_log.read_text(encoding="utf-8"))
        self.assertEqual(aapt_argv[0:2], ["dump", "badging"])
        self.assertEqual(aapt_argv[2], str(self.apk))
        apksigner_argv = json.loads(
            self.apksigner_arg_log.read_text(encoding="utf-8")
        )
        self.assertEqual(apksigner_argv[0:2], ["verify", "--print-certs"])
        self.assertEqual(apksigner_argv[2], str(self.apk))

    def test_same_input_output_path_preserves_input_apk(self):
        output_dir = self.root / "same path output"
        output_dir.mkdir()
        apk = output_dir / "app-release.apk"
        original = b"input must survive same-path packaging\n"
        apk.write_bytes(original)

        result = self.run_release(apk=apk, output_dir=output_dir)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(apk.read_bytes(), original)
        self.assertTrue((output_dir / "SHA256SUMS").is_file())
        self.assertTrue((output_dir / "release-info.json").is_file())

    def test_rejects_unexpected_package(self):
        badging = VALID_BADGING.replace("xyz.chouxuewei.mobile_agent", "com.example.other")
        self.assert_rejected_without_artifacts(self.run_release(badging=badging))

    def test_rejects_version_mismatch(self):
        badging = VALID_BADGING.replace("versionName='0.1.20'", "versionName='0.1.19'")
        self.assert_rejected_without_artifacts(self.run_release(badging=badging))

    def test_rejects_version_code_mismatch(self):
        badging = VALID_BADGING.replace("versionCode='20'", "versionCode='19'")
        self.assert_rejected_without_artifacts(self.run_release(badging=badging))

    def test_rejects_debuggable_application(self):
        badging = VALID_BADGING + "application-debuggable\n"
        self.assert_rejected_without_artifacts(self.run_release(badging=badging))

    def test_rejects_certificate_mismatch(self):
        wrong_cert = "cd" * 32
        self.assert_rejected_without_artifacts(
            self.run_release(expected_cert=wrong_cert)
        )

    def test_rejects_signature_verification_failure(self):
        self.assert_rejected_without_artifacts(self.run_release(apksigner_exit=1))

    def test_rejects_invalid_certificate_or_revision_before_creating_output(self):
        result = self.run_release(expected_cert="not-a-certificate")
        self.assert_rejected_without_artifacts(result)

        result = self.run_release(revision="f" * 39)
        self.assert_rejected_without_artifacts(result)


if __name__ == "__main__":
    unittest.main()
