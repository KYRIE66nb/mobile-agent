"""Regression tests for Android bridge activation through the exported app UI."""

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import sys

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "server"))

from adb_setup import APP_PACKAGE, SetupManager  # noqa: E402


MAIN_ACTIVITY_COMPONENT = f"{APP_PACKAGE}/.MainActivity"


class SetupBridgeStartTests(unittest.TestCase):
    def manager(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        manager = SetupManager(directory.name)
        manager._save({"serial": "device-one"})
        return manager

    @staticmethod
    def adb_result(enabled=True, code=0, output=None):
        if output is not None:
            return code, output
        value = "true" if enabled else "false"
        return 0, f"Result: Bundle[{{enabled={value}, accessibility=true, device_port=17325}}]"

    def test_disabled_bridge_requires_user_action_without_starting_any_service(self):
        manager = self.manager()
        calls = []

        def fake_adb(*arguments, **kwargs):
            calls.append((arguments, kwargs))
            return self.adb_result(enabled=False)

        with patch("adb_setup.adb", side_effect=fake_adb):
            result = manager.start_bridge()

        self.assertFalse(result["ok"])
        self.assertEqual(result["state"], "user_action_required")
        self.assertFalse(result["started"])
        self.assertIn("Settings → General → Desktop control (USB)", result["next_action"])
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0][0][:6], (
            "shell", "content", "call", "--uri", "content://xyz.chouxuewei.mobile_agent.bridge",
            "--method",
        ))
        self.assertNotIn("start-foreground-service", calls[0][0])

    def test_enabled_bridge_launches_exported_main_activity_without_claiming_http_ready(self):
        manager = self.manager()
        calls = []

        def fake_adb(*arguments, **kwargs):
            calls.append((arguments, kwargs))
            if arguments[:3] == ("shell", "content", "call"):
                return self.adb_result(enabled=True)
            return 0, "Starting: Intent { cmp=xyz.chouxuewei.mobile_agent/.MainActivity }"

        with patch("adb_setup.adb", side_effect=fake_adb):
            result = manager.start_bridge()

        self.assertTrue(result["ok"])
        self.assertEqual(result["state"], "app_launch_requested")
        self.assertTrue(result["app_launch_requested"])
        self.assertNotIn("started", result)
        self.assertNotIn("ready", result)
        self.assertIn("forward", result["next_action"])
        self.assertIn("ready", result["next_action"])
        self.assertEqual(calls[1][0][:4], ("shell", "am", "start", "-n"))
        self.assertEqual(calls[1][0][4], MAIN_ACTIVITY_COMPONENT)
        self.assertNotIn("start-foreground-service", calls[1][0])
        self.assertEqual(calls[1][1]["serial"], "device-one")

    def test_missing_app_is_returned_as_structured_error(self):
        manager = self.manager()
        output = "Error: failed to resolve provider xyz.chouxuewei.mobile_agent.bridge"

        with patch("adb_setup.adb", return_value=self.adb_result(output=output, code=1)):
            result = manager.start_bridge()

        self.assertFalse(result["ok"])
        self.assertEqual(result["error"]["code"], "app_not_installed")
        self.assertEqual(result["error"]["operation"], "bridge_status")
        self.assertEqual(result["error"]["exit_code"], 1)
        self.assertEqual(result["error"]["adb_output"], output)

    def test_adb_authorization_error_is_returned_as_structured_error(self):
        manager = self.manager()
        output = "SecurityException: Permission Denial: shell is not authorized"

        with patch("adb_setup.adb", return_value=self.adb_result(output=output, code=1)):
            result = manager.start_bridge()

        self.assertFalse(result["ok"])
        self.assertEqual(result["error"]["code"], "adb_authorization_denied")
        self.assertEqual(result["error"]["operation"], "bridge_status")
        self.assertEqual(result["error"]["adb_output"], output)


if __name__ == "__main__":
    unittest.main()
