"""ADB-side setup: device discovery, pairing token, accessibility enable, bridge start, forward.

Replaces iphone-use's wda_setup.py — Android needs no signing/build: adb enables the
accessibility service, the pairing provider (SHELL_UID-only) hands out the token,
and the exported app activity restores the bridge when its persisted setting is on.
"""
from __future__ import annotations

import json
import re
import time
from pathlib import Path

from adb_client import LOCAL_PORT, DEVICE_PORT, BridgeClient, BridgeError, adb, ensure_forward

APP_PACKAGE = "xyz.chouxuewei.mobile_agent"
APP_COMPONENT = f"{APP_PACKAGE}/xyz.chouxuewei.mobile_agent.device.accessibility.AgentAccessibilityService"
MAIN_ACTIVITY_COMPONENT = f"{APP_PACKAGE}/.MainActivity"
BRIDGE_COMPONENT = f"{APP_PACKAGE}/xyz.chouxuewei.mobile_agent.device.bridge.BridgeService"
PAIRING_URI = f"content://{APP_PACKAGE}.bridge"


class SetupManager:
    def __init__(self, state_dir):
        self.state_dir = Path(state_dir)
        self.state_dir.mkdir(mode=0o700, parents=True, exist_ok=True)

    # ------------------------------------------------------------------ config

    @property
    def config_path(self):
        return self.state_dir / "config.json"

    @property
    def config(self):
        try:
            data = json.loads(self.config_path.read_text())
            return data if isinstance(data, dict) else {}
        except (OSError, ValueError):
            return {}

    def _save(self, config):
        self.config_path.write_text(json.dumps(config, ensure_ascii=False, indent=2))
        self.config_path.chmod(0o600)

    def serial(self):
        value = self.config.get("serial")
        return value if isinstance(value, str) else None

    def token(self):
        value = self.config.get("token")
        return value if isinstance(value, str) else None

    def client(self, timeout=15):
        return BridgeClient(base_url=f"http://127.0.0.1:{int(self.config.get('local_port', LOCAL_PORT))}",
                            token=self.token() or "", serial=self.serial(),
                            device_port=int(self.config.get("device_port", DEVICE_PORT)),
                            timeout=timeout)

    # ------------------------------------------------------------------ adb probes

    @staticmethod
    def _adb_ok(out):
        return lambda code, text: code == 0 and "error" not in text.lower().split("\n")[0]

    def discover(self):
        code, out = adb("devices", "-l", timeout=10)
        if code != 0:
            return {"ok": False, "error": {"code": "adb_unavailable",
                                         "message": f"adb devices failed: {out.strip()[:400]}. Install Android platform-tools and add adb to PATH (or set ADB)."},
                    "devices": []}
        devices = []
        for line in out.splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2:
                serial, state = parts[0], parts[1]
                devices.append({"serial": serial, "state": state,
                                "authorized": state == "device"})
        return {"ok": True, "devices": devices,
                "next_action": "Pick an authorized device (state=device) and call aua_setup configure with its serial. A 'unauthorized' device needs USB-debugging confirmation on its screen."}

    def configure(self, serial):
        if not isinstance(serial, str) or not re.fullmatch(r"[A-Za-z0-9._:-]{2,128}", serial):
            return {"ok": False, "error": {"code": "invalid_argument",
                                         "message": "serial must be a device serial from aua_setup discover."}}
        config = dict(self.config)
        config["serial"] = serial
        config.setdefault("local_port", LOCAL_PORT)
        config.setdefault("device_port", DEVICE_PORT)
        self._save(config)
        return {"ok": True, "serial": serial}

    def _installed(self, serial):
        code, out = adb("shell", "pm", "path", APP_PACKAGE, serial=serial, timeout=15)
        return code == 0 and "package:" in out

    def _accessibility_enabled(self, serial):
        code, out = adb("shell", "settings", "get", "secure", "enabled_accessibility_services",
                        serial=serial, timeout=15)
        return code == 0 and APP_COMPONENT.split("/", 1)[1] in out

    def enable_accessibility(self):
        serial = self.serial()
        if not serial:
            return {"ok": False, "error": {"code": "device_not_selected",
                                         "message": "Run aua_setup discover then configure first."}}
        code, out = adb("shell", "settings", "get", "secure", "enabled_accessibility_services",
                        serial=serial, timeout=15)
        services = out.strip() if code == 0 and out.strip() not in ("", "null") else ""
        component = APP_COMPONENT
        if component in services:
            adb("shell", "settings", "put", "secure", "accessibility_enabled", "1", serial=serial)
            return {"ok": True, "already_enabled": True,
                    "accessibility_component": component}
        merged = f"{services}:{component}" if services else component
        code, out = adb("shell", "settings", "put", "secure", "enabled_accessibility_services",
                        merged, serial=serial, timeout=15)
        if code != 0:
            return {"ok": False, "error": {"code": "accessibility_enable_failed",
                                         "message": f"adb settings put failed: {out.strip()[:400]}. Enable the accessibility service manually in Android Settings."}}
        adb("shell", "settings", "put", "secure", "accessibility_enabled", "1", serial=serial)
        return {"ok": True, "already_enabled": False, "accessibility_component": component,
                "note": "The service was enabled through adb. If the phone shows a confirmation dialog, approve it on the device."}

    def pair(self):
        serial = self.serial()
        if not serial:
            return {"ok": False, "error": {"code": "device_not_selected",
                                         "message": "Run aua_setup discover then configure first."}}
        code, out = adb("shell", "content", "call", "--uri", PAIRING_URI,
                        "--method", "pairing_token", serial=serial, timeout=15)
        match = re.search(r"token=([0-9a-fA-F]{16,128})", out)
        if code != 0 or not match:
            return {"ok": False, "error": {"code": "pairing_failed",
                                         "message": f"Could not read the pairing token: {out.strip()[:400]}. Is the app installed and its provider exported?"}}
        config = dict(self.config)
        config["token"] = match.group(1)
        self._save(config)
        return {"ok": True, "paired": True,
                "note": "Token stored in the state directory (0600). It is never printed or logged."}

    @staticmethod
    def _bridge_status_enabled(output):
        match = re.search(r"\benabled\s*=\s*(true|false)\b", output, re.IGNORECASE)
        if not match:
            return None
        return match.group(1).lower() == "true"

    @staticmethod
    def _adb_error(operation, code, output):
        detail = output.strip()[:1000]
        lowered = detail.lower()
        if any(marker in lowered for marker in (
                "error type 3", "failed to resolve provider", "unable to find provider",
                "failed to find provider", "no provider found", "unknown uri",
                "unable to find package",
                "unknown package", "package not found", "not installed", "no such package",
                "does not exist")):
            error_code = "app_not_installed"
            message = "The Mobile Agent app is not installed, so the bridge cannot be activated."
        elif any(marker in lowered for marker in (
                "securityexception", "permission denial", "requires permission",
                "not exported", "unauthorized", "not authorized", "authorization")):
            error_code = "adb_authorization_denied"
            message = "ADB is not authorized to access the Mobile Agent app; authorize this device and retry."
        else:
            error_code = f"{operation}_failed"
            message = f"adb {operation} failed: {detail[:400]}"
        return {"ok": False, "state": "error",
                "error": {"code": error_code, "message": message,
                           "operation": operation, "exit_code": code,
                           "adb_output": detail}}

    def start_bridge(self):
        serial = self.serial()
        if not serial:
            return {"ok": False, "error": {"code": "device_not_selected",
                                         "message": "Run aua_setup discover then configure first."}}
        code, out = adb("shell", "content", "call", "--uri", PAIRING_URI,
                        "--method", "bridge_status", serial=serial, timeout=15)
        if code != 0:
            return self._adb_error("bridge_status", code, out)
        enabled = self._bridge_status_enabled(out)
        if enabled is None:
            return {"ok": False, "state": "error",
                    "error": {"code": "bridge_status_invalid",
                               "message": "Could not read the desktop bridge setting from the app.",
                               "operation": "bridge_status", "exit_code": code,
                               "adb_output": out.strip()[:1000]}}
        if not enabled:
            return {"ok": False, "state": "user_action_required", "started": False,
                    "error": {"code": "bridge_disabled",
                               "message": "Desktop control (USB) is disabled in the app."},
                    "next_action": "On the phone, open Settings → General → Desktop control (USB), turn it on, then run aua_setup start_bridge again."}

        code, out = adb("shell", "am", "start", "-n", MAIN_ACTIVITY_COMPONENT,
                        serial=serial, timeout=15)
        if code != 0:
            return self._adb_error("app_launch", code, out)
        return {"ok": True, "state": "app_launch_requested", "app_launch_requested": True,
                "next_action": "Run aua_setup forward, then aua_ready to verify HTTP bridge readiness and accessibility.",
                "note": "The app was activated; start_bridge does not verify HTTP readiness."}

    def stop_bridge(self):
        serial = self.serial()
        if not serial:
            return {"ok": False, "error": {"code": "device_not_selected",
                                         "message": "Run aua_setup discover then configure first."}}
        code, out = adb("shell", "am", "force-stop", APP_PACKAGE, serial=serial, timeout=15)
        return {"ok": code == 0, "stopped": code == 0,
                "note": "force-stop also kills the app UI; the bridge restarts on next app launch or start_bridge."}

    def forward(self, local_port=None, device_port=None):
        serial = self.serial()
        if not serial:
            return {"ok": False, "error": {"code": "device_not_selected",
                                         "message": "Run aua_setup discover then configure first."}}
        config = self.config
        local_port = int(config.get("local_port", LOCAL_PORT)) if local_port is None else local_port
        device_port = int(config.get("device_port", DEVICE_PORT)) if device_port is None else device_port
        if any(isinstance(port, bool) or not isinstance(port, int) or not 1024 <= port <= 65535
               for port in (local_port, device_port)):
            return {"ok": False, "error": {"code": "invalid_argument", "message": "Ports must be integers in 1024..65535."}}
        try:
            ensure_forward(serial=serial, local_port=local_port, device_port=device_port)
        except BridgeError as error:
            return {"ok": False, "error": error.as_dict()}
        config.update(local_port=local_port, device_port=device_port)
        self._save(config)
        return {"ok": True, "forward": f"tcp:{local_port} -> tcp:{device_port}"}

    # ------------------------------------------------------------------ status / ready

    def status(self):
        """Full pipeline check: adb -> device -> app -> accessibility -> pair -> bridge -> forward."""
        checks = []
        found = self.discover()
        adb_ok = found["ok"]
        checks.append({"step": "adb", "ok": adb_ok})
        serial = self.serial()
        devices = found.get("devices", [])
        device = next((d for d in devices if d["serial"] == serial), None) if serial else None
        authorized = bool(device and device["authorized"])
        checks.append({"step": "device", "ok": authorized,
                       "detail": device["state"] if device else ("no serial configured" if not serial else "not in adb devices")})
        if not authorized:
            return {"ok": False, "checks": checks, "devices": devices,
                    "next_action": "Connect the phone with USB debugging enabled, authorize this computer, then aua_setup discover/configure."}
        installed = self._installed(serial)
        checks.append({"step": "app_installed", "ok": installed})
        accessibility = self._accessibility_enabled(serial) if installed else False
        checks.append({"step": "accessibility", "ok": accessibility})
        paired = bool(self.token())
        checks.append({"step": "paired", "ok": paired})
        bridge = False
        if paired:
            code, out = adb("shell", "content", "call", "--uri", PAIRING_URI,
                            "--method", "bridge_status", serial=serial, timeout=15)
            bridge = code == 0 and "enabled=true" in out.replace(" ", "")
        checks.append({"step": "bridge_enabled", "ok": bridge})
        reachable = False
        if paired:
            probe = self.client(timeout=5)
            try:
                probe.request("GET", "/status")
                reachable = True
            except BridgeError:
                reachable = False
            finally:
                probe.close()
        checks.append({"step": "http_reachable", "ok": reachable})
        ok = all(c["ok"] for c in checks)
        missing = [c["step"] for c in checks if not c["ok"]]
        return {"ok": ok, "checks": checks, "serial": serial,
                "missing": missing,
                "next_action": None if ok else
                "Run aua_setup actions in order: enable_accessibility -> pair -> start_bridge -> forward, then aua_ready."}

    def ready(self):
        """End-to-end readiness: forward up, bridge reachable, session + tree read."""
        serial = self.serial()
        if not serial or not self.token():
            return {"ok": False, "error": {"code": "not_paired",
                                         "message": "No configured device/token. Run aua_setup status and follow its steps."}}
        config = self.config
        try:
            ensure_forward(serial=serial,
                           local_port=int(config.get("local_port", LOCAL_PORT)),
                           device_port=int(config.get("device_port", DEVICE_PORT)))
        except BridgeError as error:
            return {"ok": False, "error": error.as_dict()}
        client = self.client(timeout=10)
        try:
            status = client.request("GET", "/status")
            if not status.get("accessibility"):
                return {"ok": False, "ready": False, "error": {"code": "accessibility_off",
                        "message": "The bridge answers but accessibility is disconnected. Enable it on the phone."},
                        "status": status}
            if status.get("phone_locked"):
                return {"ok": False, "ready": False, "error": {"code": "phone_locked",
                        "message": "Unlock the phone before continuing."}, "status": status}
            if status.get("session_open") and isinstance(status.get("session_id"), str):
                client.session_id = status["session_id"]
            client.ensure_session()
            tree = client.session("POST", "/inspect", {"mode": "tree", "max_nodes": 5})
            return {"ok": True, "ready": True, "status": status,
                    "foreground_app": tree.get("foreground_package"),
                    "viewport": tree.get("viewport"), "session_id": client.session_id}
        except BridgeError as error:
            return {"ok": False, "ready": False, "error": error.as_dict(),
                    "next_action": "Check bridge status and forwarding before continuing; do not replay actions."}
        finally:
            client.close()
