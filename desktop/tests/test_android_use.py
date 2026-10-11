"""End-to-end tests for the Android Use MCP server against a fake on-device bridge.

A local HTTPServer plays the bridge role (same JSON routes/fields as BridgeController.kt),
so client + controller + MCP dispatch run a real HTTP round trip without a phone.
"""
import base64
import json
import os
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "server"))

from adb_client import BridgeClient, BridgeError          # noqa: E402
from adb_controller import AndroidController               # noqa: E402
from adb_text import split_text, safe_boundary             # noqa: E402
import android_use                                         # noqa: E402

TOKEN = "deadbeef" * 4
# 1x1 px JPEG
TINY_JPEG = base64.b64encode(bytes.fromhex(
    "ffd8ffe000104a46494600010100000100010000ffdb004300080606070605080707070909080a0c"
    "140d0c0b0b0c1912130f141d1a1f1e1d1a1c1c20242e2720222c231c1c2837292c30313434341f27"
    "393d38323c2e333432ffc0000b080001000101011100ffc400140001000000000000000000000000"
    "0000000000ffc4001410010000000000000000000000000000000000ffda0008010100003f00d2cf"
    "20ffd9")).decode()

NODES = [
    {"ref": "w0|com.app:id/title|TextView", "rect": [0, 0, 1080, 200],
     "type": "TextView", "text": "标题栏", "tap": [540, 100], "pkg": "com.app"},
    {"ref": "w0|com.app:id/btn_ok|Button", "rect": [300, 900, 480, 120],
     "type": "Button", "text": "确定", "clickable": True, "tap": [540, 960], "pkg": "com.app"},
    {"ref": "w0|com.app:id/btn_dup|Button", "rect": [300, 1100, 480, 120],
     "type": "Button", "text": "确定", "clickable": True, "tap": [540, 1160], "pkg": "com.app"},
    {"ref": "w0|com.app:id/input|EditText", "rect": [60, 600, 960, 140],
     "type": "EditText", "editable": True, "focused": True, "tap": [540, 670],
     "text": "", "pkg": "com.app"},
    {"ref": "w0|com.app:id/row1|TextView", "rect": [0, 1400, 1080, 160],
     "type": "TextView", "text": "第1行", "tap": [540, 1480], "pkg": "com.app"},
]


