"""Same-origin loopback preview, sharing Runtime's controller and operation lock.

The random path is a per-process capability. The phone pairing token stays in
Python. A preview action must refer to a recently served frame; none are retried.
"""
import collections
import json
import secrets
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from adb_client import BridgeError


class PreviewServer:
    def __init__(self, screen, action_callback, context_callback):
        self.screen = screen
        self.action_callback = action_callback
        self.context_callback = context_callback
        self._prefix = "/" + secrets.token_urlsafe(24)
        self._server = None
        self._thread = None
        self._served = collections.OrderedDict()
        self._lock = threading.Lock()

    def start(self):
        with self._lock:
            if self._server:
                return self.url
            owner = self

            class Handler(BaseHTTPRequestHandler):
                def log_message(self, *_):
                    pass  # URLs contain the preview capability.

                def reply(self, status, data, mime="application/json"):
                    payload = data if isinstance(data, bytes) else json.dumps(data, ensure_ascii=False).encode()
                    self.send_response(status)
                    self.send_header("Content-Type", mime)
                    self.send_header("Content-Length", str(len(payload)))
                    self.send_header("Cache-Control", "no-store")
                    self.send_header("Referrer-Policy", "no-referrer")
                    self.send_header("X-Content-Type-Options", "nosniff")
                    self.send_header("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'")
                    self.end_headers()
                    self.wfile.write(payload)

                def route(self):
                    path = urlsplit(self.path).path
                    if self.headers.get("Host") != urlsplit(owner.url).netloc:
                        return None
                    if not path.startswith(owner._prefix + "/"):
                        return None
                    return path[len(owner._prefix):]

                def failure(self, error):
                    status = 409 if error.code in {"device_busy", "preview_paused", "stale_frame",
                                                  "stale_observation", "observation_mismatch"} else 400
                    self.reply(status, {"error": error.as_dict()})

                def do_GET(self):  # noqa: N802
                    route = self.route()
                    if route == "/":
                        page = Path(__file__).with_name("phone_preview.html").read_bytes()
                        return self.reply(200, page, "text/html; charset=utf-8")
                    if route == "/api/frame":
                        try:
                            value = parse_qs(urlsplit(self.path).query).get("after_seq", ["0"])[0]
                            after = int(value)
                            if not 0 <= after <= 9007199254740991:
                                raise ValueError()
                        except (ValueError, TypeError):
                            return self.reply(400, {"error": {"code": "invalid_argument"}})
                        frame = owner.screen.frame(after_seq=after, timeout=.6)
                        data = frame or {"seq": after, "timeout": True}
                        data = {**data, "paused": owner.screen.paused(), "stream_running": owner.screen.running()}
                        if data["paused"]:
                            data.pop("jpeg_base64", None)
                        elif data.get("jpeg_base64"):
                            with owner._lock:
                                owner._served[data["seq"]] = (time.monotonic(), owner.screen.capture_epoch)
                                while len(owner._served) > 32:
                                    owner._served.popitem(last=False)
                        return self.reply(200, data)
                    if route == "/api/context":
                        try:
                            if owner.screen.paused():
                                raise BridgeError("preview_paused", "Authentication handoff is active.")
                            return self.reply(200, owner.context_callback())
                        except BridgeError as error:
                            return self.failure(error)
                    return self.reply(404, {"error": {"code": "not_found"}})

                def do_POST(self):  # noqa: N802
                    if self.route() != "/api/action":
                        return self.reply(404, {"error": {"code": "not_found"}})
                    if self.headers.get("Origin") != owner.origin:
                        return self.reply(403, {"error": {"code": "origin_rejected"}})
                    try:
                        length = int(self.headers.get("Content-Length", "0"))
                        if not 0 < length <= 16384 or self.headers.get("Content-Type") != "application/json":
                            raise ValueError()
                        body = json.loads(self.rfile.read(length))
                        if not isinstance(body, dict) or set(body) != {"tool", "arguments", "frame_seq"}:
                            raise ValueError()
                        owner.check_frame(body["frame_seq"])
                        result = owner.action_callback({"tool": body["tool"], "arguments": body["arguments"]})
                        return self.reply(200, result)
                    except BridgeError as error:
                        return self.failure(error)
                    except (ValueError, TypeError, UnicodeDecodeError):
                        return self.reply(400, {"error": {"code": "invalid_argument", "message": "Invalid preview request."}})

            self._server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
            self._server.daemon_threads = True
            self.origin = f"http://127.0.0.1:{self._server.server_address[1]}"
            self.url = self.origin + self._prefix + "/"
            self._thread = threading.Thread(target=self._server.serve_forever, name="aua-preview", daemon=True)
            self._thread.start()
            return self.url

    def check_frame(self, seq):
        if self.screen.refresh_pause():
            raise BridgeError("preview_paused", "Resume after authentication before controlling the phone.")
        with self._lock:
            served = self._served.get(seq) if isinstance(seq, int) and not isinstance(seq, bool) else None
        latest = self.screen.latest()
        if (not served or served[1] != self.screen.capture_epoch or time.monotonic() - served[0] > 3
                or not latest or time.time() - latest["at"] > 3):
            raise BridgeError("stale_frame", "Wait for a fresh preview frame before acting.",
                              details={"action_executed": False, "replay_action": False})

    def stop(self):
        server = self._server
        if server:
            self._server = None
            server.shutdown()
            server.server_close()
            self._thread.join(timeout=2)
