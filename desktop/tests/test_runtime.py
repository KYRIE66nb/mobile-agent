"""Runtime checks exercise the real controller/HTTP boundary without a phone."""
import json
import tempfile
from pathlib import Path
from unittest.mock import patch

from test_android_use import BridgeFixture, FakeBridgeHandler
from adb_client import BridgeClient, BridgeError
from adb_setup import SetupManager
import android_use


class RuntimeTests(BridgeFixture):
    def setUp(self):
        super().setUp()
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.runtime = android_use.Runtime(self.directory.name)
        self.addCleanup(self.runtime.close)
        self.runtime.client.close()
        self.runtime.client = self.client
        self.runtime.phone.client = self.client
        self.runtime.apps.client = self.client

    def readiness(self):
        return {"ok": True, "ready": True, "session_id": self.client.ensure_session(),
                "status": {"screen_paused": False}}

    def test_ready_does_not_hide_failed_observation(self):
        with patch.object(self.runtime.setup_manager, "ready", side_effect=self.readiness), \
             patch.object(self.runtime.phone, "observe", side_effect=BridgeError("failure", "capture failed")):
            result = self.runtime._dispatch("aua_ready", {})
        self.assertFalse(result["ready"])
        self.assertEqual(result["error"]["code"], "failure")

    def test_ready_requires_requested_image_and_valid_context(self):
        for observation, reason in (
            ({"app": "com.app", "viewport": {"width": 1080, "height": 2400}, "nodes": [{}]}, "screenshot_unavailable"),
            ({"app": "com.app", "viewport": {"width": 0, "height": 2400}, "nodes": [{}]}, "viewport_unavailable"),
            ({"app": "com.app", "viewport": {"width": 1080, "height": 2400}, "nodes": []}, "tree_unavailable"),
        ):
            with self.subTest(reason=reason), \
                 patch.object(self.runtime.setup_manager, "ready", side_effect=self.readiness), \
                 patch.object(self.runtime.phone, "observe", return_value=observation):
                result = self.runtime._dispatch("aua_ready", {})
            self.assertFalse(result["ready"])
            self.assertEqual(result["error"]["code"], reason)

    def test_ready_returns_readable_proof_and_does_not_start_preview(self):
        with patch.object(self.runtime.setup_manager, "ready", side_effect=self.readiness):
            result = self.runtime._dispatch("aua_ready", {"screenshot": False})
        self.assertTrue(result["ready"])
        self.assertTrue(result["proof"]["tree_readable"])
        self.assertFalse(self.runtime.screen.running())

    def test_ready_without_image_is_still_not_ready_while_paused(self):
        self.runtime.screen.set_paused(True)
        with patch.object(self.runtime.setup_manager, "ready", side_effect=self.readiness):
            result = self.runtime._dispatch("aua_ready", {"screenshot": False})
        self.assertFalse(result["ready"])
        self.assertEqual(result["error"]["code"], "preview_paused")

    def test_accepted_action_followed_by_read_failure_is_not_safe_to_replay(self):
        with patch.object(self.runtime.phone, "observe", side_effect=BridgeError("failure", "capture failed")):
            with self.assertRaises(BridgeError) as caught:
                self.runtime._dispatch("aua_drag", {"x1": 10, "y1": 10, "x2": 20, "y2": 100,
                                                     "observe": "screenshot"})
        self.assertTrue(caught.exception.details["action_executed"])
        self.assertFalse(caught.exception.details["replay_action"])
        self.assertEqual(len(FakeBridgeHandler.actions), 1)

    def test_handoff_during_result_delivery_preserves_action_evidence(self):
        def post_action(**_):
            self.runtime.screen.set_paused(True)
            return {"action_executed": True, "image": {"base64": "private-image"}}
        with patch.object(self.runtime.phone, "_post_action", side_effect=post_action):
            with self.assertRaises(BridgeError) as caught:
                self.runtime._dispatch("aua_drag", {"x1": 10, "y1": 10, "x2": 20, "y2": 100,
                                                     "observe": "screenshot"})
        self.assertEqual(caught.exception.code, "preview_paused")
        self.assertTrue(caught.exception.details["action_executed"])
        self.assertNotIn("image", caught.exception.details)
        self.assertFalse(caught.exception.details["replay_action"])

    def test_paused_preview_blocks_images_actions_and_native_screenshot(self):
        self.runtime.screen.port = self.port
        self.runtime.screen.token = self.client.token
        self.runtime._dispatch("aua_screen", {"action": "pause"})
        for name, args in (("aua_observe", {"mode": "screenshot"}),
                           ("aua_tap", {"x": 10, "y": 10}), ("aua_screenshot", {})):
            with self.subTest(name=name), self.assertRaises(BridgeError) as caught:
                self.runtime._dispatch(name, args)
            self.assertEqual(caught.exception.code, "preview_paused")
        self.assertEqual(FakeBridgeHandler.actions, [])

    def test_native_screenshot_requests_once_without_claiming_saved_image(self):
        result = self.runtime._dispatch("aua_screenshot", {})
        self.assertTrue(result["screenshot_requested"])
        self.assertFalse(result["verified"])
        self.assertNotIn("image", result)
        self.assertEqual([a["key"] for a in FakeBridgeHandler.actions], ["screenshot"])

    def test_native_screenshot_uncertainty_is_not_replayed(self):
        original = self.client._request_once
        def timeout_after_action(method, path, payload, budget, readonly):
            result = original(method, path, payload, budget, readonly)
            if path == "/action":
                raise BridgeError("action_uncertain", "Lost response after capture", uncertain=True)
            return result
        with patch.object(self.client, "_request_once", side_effect=timeout_after_action):
            with self.assertRaises(BridgeError) as caught:
                self.runtime._dispatch("aua_screenshot", {})
        self.assertTrue(caught.exception.uncertain)
        self.assertFalse(caught.exception.details["recovery"]["replay_action"])
        self.assertEqual([a["key"] for a in FakeBridgeHandler.actions], ["screenshot"])

    def test_setup_client_respects_configured_local_port(self):
        manager = SetupManager(Path(self.directory.name))
        manager._save({"serial": "device-one", "token": "test", "local_port": 19302,
                       "device_port": 17326})
        configured = manager.client()
        self.addCleanup(configured.close)
        self.assertEqual(configured.port, 19302)
        self.assertEqual(configured.device_port, 17326)

    def test_forward_tool_applies_and_persists_requested_port(self):
        self.runtime.setup_manager._save({"serial": "device-one", "token": "test"})
        forwarded = []
        def forward(**arguments):
            forwarded.append(arguments)
        with patch("adb_setup.ensure_forward", side_effect=forward):
            result = self.runtime._dispatch("aua_setup", {"action": "forward", "local_port": 19302})
        self.assertTrue(result["ok"])
        self.assertEqual(forwarded[0]["local_port"], 19302)
        self.assertEqual(self.runtime.client.port, 19302)
        self.assertEqual(self.runtime.setup_manager.config["local_port"], 19302)

    def test_setup_ready_reuses_authenticated_session_and_closes_transport(self):
        manager = self.runtime.setup_manager
        manager._save({"serial": "device-one", "token": self.client.token})
        original_session = self.client.ensure_session()
        temporary = BridgeClient(f"http://127.0.0.1:{self.port}", token=self.client.token)
        with patch("adb_setup.ensure_forward"), patch.object(manager, "client", return_value=temporary):
            result = manager.ready()
        self.assertTrue(result["ready"])
        self.assertEqual(result["session_id"], original_session)
        self.assertIsNone(temporary.connection)

    def test_reload_closes_old_resources(self):
        with patch.object(self.runtime.screen, "stop") as stopped, \
             patch.object(self.client, "close") as closed:
            self.runtime._reload_client()
        stopped.assert_called_once()
        closed.assert_called_once()