class FakeBridgeHandler(BaseHTTPRequestHandler):
    """Minimal stand-in for device/bridge BridgeServer+BridgeController."""

    server_version = "FakeBridge/1"
    sessions = {}
    actions = []          # every action body the fake bridge received
    observation_seq = 0
    nodes = list(NODES)
    field_text = {}       # ref -> typed content, for readback verify
    row_shift = 0         # set nonzero to simulate scrolling rows

    def log_message(self, *args):
        pass

    def _auth(self):
        return self.headers.get("Authorization") == f"Bearer {TOKEN}"

    def _reply(self, status, body):
        payload = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def _body(self):
        length = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(length)) if length else {}

    def do_GET(self):  # noqa: N802
        path = self.path.split("?", 1)[0]
        query = self.path.split("?", 1)[1] if "?" in self.path else ""
        # Mirror BridgeServer: GET /screen also accepts the token as a query param.
        if not (self._auth() or (path == "/screen" and f"token={TOKEN}" in query)):
            return self._reply(401, {"ok": False, "error": {"code": "unauthorized", "message": "bad token"}})
        if path == "/status":
            return self._reply(200, {"ok": True, "version": 1, "accessibility": True,
                                     "session_open": bool(self.sessions)})
        if path == "/apps":
            return self._reply(200, {"ok": True, "apps": [
                {"label": "微信", "package_name": "com.tencent.mm"},
                {"label": "设置", "package_name": "com.android.settings"}]})
        if path == "/screen":
            self.send_response(200)
            self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=mobileagent")
            self.end_headers()
            frame = base64.b64decode(TINY_JPEG)
            for _ in range(2):
                part = (b"--mobileagent\r\nContent-Type: image/jpeg\r\n"
                        + f"Content-Length: {len(frame)}\r\n\r\n".encode() + frame + b"\r\n")
                try:
                    self.wfile.write(part)
                    self.wfile.flush()
                except BrokenPipeError:
                    return
            return
        return self._reply(404, {"ok": False, "error": {"code": "not_found", "message": "route"}})

    def do_DELETE(self):  # noqa: N802
        if not self._auth():
            return self._reply(401, {"ok": False, "error": {"code": "unauthorized", "message": "bad token"}})
        self.sessions.clear()
        return self._reply(200, {"ok": True})

    def do_POST(self):  # noqa: N802
        if not self._auth():
            return self._reply(401, {"ok": False, "error": {"code": "unauthorized", "message": "bad token"}})
        path = self.path.split("?", 1)[0]
        body = self._body()
        if path == "/session":
            type(self).session_seq = getattr(type(self), "session_seq", 0) + 1
            sid = f"sess-{type(self).session_seq}"
            self.sessions[sid] = True
            return self._reply(200, {"ok": True, "session_id": sid, "capabilities": ["screenshot"]})
        sid = body.get("session_id")
        if sid not in self.sessions:
            return self._reply(410, {"ok": False, "error": {"code": "session_expired", "message": "expired"}})
        if path in ("/inspect", "/observe"):
            self.observation_seq += 1
            mode = body.get("mode", "tree")
            nodes = list(self.nodes)
            if self.row_shift:
                nodes = nodes + [{"ref": f"w0|row{4 + self.row_shift}",
                                  "rect": [0, 300 + self.row_shift * 200, 1080, 160],
                                  "type": "TextView", "text": f"新行{self.row_shift}",
                                  "tap": [540, 380 + self.row_shift * 200], "pkg": "com.app"}]
            result = {"ok": True, "observation_id": f"obs-{self.observation_seq}",
                      "session_id": sid, "captured_at": 0,
                      "viewport": {"width": 1080, "height": 2400},
                      "rotation": 0, "foreground_package": "com.app",
                      "nodes": nodes, "node_count": len(nodes), "truncated": False}
            if path == "/observe" and mode in ("screenshot", "both"):
                result["image"] = {"base64": TINY_JPEG, "mime": "image/jpeg",
                                   "width": 1080, "height": 2400, "pixel_to_point": [1, 1]}
            return self._reply(200, result)
        if path == "/action":
            action = body.get("action") or {}
            self.actions.append({"observation_id": body.get("observation_id"), **action})
            kind = action.get("kind")
            if kind == "input_text":
                ref = action.get("node_ref", "w0|com.app:id/input|EditText")
                mode = action.get("mode", "replace")
                old = self.field_text.get(ref, "")
                self.field_text[ref] = action.get("text", "") if mode == "replace" else old + action.get("text", "")
                for node in self.nodes:
                    if node.get("ref") == ref:
                        node["text"] = self.field_text[ref]
            return self._reply(200, {"ok": True, "performed": True})
        if path == "/screen/pause":
            return self._reply(200, {"ok": True, "paused": True})
        if path == "/screen/resume":
            return self._reply(200, {"ok": True, "paused": False})
        return self._reply(404, {"ok": False, "error": {"code": "not_found", "message": "route"}})


