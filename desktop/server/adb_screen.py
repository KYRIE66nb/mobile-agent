"""MJPEG screen preview: one persistent /screen stream, latest frame kept for tools.

The preview is display-only and does not prove control readiness. Frames are bounded
JPEGs produced by the bridge; the hub just keeps the newest one so aua_screen can
hand it to the model or a browser page can poll it.
"""
from __future__ import annotations

import base64
import http.client
import json
import socket
import threading
import time
from urllib.parse import urlencode

from adb_client import BridgeError, LOCAL_PORT


STATUS_TIMEOUT = 2
STREAM_READ_TIMEOUT = 2
MAX_STATUS_BYTES = 1 * 1024 * 1024


class ScreenHub:
    def __init__(self, host="127.0.0.1", port=LOCAL_PORT, token="", device_port=17325):
        self.host, self.port, self.token = host, port, token
        self.device_port = device_port
        self._socket = None
        self._thread = None
        self._stop = None
        self._lock = threading.RLock()
        self._lifecycle_lock = threading.RLock()
        self._latest = None          # {"seq": int, "jpeg": bytes, "at": float}
        self._seq = 0                # monotonically increasing across reconnects
        self._local_paused = False
        self._remote_paused = False
        self._remote_pause_known = False
        self._paused = False
        self.capture_epoch = 0
        self._resume_after = 0
        self._restart_after_status_error = False
        self.error = None

    def paused(self):
        with self._lock:
            return self._paused

    def set_paused(self, paused):
        paused = bool(paused)
        with self._lock:
            if paused:
                changed = not self._local_paused
                self._local_paused = True
                self._paused = True
                self._restart_after_status_error = False
                if changed:
                    self.capture_epoch += 1
            else:
                # The caller only clears this state after the bridge resume
                # request succeeded. Do not let an old remote state survive it.
                self._local_paused = False
                self._remote_paused = False
                self._remote_pause_known = True
                self._paused = False
                self._restart_after_status_error = False
                self.capture_epoch += 1
                self._resume_after = time.time()
        if paused:
            self.stop()

    def _status(self):
        """Read remote pause state on a separate bounded loopback connection."""
        connection = http.client.HTTPConnection(self.host, self.port, timeout=STATUS_TIMEOUT)
        try:
            headers = {"Accept": "application/json"}
            if self.token:
                headers["Authorization"] = f"Bearer {self.token}"
            connection.request("GET", "/status", headers=headers)
            response = connection.getresponse()
            raw = response.read(MAX_STATUS_BYTES + 1)
            if len(raw) > MAX_STATUS_BYTES:
                raise BridgeError("response_too_large", "Bridge status response exceeds 1 MiB.")
            try:
                document = json.loads(raw)
            except (ValueError, UnicodeDecodeError) as error:
                raise BridgeError("invalid_response", "Bridge status returned invalid JSON.") from error
            if response.status >= 400 or (isinstance(document, dict) and document.get("ok") is False):
                detail = document.get("error") if isinstance(document, dict) else None
                code = detail.get("code") if isinstance(detail, dict) else None
                message = detail.get("message") if isinstance(detail, dict) else None
                raise BridgeError(code or "http_error", str(message or f"Bridge status returned HTTP {response.status}."))
            if not isinstance(document, dict) or not isinstance(document.get("screen_paused"), bool):
                raise BridgeError("invalid_response", "Bridge status did not include a boolean screen_paused field.")
            return document["screen_paused"]
        except BridgeError:
            raise
        except (OSError, socket.timeout, http.client.HTTPException) as error:
            raise BridgeError("aua_unreachable", f"Could not refresh remote preview state: {error}") from error
        finally:
            connection.close()

    def refresh_pause(self):
        """Synchronize remote pause state without allowing it to clear local pause."""
        try:
            remote_paused = self._status()
        except BridgeError as error:
            was_running = self.running()
            with self._lock:
                self.error = str(error)
                self._restart_after_status_error = was_running and not self._local_paused
                self.capture_epoch += 1
                self._resume_after = time.time()
            self.stop()
            raise

        should_stop = False
        should_start = False
        with self._lock:
            old_remote = self._remote_paused
            old_effective = self._paused
            self._remote_pause_known = True
            self._remote_paused = remote_paused
            self._paused = self._local_paused or self._remote_paused
            if self._paused:
                self._restart_after_status_error = False
            if old_remote != remote_paused:
                self.capture_epoch += 1
                if not remote_paused:
                    self._resume_after = time.time()
            should_stop = self._paused and not old_effective
            should_start = (
                not self._paused
                and not self._local_paused
                and (old_remote and not remote_paused or self._restart_after_status_error)
            )
            self.error = None
            effective = self._paused
        if should_stop:
            self.stop()
        elif should_start:
            if self.start():
                with self._lock:
                    self._restart_after_status_error = False
        return effective

    def url(self, query_token=True):
        query = f"?{urlencode({'token': self.token})}" if query_token and self.token else ""
        return f"http://{self.host}:{self.port}/screen{query}"

    def latest(self):
        with self._lock:
            return self._latest

    def running(self):
        with self._lifecycle_lock:
            return self._thread is not None and self._thread.is_alive()

    def start(self):
        with self._lifecycle_lock:
            if self.paused():
                return False
            if self._thread is not None:
                if self._thread.is_alive():
                    return not (self._stop and self._stop.is_set())
                self._thread = None
                self._stop = None
            stop_event = threading.Event()
            thread = threading.Thread(target=self._run, args=(stop_event,),
                                      name="aua-screen", daemon=True)
            self._stop = stop_event
            self._thread = thread
            self.error = None
            try:
                thread.start()
            except BaseException:
                self._thread = None
                self._stop = None
                raise
            return True

    def stop(self):
        with self._lifecycle_lock:
            thread = self._thread
            stop_event = self._stop
            sock = self._socket
            if stop_event:
                stop_event.set()
            if sock is self._socket:
                self._socket = None
        self._close_socket(sock)
        if thread:
            thread.join(timeout=6)
        with self._lifecycle_lock:
            if thread is not None and thread.is_alive():
                self.error = "Screen stream did not stop within the bounded shutdown wait."
                return False
            if self._thread is thread:
                self._thread = None
                self._stop = None
        return True

    @staticmethod
    def _close_socket(sock):
        if not sock:
            return
        try:
            sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            sock.close()
        except OSError:
            pass

    def _run(self, stop_event):
        while not stop_event.is_set():
            try:
                self._stream_once(stop_event)
            except Exception as error:
                if stop_event.is_set():
                    break
                self.error = str(error)
            if stop_event.is_set():
                break
            stop_event.wait(1.0)   # interruptible reconnect backoff

    def _connect(self, stop_event):
        sock = socket.create_connection((self.host, self.port), timeout=5)
        try:
            sock.settimeout(STREAM_READ_TIMEOUT)
            path = f"/screen?{urlencode({'token': self.token})}" if self.token else "/screen"
            request = (f"GET {path} HTTP/1.1\r\nHost: {self.host}:{self.port}\r\n"
                       f"Accept: multipart/x-mixed-replace\r\nConnection: close\r\n\r\n")
            sock.sendall(request.encode())
            with self._lifecycle_lock:
                if stop_event.is_set():
                    raise ConnectionError("screen stream stopped during connect")
                self._socket = sock
            return sock
        except BaseException:
            self._close_socket(sock)
            raise

    def _read_until(self, sock, buffered, marker, limit=16384):
        while marker not in buffered:
            if len(buffered) > limit:
                raise ValueError("screen stream header exceeded limit")
            chunk = sock.recv(4096)
            if not chunk:
                raise ConnectionError("screen stream closed")
            buffered += chunk
        head, _, rest = buffered.partition(marker)
        return head, rest

    def _stream_once(self, stop_event):
        sock = self._connect(stop_event)
        try:
            buffered = b""
            header, buffered = self._read_until(sock, buffered, b"\r\n\r\n")
            if b"200" not in header.split(b"\r\n", 1)[0]:
                raise ConnectionError(f"screen stream rejected: {header[:200]!r}")
            while not stop_event.is_set():
                # Frames arrive as: --boundary\r\nContent-Type: image/jpeg\r\nContent-Length: n\r\n\r\n<bytes>\r\n
                part_head, buffered = self._read_until(sock, buffered, b"\r\n\r\n")
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
                if stop_event.is_set() or self.paused():
                    continue
                with self._lock:
                    self._seq += 1
                    self._latest = {"seq": self._seq, "jpeg": frame, "at": time.time()}
        finally:
            with self._lifecycle_lock:
                if self._socket is sock:
                    self._socket = None
            self._close_socket(sock)

    def frame(self, after_seq=0, timeout=8):
        """Latest frame once its sequence exceeds after_seq; bounded wait for previews."""
        try:
            if self.refresh_pause():
                return {"paused": True, "seq": after_seq}
        except BridgeError as error:
            return {"seq": after_seq, "timeout": True, "paused": self.paused(),
                    "error": error.as_dict()}
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.paused():
                return {"paused": True, "seq": after_seq}
            latest = self.latest()
            if latest and latest["seq"] > after_seq and latest["at"] > self._resume_after:
                return {"seq": latest["seq"],
                        "jpeg_base64": base64.b64encode(latest["jpeg"]).decode(),
                        "mimeType": "image/jpeg", "age_seconds": round(time.time() - latest["at"], 2)}
            time.sleep(0.2)
        return None
