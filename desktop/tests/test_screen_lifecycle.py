"""ScreenHub lifecycle and shared bridge pause-state regressions."""

import json
import sys
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch
from urllib.parse import urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "server"))

from adb_screen import ScreenHub  # noqa: E402


FRAME = b"\xff\xd8\xff\xd9"
TOKEN = "screen-test-token"


class FakeBridgeState:
    def __init__(self):
        self.paused = False
        self.status_mode = "ok"
        self.status_failures = 0
        self.frame_seq = 0
        self.active_streams = 0
        self.max_streams = 0
        self.lock = threading.Lock()


class FakeBridgeHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        pass

    @property
    def state(self):
        return self.server.state

    def reply(self, status, body):
        payload = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):  # noqa: N802
        path = urlsplit(self.path).path
        if path == "/status":
            if self.state.status_failures:
                self.state.status_failures -= 1
                return self.reply(503, {"ok": False, "error": {"code": "offline"}})
            if self.state.status_mode == "error":
                return self.reply(503, {"ok": False, "error": {"code": "offline"}})
            if self.state.status_mode == "invalid":
                return self.reply(200, {"ok": True})
            if self.state.status_mode == "false":
                return self.reply(200, {"ok": False, "screen_paused": False})
            return self.reply(200, {"ok": True, "screen_paused": self.state.paused})
        if path != "/screen":
            return self.reply(404, {"ok": False})
        self.send_response(200)
        self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=screen")
        self.send_header("Connection", "close")
        self.end_headers()
        with self.state.lock:
            self.state.active_streams += 1
            self.state.max_streams = max(self.state.max_streams, self.state.active_streams)
        try:
            for _ in range(5):
                if self.server.stop_event.is_set() or self.state.paused:
                    break
                with self.state.lock:
                    self.state.frame_seq += 1
                part = (b"--screen\r\nContent-Type: image/jpeg\r\n"
                        + f"Content-Length: {len(FRAME)}\r\n\r\n".encode()
                        + FRAME + b"\r\n")
                self.wfile.write(part)
                self.wfile.flush()
                time.sleep(.01)
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            with self.state.lock:
                self.state.active_streams -= 1


class ScreenLifecycleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.state = FakeBridgeState()
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), FakeBridgeHandler)
        cls.server.state = cls.state
        cls.server.stop_event = threading.Event()
        cls.server.daemon_threads = True
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.stop_event.set()
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2)

    def setUp(self):
        self.state.paused = False
        self.state.status_mode = "ok"
        self.state.status_failures = 0
        self.state.frame_seq = 0
        self.state.active_streams = 0
        self.state.max_streams = 0

    def hub(self):
        return ScreenHub(host="127.0.0.1", port=self.server.server_port, token=TOKEN)

    def test_two_hubs_share_remote_pause_without_leaking_cached_frames(self):
        first, second = self.hub(), self.hub()
        self.addCleanup(first.stop)
        self.addCleanup(second.stop)
        first.start()
        second.start()
        first_frame = first.frame(timeout=2)
        second_frame = second.frame(timeout=2)
        self.assertIn("jpeg_base64", first_frame)
        self.assertIn("jpeg_base64", second_frame)

        self.state.paused = True
        self.assertTrue(first.refresh_pause())
        self.assertTrue(second.refresh_pause())
        for hub in (first, second):
            blocked = hub.frame(after_seq=0, timeout=.2)
            self.assertTrue(blocked["paused"])
            self.assertNotIn("jpeg_base64", blocked)

        # A remote resume must not override a local authentication pause.
        self.state.paused = False
        first.set_paused(True)
        self.assertTrue(first.refresh_pause())
        first.set_paused(False)  # bridge resume was confirmed by the caller
        second.set_paused(False)
        first.start()
        second.start()
        resumed = first.frame(after_seq=first_frame["seq"], timeout=2)
        self.assertGreater(resumed["seq"], first_frame["seq"])
        self.assertIn("jpeg_base64", resumed)

    def test_status_failure_never_delivers_cached_frame(self):
        hub = self.hub()
        self.addCleanup(hub.stop)
        hub.start()
        self.assertIn("jpeg_base64", hub.frame(timeout=2))
        hub.stop()
        for mode in ("invalid", "false"):
            with self.subTest(mode=mode):
                self.state.status_mode = mode
                failed = hub.frame(after_seq=0, timeout=.2)
                self.assertNotIn("jpeg_base64", failed)
                self.assertEqual(failed["error"]["code"],
                                 "invalid_response" if mode == "invalid" else "http_error")

    def test_status_failure_recovers_a_started_stream_with_a_fresh_frame(self):
        hub = self.hub()
        self.addCleanup(hub.stop)
        hub.start()
        first = hub.frame(timeout=2)
        self.assertIn("jpeg_base64", first)
        first_seq = first["seq"]
        self.state.status_failures = 1

        failed = hub.frame(after_seq=first_seq, timeout=.2)
        self.assertEqual(failed["error"]["code"], "offline")
        self.assertNotIn("jpeg_base64", failed)

        recovered = hub.frame(after_seq=first_seq, timeout=2)
        self.assertIn("jpeg_base64", recovered)
        self.assertGreater(recovered["seq"], first_seq)

    def test_connect_closes_socket_when_request_send_fails(self):
        hub = self.hub()

        class BrokenSocket:
            def __init__(self):
                self.closed = False

            def settimeout(self, _):
                pass

            def sendall(self, _):
                raise OSError("send failed")

            def shutdown(self, _):
                pass

            def close(self):
                self.closed = True

        sock = BrokenSocket()
        with patch("adb_screen.socket.create_connection", return_value=sock):
            with self.assertRaises(OSError):
                hub._connect(threading.Event())
        self.assertTrue(sock.closed)

    def test_repeated_start_stop_waits_for_worker_and_never_runs_two_streams(self):
        hub = self.hub()
        active = 0
        maximum = 0
        lock = threading.Lock()
        started = threading.Event()

        def stream(stop_event):
            nonlocal active, maximum
            self.assertIsInstance(stop_event, threading.Event)
            with lock:
                active += 1
                maximum = max(maximum, active)
            started.set()
            try:
                stop_event.wait(2)
            finally:
                with lock:
                    active -= 1

        hub._stream_once = stream
        self.addCleanup(hub.stop)
        for _ in range(3):
            started.clear()
            self.assertTrue(hub.start())
            self.assertTrue(started.wait(1))
            hub.stop()
            self.assertFalse(hub.running())
            with lock:
                self.assertEqual(active, 0)
        self.assertEqual(maximum, 1)


if __name__ == "__main__":
    unittest.main()
