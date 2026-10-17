"""Transport regressions for idle keep-alive sockets and uncertain actions."""

import json
import socket
import sys
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "server"))

import adb_client  # noqa: E402
from adb_client import BridgeClient, BridgeError  # noqa: E402


class IdleBridgeState:
    def __init__(self):
        self.connections = 0
        self.actions = 0
        self.last_action = None
        self.drop_actions = False
        self.lock = threading.Lock()


class IdleBridgeHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        pass

    def setup(self):
        super().setup()
        self.connection.settimeout(self.server.idle_timeout)
        with self.server.state.lock:
            self.server.state.connections += 1

    def handle(self):
        self.close_connection = False
        while not self.close_connection:
            try:
                self.handle_one_request()
            except socket.timeout:
                self.close_connection = True

    def reply(self, status=200, body=None):
        payload = json.dumps(body or {"ok": True}).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Connection", "keep-alive")
        self.end_headers()
        self.wfile.write(payload)
        self.wfile.flush()

    def do_GET(self):  # noqa: N802
        if self.path != "/status":
            return self.reply(404, {"ok": False})
        self.reply(200, {"ok": True, "screen_paused": False})

    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length) or b"{}")
        with self.server.state.lock:
            self.server.state.actions += 1
            self.server.state.last_action = body
        if self.server.state.drop_actions:
            self.close_connection = True
            try:
                self.connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            return
        self.reply(200, {"ok": True, "performed": True})


class ClientIdleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), IdleBridgeHandler)
        cls.server.state = IdleBridgeState()
        cls.server.idle_timeout = 0.05
        cls.server.daemon_threads = True
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2)

    def setUp(self):
        with self.server.state.lock:
            self.server.state.connections = 0
            self.server.state.actions = 0
            self.server.state.last_action = None
            self.server.state.drop_actions = False

    def client(self):
        client = BridgeClient(
            base_url=f"http://127.0.0.1:{self.server.server_port}",
            timeout=2,
        )
        self.addCleanup(client.close)
        return client

    def test_idle_connection_is_replaced_before_action_without_replaying(self):
        client = self.client()
        client.session_id = "session-kept-across-transport-refresh"
        with patch.object(adb_client, "CONNECTION_IDLE_SECONDS", 0.01, create=True):
            self.assertTrue(client.request("GET", "/status")["ok"])
            time.sleep(0.1)
            result = client.session("POST", "/action", {"kind": "press_key", "key": "home"})

        self.assertTrue(result["ok"])
        self.assertEqual(client.session_id, "session-kept-across-transport-refresh")
        self.assertEqual(self.server.state.actions, 1)
        self.assertEqual(self.server.state.last_action["session_id"], client.session_id)
        self.assertGreaterEqual(self.server.state.connections, 2)

    def test_transmit_failure_returns_uncertain_without_retrying_action(self):
        client = self.client()
        self.server.state.drop_actions = True

        with self.assertRaises(BridgeError) as caught:
            client.request("POST", "/action", {"kind": "press_key", "key": "home"})

        self.assertEqual(caught.exception.code, "action_uncertain")
        self.assertTrue(caught.exception.uncertain)
        self.assertEqual(self.server.state.actions, 1)


if __name__ == "__main__":
    unittest.main()
