"""Real loopback preview HTTP routes and shared phone-operation semantics."""
import base64
import http.client
import json
import tempfile
import time
from urllib.parse import urlsplit

from test_android_use import BridgeFixture, FakeBridgeHandler, TINY_JPEG
from adb_preview import PreviewServer
from adb_client import BridgeError
import android_use


class PreviewTests(BridgeFixture):
    def setUp(self):
        super().setUp()
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.runtime = android_use.Runtime(directory.name)
        self.addCleanup(self.runtime.close)
        self.runtime.client.close()
        self.runtime.client = self.client
        self.runtime.phone.client = self.client
        screen = self.runtime.screen
        screen.host, screen.port, screen.token = self.client.host, self.client.port, self.client.token
        screen._latest = {"seq": 4, "jpeg": base64.b64decode(TINY_JPEG), "at": time.time()}
        self.preview = PreviewServer(screen, self.runtime._preview_action, self.runtime._preview_context)
        self.addCleanup(self.preview.stop)
        self.url = urlsplit(self.preview.start())
        self.prefix = self.url.path.rstrip("/")

    def request(self, suffix="", body=None, origin=None):
        connection = http.client.HTTPConnection(self.url.hostname, self.url.port, timeout=3)
        headers = {}
        if body is not None:
            headers["Content-Type"] = "application/json"
            headers["Origin"] = origin or f"http://{self.url.netloc}"
        try:
            connection.request("GET" if body is None else "POST", self.prefix + suffix,
                               None if body is None else json.dumps(body), headers)
            response = connection.getresponse()
            return response.status, response.read()
        finally:
            connection.close()

    def frame(self):
        status, data = self.request("/api/frame?after_seq=0")
        self.assertEqual(status, 200)
        return json.loads(data)

    def action(self, tool, arguments, frame_seq=4):
        return self.request("/api/action", {"tool": tool, "arguments": arguments, "frame_seq": frame_seq})

    def test_page_is_same_origin_and_never_contains_pairing_token(self):
        status, page = self.request("/")
        self.assertEqual(status, 200)
        self.assertIn(b"Android Use", page)
        self.assertNotIn(self.client.token.encode(), page)
        wrong = http.client.HTTPConnection(self.url.hostname, self.url.port)
        try:
            wrong.request("GET", "/api/frame")
            self.assertEqual(wrong.getresponse().status, 404)
        finally:
            wrong.close()

    def test_frame_and_context_use_existing_hub_and_controller(self):
        frame = self.frame()
        self.assertEqual(frame["seq"], 4)
        self.assertEqual(frame["jpeg_base64"], TINY_JPEG)
        status, data = self.request("/api/context")
        context = json.loads(data)
        self.assertEqual(status, 200)
        self.assertEqual(context["viewport"]["width"], 1080)
        self.assertNotIn("nodes", context)
        self.assertFalse(self.runtime.screen.running())

    def test_user_navigation_reuses_agent_session(self):
        original_session = self.client.ensure_session()
        self.frame()
        status, data = self.action("aua_press_button", {"name": "home"})
        self.assertEqual(status, 200, data)
        self.assertEqual(list(FakeBridgeHandler.sessions), [original_session])
        self.assertEqual(FakeBridgeHandler.actions[-1]["key"], "home")

    def test_pointer_drag_maps_to_one_bounded_device_gesture(self):
        self.frame()
        status, data = self.action("aua_drag", {"x1": 100, "y1": 1000, "x2": 500, "y2": 400})
        self.assertEqual(status, 200, data)
        self.assertEqual(FakeBridgeHandler.actions[-1]["kind"], "swipe")
        self.assertEqual(FakeBridgeHandler.actions[-1]["x2"], 500)
        status, _ = self.action("aua_drag", {"x1": 100, "y1": 1000, "x2": 1100, "y2": 400})
        self.assertEqual(status, 400)
        self.assertEqual(len(FakeBridgeHandler.actions), 1)

    def test_paused_stale_or_unseen_frames_cannot_control(self):
        self.frame()
        for state in ("paused", "stale", "unseen"):
            self.runtime.screen.set_paused(state == "paused")
            self.runtime.screen._latest["at"] = time.time() - (10 if state == "stale" else 0)
            with self.subTest(state=state):
                status, data = self.action("aua_tap", {"x": 10, "y": 10}, 99 if state == "unseen" else 4)
                self.assertEqual(status, 409, data)
        self.assertEqual(FakeBridgeHandler.actions, [])

    def test_pause_returns_no_new_image_and_resume_requires_new_frame(self):
        self.frame()
        self.runtime.screen.set_paused(True)
        paused = self.frame()
        self.assertTrue(paused["paused"])
        self.assertNotIn("jpeg_base64", paused)
        self.runtime.screen.set_paused(False)
        status, data = self.action("aua_tap", {"x": 10, "y": 10})
        self.assertEqual(status, 409, data)

    def test_cross_origin_post_is_refused_without_phone_actions(self):
        self.frame()
        status, _ = self.request("/api/action", {"tool": "aua_press_button", "arguments": {"name": "home"},
                                               "frame_seq": 4}, origin="https://other.example")
        self.assertEqual(status, 403)
        self.assertEqual(FakeBridgeHandler.actions, [])

    def test_remote_pause_rejects_control_before_the_next_frame_poll(self):
        self.frame()
        FakeBridgeHandler.screen_paused = True
        status, body = self.action("aua_tap", {"x": 10, "y": 10})
        self.assertEqual(status, 409)
        self.assertEqual(json.loads(body)["error"]["code"], "preview_paused")
        self.assertEqual(FakeBridgeHandler.actions, [])

    def test_shared_busy_lock_blocks_preview_mutation(self):
        self.frame()
        self.runtime._operation_lock.acquire()
        try:
            status, body = self.action("aua_tap", {"x": 10, "y": 10})
        finally:
            self.runtime._operation_lock.release()
        self.assertEqual(status, 409)
        self.assertEqual(json.loads(body)["error"]["code"], "device_busy")
        self.assertEqual(FakeBridgeHandler.actions, [])
