"""Persistent loopback HTTP transport to the on-device desktop bridge.

The bridge is reached through `adb forward tcp:LOCAL tcp:DEVICE`; only 127.0.0.1
endpoints are accepted, mirroring iphone-use's WDA_URL policy. Read requests may
retry once on transport failure; actions are never replayed implicitly.
"""
import collections
import http.client
import json
import socket
import subprocess
import time
from urllib.parse import urlsplit

LOCAL_PORT = 18301
DEVICE_PORT = 17325
CONNECTION_IDLE_SECONDS = 10.0


class BridgeError(Exception):
    def __init__(self, code, message, uncertain=False, details=None):
        super().__init__(message)
        self.code, self.uncertain, self.details = code, uncertain, details or {}

    def as_dict(self):
        return {"code": self.code, "message": str(self), "uncertain": self.uncertain, **self.details}


def find_adb():
    import os
    import shutil
    override = os.environ.get("ADB", "").strip()
    if override:
        return override
    path = shutil.which("adb")
    if path:
        return path
    # Common macOS platform-tools location when adb is not on PATH.
    candidate = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
    return candidate if os.path.isfile(candidate) else "adb"


def adb(*arguments, serial=None, timeout=15):
    command = [find_adb()]
    if serial:
        command += ["-s", serial]
    command += list(arguments)
    completed = subprocess.run(command, stdin=subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               timeout=timeout)
    return completed.returncode, completed.stdout.decode("utf-8", "replace")


def ensure_forward(serial=None, local_port=LOCAL_PORT, device_port=DEVICE_PORT):
    """adb forward is idempotent; rerun it whenever the forwarded socket refuses."""
    code, out = adb("forward", f"tcp:{local_port}", f"tcp:{device_port}",
                    serial=serial, timeout=10)
    if code != 0:
        raise BridgeError("forward_failed",
                          f"adb forward failed: {out.strip()[:400]}",
                          details={"adb_output": out.strip()[:1000]})


