"""MJPEG screen preview: one persistent /screen stream, latest frame kept for tools.

The preview is display-only and does not prove control readiness. Frames are bounded
JPEGs produced by the bridge; the hub just keeps the newest one so aua_screen can
hand it to the model or a browser page can poll it.
"""
from __future__ import annotations

import base64
import socket
import threading
import time
from urllib.parse import urlencode

from adb_client import LOCAL_PORT


class ScreenHub:
    def __init__(self, host="127.0.0.1", port=LOCAL_PORT, token="", device_port=17325):
        self.host, self.port, self.token = host, port, token
        self.device_port = device_port
        self._socket = None
        self._thread = None
        self._stop = threading.Event()
        self._lock = threading.Lock()
        self._latest = None          # {"seq": int, "jpeg": bytes, "at": float}
        self._seq = 0                # monotonically increasing across reconnects
        self.error = None

    def url(self, query_token=True):
        query = f"?{urlencode({'token': self.token})}" if query_token and self.token else ""
        return f"http://{self.host}:{self.port}/screen{query}"

    def latest(self):
        with self._lock:
            return self._latest

    def running(self):
        return self._thread is not None and self._thread.is_alive()

    def start(self):
        if self.running():
            return True
        self._stop.clear()
        self.error = None
        self._thread = threading.Thread(target=self._run, name="aua-screen", daemon=True)
        self._thread.start()
        return True

    def stop(self):
        self._stop.set()
        try:
            if self._socket:
                self._socket.close()
        except OSError:
            pass
        thread = self._thread
        self._thread = None
        if thread:
            thread.join(timeout=2)

    def _run(self):
        while not self._stop.is_set():
            try:
                self._stream_once()
            except Exception as error:
                self.error = str(error)
            if self._stop.is_set():
                break
            time.sleep(1.0)   # bounded reconnect backoff
        self._socket = None

    def _connect(self):
        try:
            if self._socket:
                self._socket.close()
        except OSError:
            pass
        sock = socket.create_connection((self.host, self.port), timeout=5)
        sock.settimeout(10)
        path = f"/screen?{urlencode({'token': self.token})}" if self.token else "/screen"
        request = (f"GET {path} HTTP/1.1\r\nHost: {self.host}:{self.port}\r\n"
                   f"Accept: multipart/x-mixed-replace\r\nConnection: close\r\n\r\n")
        sock.sendall(request.encode())
        self._socket = sock
        return sock

    def _read_until(self, buffered, marker, limit=16384):
        while marker not in buffered:
            if len(buffered) > limit:
                raise ValueError("screen stream header exceeded limit")
            chunk = self._socket.recv(4096)
            if not chunk:
                raise ConnectionError("screen stream closed")
            buffered += chunk
        head, _, rest = buffered.partition(marker)
        return head, rest

    def _stream_once(self):
        sock = self._connect()
        buffered = b""
        header, buffered = self._read_until(buffered, b"\r\n\r\n")
        if b"200" not in header.split(b"\r\n", 1)[0]:
            raise ConnectionError(f"screen stream rejected: {header[:200]!r}")
        while not self._stop.is_set():
            # Frames arrive as: --boundary\r\nContent-Type: image/jpeg\r\nContent-Length: n\r\n\r\n<bytes>\r\n
            part_head, buffered = self._read_until(buffered, b"\r\n\r\n")
            length = None
            for line in part_head.split(b"\r\n"):
                if line.lower().startswith(b"content-length:"):
                    length = int(line.split(b":", 1)[1].strip())
            if length is None or not 0 < length <= 4 * 1024 * 1024:
                raise ValueError("screen frame missing a valid Content-Length")
            while len(buffered) < length:
                chunk = sock.recv(min(65536, length - len(buffered)))
                if not chunk:
                    raise ConnectionError("screen stream closed mid-frame")
                buffered += chunk
            frame, buffered = buffered[:length], buffered[length:]
            buffered = buffered.lstrip(b"\r\n") if buffered[:2] == b"\r\n" else buffered
            with self._lock:
                self._seq += 1
                self._latest = {"seq": self._seq, "jpeg": frame, "at": time.time()}

    def frame(self, after_seq=0, timeout=8):
        """Latest frame once its sequence exceeds after_seq; bounded wait for previews."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            latest = self.latest()
            if latest and latest["seq"] > after_seq:
                return {"seq": latest["seq"],
                        "jpeg_base64": base64.b64encode(latest["jpeg"]).decode(),
                        "mimeType": "image/jpeg", "age_seconds": round(time.time() - latest["at"], 2)}
            time.sleep(0.2)
        return None