class BridgeFixture(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(("127.0.0.1", 0), FakeBridgeHandler)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        FakeBridgeHandler.sessions.clear()
        FakeBridgeHandler.actions.clear()
        FakeBridgeHandler.observation_seq = 0
        FakeBridgeHandler.nodes = [dict(n) for n in NODES]
        FakeBridgeHandler.field_text.clear()
        FakeBridgeHandler.row_shift = 0
        self.client = BridgeClient(base_url=f"http://127.0.0.1:{self.port}", token=TOKEN)
        self.addCleanup(self.client.close)

    def controller(self, tmp=None):
        return AndroidController(self.client, tmp or Path(self.id().replace(".", "_")))


class TestClient(BridgeFixture):
    def test_status_requires_token(self):
        bad = BridgeClient(base_url=f"http://127.0.0.1:{self.port}", token="wrong")
        self.addCleanup(bad.close)
        with self.assertRaises(BridgeError) as ctx:
            bad.request("GET", "/status")
        self.assertEqual(ctx.exception.code, "unauthorized")

    def test_status_and_session(self):
        status = self.client.request("GET", "/status")
        self.assertTrue(status["accessibility"])
        sid = self.client.ensure_session()
        self.assertTrue(sid.startswith("sess-"))
        result = self.client.session("POST", "/inspect", {"mode": "tree"})
        self.assertIn("observation_id", result)

    def test_session_expired_retry(self):
        sid = self.client.ensure_session()
        FakeBridgeHandler.sessions.clear()           # phone-side session vanished
        result = self.client.session("POST", "/inspect", {"mode": "tree"})
        self.assertIn("observation_id", result)
        self.assertNotEqual(self.client.session_id, sid)   # reopened, not replayed blindly


class TestText(unittest.TestCase):
    def test_split_preserves_text(self):
        text = "a" * 900 + "中文测试" + "b" * 500
        pieces = split_text(text, 450)
        self.assertEqual("".join(pieces), text)
        self.assertTrue(all(len(p) <= 450 for p in pieces))

    def test_grapheme_boundary(self):
        flag = "🇨🇳🇺🇸"          # two flag pairs
        text = "x" * 4 + flag
        self.assertFalse(safe_boundary(text, 5))   # inside a flag pair
        self.assertTrue(safe_boundary(text, 4))    # between pairs
        combined = "e" + "́" + "tail"
        self.assertFalse(safe_boundary(combined, 1))  # inside e+combining
        self.assertTrue(safe_boundary(combined, 2))   # e+mark is complete before t


class TestController(BridgeFixture):
    def test_observe_tree(self):
        phone = self.controller()
        obs = phone.observe("tree")
        self.assertIn("observation_id", obs)
        self.assertEqual(len(obs["nodes"]), len(NODES))
        self.assertEqual(obs["app"], "com.app")
        self.assertNotIn("image", obs)

    def test_observe_screenshot(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            phone = self.controller(tmp)
            obs = phone.observe("both")
            self.assertIn("image", obs)
            self.assertTrue(Path(obs["image"]["path"]).is_file())
            self.assertEqual(obs["image"]["mimeType"], "image/jpeg")

    def test_tap_by_selector_uses_node_action(self):
        phone = self.controller()
        result = phone.tap(selector={"label": "确定", "index": 0})
        self.assertTrue(result["action_executed"])
        self.assertEqual(FakeBridgeHandler.actions[-1]["kind"], "node_action")
        self.assertEqual(FakeBridgeHandler.actions[-1]["node_ref"], "w0|com.app:id/btn_ok|Button")

    def test_tap_ambiguous_returns_candidates(self):
        phone = self.controller()
        # two separate "确定" buttons -> ambiguous_target with candidates
        with self.assertRaises(BridgeError) as ctx:
            phone.tap(selector={"label": "确定"})
        error = ctx.exception
        self.assertEqual(error.code, "ambiguous_target")
        self.assertEqual(len(error.details["candidates"]), 2)
        self.assertIn("observation", error.details)   # screenshot fallback attached

    def test_tap_index_picks_match(self):
        phone = self.controller()
        phone.tap(selector={"label": "确定", "index": 1})
        self.assertEqual(FakeBridgeHandler.actions[-1]["node_ref"], "w0|com.app:id/btn_dup|Button")

    def test_tap_no_match_error_has_screenshot(self):
        phone = self.controller()
        with self.assertRaises(BridgeError) as ctx:
            phone.tap(selector={"label": "不存在"})
        error = ctx.exception
        self.assertEqual(error.code, "no_such_element")
        self.assertFalse(error.details["action_executed"])
        self.assertIn("observation", error.details)
        self.assertEqual(error.details["recovery"]["use"], "coordinates")

    def test_tap_coordinates(self):
        phone = self.controller()
        phone.tap(x=100, y=200)
        action = FakeBridgeHandler.actions[-1]
        self.assertEqual(action["kind"], "tap")
        self.assertEqual((action["x"], action["y"]), (100, 200))
        self.assertTrue(action["observation_id"].startswith("obs-"))

    def test_tap_outside_viewport_rejected(self):
        phone = self.controller()
        with self.assertRaises(BridgeError) as ctx:
            phone.tap(x=5000, y=100)
        self.assertEqual(ctx.exception.code, "invalid_argument")

    def test_stale_observation(self):
        phone = self.controller()
        with self.assertRaises(BridgeError) as ctx:
            phone.tap(x=10, y=10, observation_id="obs-999")
        self.assertEqual(ctx.exception.code, "stale_observation")

    def test_type_text_replace_and_verify(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            phone = self.controller(tmp)
            result = phone.type_text(text="你好世界", replace=True, verify=True)
            self.assertTrue(result["verified"])
            action = FakeBridgeHandler.actions[-1]
            self.assertEqual(action["kind"], "input_text")
            self.assertEqual(action["text"], "你好世界")
            self.assertEqual(action["mode"], "replace")

    def test_type_text_pieces_for_long_input(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            phone = self.controller(tmp)
            phone.type_text(text="x" * 900, replace=True)
            sends = [a for a in FakeBridgeHandler.actions if a["kind"] == "input_text"]
            self.assertGreater(len(sends), 1)
            self.assertEqual(sends[0]["mode"], "replace")
            self.assertTrue(all(a["mode"] == "append" for a in sends[1:]))

    def test_type_text_focused_field(self):
        phone = self.controller()
        phone.type_text(text="abc")         # no selector -> focused editable
        self.assertEqual(FakeBridgeHandler.actions[-1]["kind"], "input_text")

    def test_swipe_executes(self):
        phone = self.controller()
        phone.swipe("up", verify=False)
        action = FakeBridgeHandler.actions[-1]
        self.assertEqual(action["kind"], "swipe")
        self.assertLess(action["y2"], action["y1"])   # upward

    def test_swipe_verify_no_progress(self):
        phone = self.controller()
        with self.assertRaises(BridgeError) as ctx:
            phone.swipe("up", verify=True)
        self.assertEqual(ctx.exception.code, "no_scroll_progress")

    def test_swipe_verify_progress(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            phone = self.controller(tmp)

            def move():
                # The gesture moved a stable anchor: same text, displaced y, same size.
                for node in FakeBridgeHandler.nodes:
                    if node.get("text") == "第1行":
                        node["rect"] = [0, 1200, 1080, 160]
                        node["tap"] = [540, 1280]
                return {}
            original = AndroidController._action
            AndroidController._action = lambda self, a, oid=None, timeout=None: (
                move(), original(self, a, oid, timeout))[1]
            try:
                result = phone.swipe("up", verify=True)
            finally:
                AndroidController._action = original
            self.assertTrue(result["progress_verified"])

    def test_wait_found_and_timeout(self):
        phone = self.controller()
        found = phone.wait({"label": "确定", "index": 0}, timeout_seconds=2)
        self.assertTrue(found["verified"])
        with self.assertRaises(BridgeError) as ctx:
            phone.wait({"label": "不存在"}, timeout_seconds=0.5)
        self.assertEqual(ctx.exception.code, "postcondition_failed")

    def test_find(self):
        phone = self.controller()
        result = phone.find({"type": "Button"}, limit=10)
        self.assertEqual(result["matches"], 2)
        self.assertEqual(result["elements"][0]["type"], "Button")

    def test_press_button(self):
        phone = self.controller()
        phone.press_button("back")
        self.assertEqual(FakeBridgeHandler.actions[-1]["key"], "back")
        with self.assertRaises(BridgeError):
            phone.press_button("nonexistent")

    def test_launch_app(self):
        phone = self.controller()
        phone.launch_app("com.tencent.mm")
        action = FakeBridgeHandler.actions[-1]
        self.assertEqual(action["kind"], "open_app")
        self.assertEqual(action["package_name"], "com.tencent.mm")

    def test_batch_runs_and_stops(self):
        phone = self.controller()
        result = phone.batch([
            {"op": "tap", "args": {"selector": {"label": "确定", "index": 0}}},
            {"op": "tap", "args": {"selector": {"label": "不存在"}}},
            {"op": "tap", "args": {"x": 1, "y": 1}},
        ])
        self.assertFalse(result["batch_complete"])
        self.assertEqual(result["completed_steps"], 1)
        self.assertEqual(result["stopped_at"], 1)
        self.assertEqual(len(FakeBridgeHandler.actions), 1)   # failed step executed nothing

    def test_scroll_find(self):
        phone = self.controller()
        result = phone.scroll_find({"label": "第1行"}, direction="up", max_swipes=1)
        self.assertTrue(result["verified"])
        with self.assertRaises(BridgeError) as ctx:
            phone.scroll_find({"label": "看不见"}, direction="up", max_swipes=1)
        self.assertEqual(ctx.exception.code, "search_exhausted")


class TestApps(BridgeFixture):
    def test_lookup_installed_and_catalog(self):
        import tempfile
        from adb_apps import AppCatalog
        with tempfile.TemporaryDirectory() as tmp:
            catalog = AppCatalog(tmp, self.client, serial_getter=lambda: "dev1")
            result = catalog.lookup("微信")
            self.assertTrue(result["ok"])
            self.assertTrue(any(c["package_name"] == "com.tencent.mm" for c in result["candidates"]))
            top = result["candidates"][0]
            self.assertTrue(top["installed_verified"])

    def test_lookup_unknown(self):
        import tempfile
        from adb_apps import AppCatalog
        with tempfile.TemporaryDirectory() as tmp:
            catalog = AppCatalog(tmp, self.client)
            result = catalog.lookup("不存在appzzz")
            self.assertEqual(result["candidates"], [])


class TestScreen(BridgeFixture):
    def test_mjpeg_frames(self):
        from adb_screen import ScreenHub
        hub = ScreenHub(port=self.port, token=TOKEN)
        hub.start()
        try:
            frame = hub.frame(timeout=8)
            self.assertIsNotNone(frame)
            self.assertGreaterEqual(frame["seq"], 1)
            self.assertTrue(frame["jpeg_base64"])
            again = hub.frame(after_seq=frame["seq"], timeout=8)
            self.assertIsNotNone(again)
            self.assertGreater(again["seq"], frame["seq"])
        finally:
            hub.stop()

    def test_frame_timeout_returns_none(self):
        from adb_screen import ScreenHub
        hub = ScreenHub(port=self.port, token=TOKEN)   # never start: no frames arrive
        self.assertIsNone(hub.frame(timeout=1))


class TestDispatch(unittest.TestCase):
    def test_unknown_tool(self):
        runtime = object.__new__(android_use.Runtime)
        with self.assertRaises(BridgeError) as ctx:
            android_use.Runtime._dispatch(runtime, "aua_nonexistent", {})
        self.assertEqual(ctx.exception.code, "unknown_tool")

    def test_validation_unknown_field(self):
        runtime = object.__new__(android_use.Runtime)
        with self.assertRaises(BridgeError) as ctx:
            android_use.Runtime._dispatch(runtime, "aua_tap", {"x": 1, "y": 2, "bogus": 1})
        self.assertEqual(ctx.exception.code, "invalid_argument")
        self.assertIn("bogus", ctx.exception.details["unknown_fields"])

    def test_tap_needs_selector_or_xy(self):
        runtime = object.__new__(android_use.Runtime)
        with self.assertRaises(BridgeError) as ctx:
            android_use.Runtime._dispatch(runtime, "aua_tap", {"selector": {"label": "x"}, "x": 1})
        self.assertEqual(ctx.exception.code, "invalid_argument")

    def test_newline_requires_intent(self):
        runtime = object.__new__(android_use.Runtime)
        with self.assertRaises(BridgeError) as ctx:
            android_use.Runtime._dispatch(runtime, "aua_type_text", {"text": "a\nb"})
        self.assertEqual(ctx.exception.code, "newline_requires_intent")


if __name__ == "__main__":
    unittest.main()