class BridgeClient:
    def __init__(self, base_url=f"http://127.0.0.1:{LOCAL_PORT}", token="", serial=None,
                 device_port=DEVICE_PORT, timeout=15):
        parsed = urlsplit(base_url)
        if (parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "localhost", "::1")
                or parsed.path not in ("", "/") or parsed.username or parsed.query or parsed.fragment):
            raise ValueError("Bridge URL must be a local HTTP endpoint; forward adb to loopback first.")
        self.host, self.port = parsed.hostname, parsed.port or 80
        self.token, self.serial, self.device_port = token, serial, device_port
        self.timeout, self.connection, self.session_id = timeout, None, None
        self._last_http_use = None
        self.records = collections.deque(maxlen=2000)
        self._forwarded = False

    def close(self):
        if self.connection:
            self.connection.close()
        self.connection = None
        self._last_http_use = None

    @staticmethod
    def is_read(method, path):
        route = path.split("?", 1)[0].rstrip("/")
        if method == "GET":
            return route in ("/status", "/apps", "/screen")
        return method == "POST" and route in ("/observe", "/inspect")

    @staticmethod
    def remaining(deadline):
        budget = deadline - time.monotonic()
        if budget <= 0:
            raise BridgeError("aua_unreachable", "Bridge deadline expired; no phone action was replayed.")
        return budget

    def request(self, method, path, payload=None, timeout=None):
        deadline = time.monotonic() + (self.timeout if timeout is None else timeout)
        readonly = self.is_read(method, path)
        attempts = 3 if readonly else 1
        for attempt in range(attempts):
            try:
                return self._request_once(method, path, payload, self.remaining(deadline), readonly)
            except BridgeError as exc:
                if not (readonly and attempt < attempts - 1 and exc.code == "aua_unreachable"
                        and time.monotonic() < deadline):
                    raise
                # A refused loopback usually means the forward was dropped: restore it once,
                # then retry the read inside the original budget.
                self.close()
                try:
                    ensure_forward(serial=self.serial, local_port=self.port,
                                   device_port=self.device_port)
                except BridgeError:
                    if attempt == attempts - 1:
                        raise

    def _request_once(self, method, path, payload, budget, readonly):
        started = time.monotonic()
        deadline = started + budget
        status, code, received = None, None, 0
        try:
            if (self.connection is not None and self._last_http_use is not None
                    and time.monotonic() - self._last_http_use >= CONNECTION_IDLE_SECONDS):
                self.close()
            if self.connection is None:
                self.connection = http.client.HTTPConnection(self.host, self.port, timeout=budget)
            self.connection.timeout = budget
            if self.connection.sock:
                self.connection.sock.settimeout(budget)
            body = None if payload is None else json.dumps(payload, ensure_ascii=False, allow_nan=False).encode()
            headers = {"Content-Type": "application/json", "Accept": "application/json"}
            if self.token:
                headers["Authorization"] = f"Bearer {self.token}"
            self.connection.request(method, path, body, headers)
            network_socket = self.connection.sock
            if network_socket:
                network_socket.settimeout(max(0.05, deadline - time.monotonic()))
            response = self.connection.getresponse()
            status = response.status
            chunks, size, limit = [], 0, 24 * 1024 * 1024 + 1
            while size < limit and not response.isclosed():
                if network_socket:
                    network_socket.settimeout(max(0.05, deadline - time.monotonic()))
                if time.monotonic() >= deadline:
                    raise socket.timeout("bridge response deadline expired")
                chunk = response.read1(min(65536, limit - size))
                if not chunk:
                    break
                chunks.append(chunk)
                size += len(chunk)
            raw = b"".join(chunks)
            received = len(raw)
            response.close()
            if len(raw) > 24 * 1024 * 1024:
                raise BridgeError("response_too_large", "Bridge response exceeds 24 MiB.", uncertain=not readonly)
            try:
                result = json.loads(raw)
            except (ValueError, UnicodeDecodeError) as exc:
                raise BridgeError("invalid_response", "Bridge returned invalid JSON.", uncertain=not readonly) from exc
            if not isinstance(result, dict):
                raise BridgeError("invalid_response", "Bridge returned a non-object.", uncertain=not readonly)
            if result.get("ok") is False or status >= 400:
                error = result.get("error") if isinstance(result.get("error"), dict) else {}
                raise BridgeError(error.get("code") or "http_error",
                                  str(error.get("message") or f"Bridge returned HTTP {status}.")[:1500])
            return result
        except BridgeError as exc:
            code = exc.code
            if exc.uncertain or exc.code in ("response_too_large", "session_expired", "session_busy"):
                self.close()
            raise
        except (OSError, socket.timeout, http.client.HTTPException) as exc:
            self.close()
            code = "aua_unreachable" if readonly else "action_uncertain"
            message = ("Bridge read connection failed or timed out; no phone action was executed."
                       if readonly else
                       "Bridge connection failed or timed out. Read fresh state before deciding whether to repeat an action.")
            raise BridgeError(code, message, uncertain=not readonly) from exc
        finally:
            if self.connection is not None:
                self._last_http_use = time.monotonic()
            endpoint = path.split("?")[0]
            self.records.append({"method": method, "endpoint": endpoint,
                                 "seconds": round(time.monotonic() - started, 4),
                                 "status": status, "error": code, "bytes": received})

    def ensure_session(self, timeout=None):
        if self.session_id:
            return self.session_id
        result = self.request("POST", "/session", {}, timeout=timeout)
        self.session_id = result.get("session_id")
        if not self.session_id:
            raise BridgeError("invalid_response", "Bridge did not return a session ID.")
        return self.session_id

    def session(self, method, path, payload=None, timeout=None, retry=True):
        """Session-scoped call: reopens the bridge session once on expiry for reads."""
        deadline = time.monotonic() + (self.timeout if timeout is None else timeout)
        for attempt in range(2):
            session_id = self.ensure_session(timeout=self.remaining(deadline))
            body = dict(payload or {})
            body.setdefault("session_id", session_id)
            try:
                return self.request(method, path, body, self.remaining(deadline))
            except BridgeError as exc:
                if exc.code == "session_expired" and attempt == 0 and retry:
                    self.session_id = None
                    if self.is_read(method, path):
                        continue
                raise

    def clear_metrics(self):
        self.records.clear()

    def metrics(self):
        endpoints = collections.defaultdict(list)
        for record in self.records:
            endpoints[record["endpoint"]].append(record)
        return {"retained_requests": len(self.records),
                "http_seconds": round(sum(r["seconds"] for r in self.records), 4),
                "http_bytes": sum(r["bytes"] for r in self.records),
                "errors": sum(bool(r["error"]) for r in self.records),
                "endpoints": {k: {"count": len(v), "seconds": round(sum(r["seconds"] for r in v), 4),
                                  "max_seconds": max(r["seconds"] for r in v),
                                  "bytes": sum(r["bytes"] for r in v)}
                              for k, v in endpoints.items()},
                "measurement": "Transport time and response bytes only; model, host scheduling and user wait are outside this process."}
