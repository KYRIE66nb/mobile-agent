#!/usr/bin/env python3
"""Dependency-free local MCP stdio entrypoint for Android Use.

Port of iphone-use's server entrypoint: same JSON-RPC plumbing, tool surface and
error semantics, with ADB + the on-device bridge replacing WDA/Xcode.
"""
import argparse
import base64
import collections
import json
import math
import os
import queue
import re
import statistics
import sys
import threading
import time
from pathlib import Path

try:
    import fcntl
except ImportError:  # Windows fallback: no inter-process lock
    fcntl = None

from adb_client import LOCAL_PORT, DEVICE_PORT, BridgeClient, BridgeError
from adb_controller import AndroidController, CALL_BUDGET, DEFAULT_MAX_NODES
from adb_setup import SetupManager
from adb_apps import AppCatalog
from adb_screen import ScreenHub

AUAError = BridgeError
VERSION = "0.1.0"
PROTOCOLS = ("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")
SETTLE_SECONDS = 0.35


def state_directory(given=None):
    """Private state outside any Git checkout: token, cache, artifacts, preview page."""
    if given:
        return Path(given)
    return Path.home() / ".android-use"


def obj(properties, required=()):
    return {"type": "object", "properties": properties, "required": list(required),
            "additionalProperties": False}


def string(description="", max_length=1000, enum=None):
    result = {"type": "string", "minLength": 1, "maxLength": max_length}
    if description:
        result["description"] = description
    if enum:
        result["enum"] = enum
    return result


def num(low, high, kind="number"):
    return {"type": kind, "minimum": low, "maximum": high}


BOOL = {"type": "boolean"}
SEL = obj({
    "label": string("Exact text or content description copied from fresh nodes.", max_length=1000),
    "label_contains": string("Substring of text/desc, for long or changing labels. Case-sensitive.", max_length=1000),
    "name": string("Exact view resource ID (the node's id field).", max_length=1000),
    "value": string("Exact current text; not the text to enter.", max_length=1000),
    "type": string("Exact short class name as nodes show it, e.g. Button, EditText, RecyclerView.", max_length=1000),
    "enabled": {"oneOf": [{"type": "boolean"}, {"type": "string", "enum": ["true", "false"]}],
                "description": "Optional exact enabled filter. This does not prove hittability."},
    "clickable": {"oneOf": [{"type": "boolean"}, {"type": "string", "enum": ["true", "false"]}],
                  "description": "Optional clickable filter."},
    "editable": {"oneOf": [{"type": "boolean"}, {"type": "string", "enum": ["true", "false"]}],
                 "description": "Optional editable filter; use it to find text fields."},
    "scrollable": {"oneOf": [{"type": "boolean"}, {"type": "string", "enum": ["true", "false"]}],
                   "description": "Optional scrollable filter."},
    "text_regex": string("Regex matched against the node's text.", max_length=500),
    "desc_regex": string("Regex matched against the node's content description.", max_length=500),
    "index": {**num(0, 199, "integer"),
              "description": "0-based position among matches in tree order, as listed by aua_find or an ambiguous_target error. Only to choose between several matches."}})
SEL["description"] = ("Exact label/name/value/type/flag fields or regexes. Matches nested at one place, or with only one on screen, "
                      "resolve to that element; several separate matches return candidates with tap points and an index.")
SEL["examples"] = [{"label": "返回", "type": "Button"}]
SEL["minProperties"] = 1
OBS = string("Post-action output, default none. Use tree/both when the next decision needs the resulting page; screenshot returns an image. This is next-step context, not proof of this action; expect/verify control verification.",
             enum=["none", "tree", "screenshot", "both"])
OBS["default"] = "none"
EXPECT = {"expect": SEL, "observe": OBS}
VERIFY = {"type": "boolean", "default": False,
          "description": "Opt in to this operation's result check. Default false executes once and defers checking to the next required observation or final key checkpoint. Never blindly replay an uncertain mutation."}
REGION = obj({k: num(0 if k in ("x", "y") else 1, 10000) for k in ("x", "y", "width", "height")},
             ("x", "y", "width", "height"))
REGION["description"] = "Scroll rectangle in screen pixels. Omit region for the central area, or use the actual list bounds."

SCHEMAS = {
    "observe": obj({"mode": string("Standalone observation output, default tree. Use mode here; observe is a post-action option on mutation tools.", enum=["tree", "screenshot", "both"]),
                    "include_invisible": BOOL,
                    "max_nodes": {**num(1, 500, "integer"), "default": DEFAULT_MAX_NODES}}),
    "find": obj({"selector": SEL, "limit": num(1, 30, "integer")}, ("selector",)),
    "tap": obj({"selector": SEL, "x": num(0, 10000), "y": num(0, 10000),
                "observation_id": string("Optional ID from this Runtime. Checks app/viewport context, not whole-page equality or age."),
                **EXPECT}),
    "long_press": obj({"x": num(0, 10000), "y": num(0, 10000),
                       "duration_ms": {**num(100, 5000, "integer"), "default": 800},
                       "observation_id": string("Optional ID from this Runtime."), "observe": OBS},
                      ("x", "y")),
    "swipe": obj({"direction": string("Finger movement; up usually reveals later rows. Default up.", enum=["up", "down", "left", "right"]),
                  "region": REGION,
                  "observation_id": string("Optional ID from this Runtime."),
                  "expect": SEL,
                  "verify": {**VERIFY, "description": "False: one gesture, no tree checks. True: check anchor movement once; failure returns a screenshot before another action. Numeric refresh is not progress."},
                  "max_attempts": {**num(1, 2, "integer"), "default": 1},
                  "observe": OBS}),
    "type_text": obj({"selector": SEL,
                      "text": string("Full text to enter. Required unless continue_token is given.", max_length=10000),
                      "allow_newlines": BOOL, "submit": BOOL, "replace": BOOL,
                      "verify": {**VERIFY, "description": "Default false enters the full text once without readback. True checks exact field text and stops before submit on mismatch."},
                      **EXPECT,
                      "continue_token": string("Token from a result with input_complete=false. Pass it alone to type the rest with the original options.", max_length=64)}),
    "press_button": obj({"name": string(enum=["home", "back", "recents", "volume_up", "volume_down",
                                             "enter", "escape", "delete", "tab",
                                             "dpad_up", "dpad_down", "dpad_left", "dpad_right"]),
                         "verify": VERIFY, **EXPECT}, ("name",)),
    "launch_app": obj({"package_name": string("Verified Android package name, e.g. com.tencent.mm.", max_length=200),
                       "verify": VERIFY, **EXPECT}, ("package_name",)),
    "wait": obj({"selector": SEL, "timeout_seconds": num(0, 20)}, ("selector",)),
    "scroll_find": obj({"selector": SEL,
                        "direction": string("Finger movement; up usually reveals later rows.", enum=["up", "down", "left", "right"]),
                        "max_swipes": {**num(0, 10, "integer"), "default": 1}}, ("selector",)),
    "collect_list": obj({"row_type": string("Optional short class name filter, e.g. LinearLayout, TextView."),
                         "max_pages": num(1, 10, "integer"), "end_selector": SEL}),
    "apps": obj({"query": string(max_length=100),
                 "source": string(enum=["auto", "catalog", "installed"]),
                 "limit": num(1, 30, "integer")}, ("query",)),
    "doctor": obj({}),
    "ready": obj({"screenshot": {"type": "boolean", "default": True,
                                 "description": "Also verify screenshot; false retains status/session/tree checks."}}),
    "metrics": obj({"reset": {"type": "boolean", "default": False,
                              "description": "Return the totals, then start a new measurement window."}}),
    "setup": obj({"action": string(enum=["discover", "configure", "status", "enable_accessibility",
                                        "pair", "start_bridge", "stop_bridge", "forward"]),
                  "serial": string("Device serial from aua_setup discover.", max_length=128),
                  "local_port": num(1024, 65535, "integer"),
                  "device_port": num(1024, 65535, "integer")}, ("action",)),
    "screen": obj({"action": string("open shows the live preview page; pause stops frame capture while the user authenticates; resume after they confirm.", enum=["open", "pause", "resume"])}),
    "screen_frame": obj({"after_seq": num(0, 9007199254740991, "integer")}),
}

BATCH_OPS = ["tap", "swipe", "type_text", "launch_app", "press_button", "wait", "observe", "scroll_find", "long_press"]
SCHEMAS["batch"] = obj({"steps": {"type": "array", "minItems": 1, "maxItems": 20,
                                 "items": {"oneOf": [obj({"op": {"type": "string", "const": op},
                                                          "args": SCHEMAS[op]}, ("op", "args"))
                                                   for op in BATCH_OPS]}}}, ("steps",))

DESCRIPTIONS = {
    "doctor": "Diagnose local adb, devices, app installation, accessibility, pairing, bridge and forwarding without changing the phone. Start here for setup.",
    "setup": "First phone use in this chat: call setup(status); if not ok, run its steps in order: enable_accessibility -> pair -> start_bridge -> forward, then aua_ready. discover lists devices, configure selects one. Android setup needs no signing or build.",
    "ready": "End-to-end readiness: forward up, bridge answers, accessibility connected, session + tree read. Only ready=true permits phone tasks. aua_unreachable requires setup, not task failure. Never replay phone actions.",
    "observe": "Fresh node tree and/or screenshot, with the pixel viewport and an observation_id. Nodes: type is the short class name; rect=[x,y,width,height] in pixels; omitted flags are defaults (enabled/visible true; clickable/editable/etc. false). A listed node is not proven hittable: fixed headers and overlays can cover it. Screenshots are scaled: image pixels x image.pixel_to_point = screen pixels.",
    "find": "Query selector fields against the current tree without returning it all. Returns matches in tree order with index, type, texts and rect; this tool's selector documents the fields every selector accepts.",
    "tap": "Tap the element a selector resolves to after on-screen checks, or tap point coordinates with optional observation_id. If the selector fails, the error returns a screenshot and tap points: tap by x/y instead of trying other selectors. Executes once optimistically; expect opts into a postcondition.",
    "long_press": "Long-press a screen point, e.g. for context menus and drag initiation. Coordinates in pixels; observation_id optional.",
    "swipe": "One gesture; default verify=false/observe=none skips checks. verify=true checks anchor movement once; failure returns a screenshot without another gesture. Inspect it before acting; no progress does not prove list completeness.",
    "type_text": "Enter the full intended Unicode text into an editable field; no short-text trial. Omit selector to type into the field that already has focus: tap the field by x/y, then type. Long text is sent in bounded pieces; input_complete=false returns a continue_token to pass alone next call. verify=true opts into exact readback before submit; expect opts into a page postcondition. Newlines need explicit intent; submit presses Enter, which the accessibility channel may not support — fall back to tapping the send button. Never replay uncertain input/submission.",
    "press_button": "System keys once: home/back/recents/volume/enter/escape/delete/tab/dpad. verify polls the foreground app for home/back/recents. Volume effects cannot be semantically verified.",
    "launch_app": "Launch once using a verified package name, optimistically by default. verify=true polls foreground up to five seconds; expect checks the intended page. Resolve package names with aua_apps first — do not guess.",
    "wait": "Bounded semantic presence polling for an expected target. Presence is a UI postcondition, not proof of business correctness.",
    "batch": "Up to 20 known steps in one model round trip. Stops on error, failed explicit check, unfinished long input (input_continues) or the per-call time budget (time_budget); continue from stopped_at without repeating completed steps.",
    "scroll_find": "Find a hittable target with at most one swipe per call. Ambiguity stops immediately; an unresolved post-scroll query returns a screenshot. Inspect the end, region and overlays before deciding whether to swipe again; do not blindly repeat or raise the budget.",
    "collect_list": "Collect/deduplicate accessibility rows over bounded pages. Returns evidence and explicit coverage limits; always requires reconciliation before declaring business completeness.",
    "apps": "Resolve a real package name by installed-device inventory or the bundled catalog of common Android apps. Query app name before launch instead of guessing. Catalog metadata does not prove installation; check installed_verified.",
    "metrics": "In-process totals without text, app data or images: bridge HTTP time and bytes, tool time, response bytes per tool, and the wait between each response and the next tool request (host, model and user time). reset=true starts a new window.",
    "screen": "Open the live screen preview page in a browser, pause frame capture while the user authenticates on the phone, resume after they confirm. Preview is display-only and does not prove control readiness.",
    "screen_frame": "Preview-page poll: latest MJPEG frame once its sequence exceeds after_seq. Never starts sessions or occupies the phone operation lock.",
}
READS = {"doctor", "observe", "find", "wait", "metrics", "apps", "screen", "screen_frame"}

TOOLS = [{"name": "aua_" + name, "title": "Aua " + name.replace("_", " "),
          "description": DESCRIPTIONS[name], "inputSchema": schema,
          "annotations": {"readOnlyHint": name in READS,
                          "destructiveHint": name not in READS,
                          "idempotentHint": name in READS,
                          "openWorldHint": True}}
         for name, schema in SCHEMAS.items()]


def validate(value, schema, path="arguments"):
    if "oneOf" in schema:
        if isinstance(value, dict) and "op" in value:
            chosen = [c for c in schema["oneOf"]
                      if c.get("properties", {}).get("op", {}).get("const") == value["op"]]
            if len(chosen) == 1:
                return validate(value, chosen[0], path)
        matches = 0
        for candidate in schema["oneOf"]:
            try:
                validate(value, candidate, path)
                matches += 1
            except AUAError:
                pass
        if matches != 1:
            raise AUAError("invalid_argument", f"{path} must match exactly one allowed operation schema.")
        return
    kind = schema.get("type")
    valid = {"object": lambda: isinstance(value, dict),
             "array": lambda: isinstance(value, list),
             "string": lambda: isinstance(value, str),
             "boolean": lambda: isinstance(value, bool),
             "number": lambda: not isinstance(value, bool) and isinstance(value, (int, float)) and math.isfinite(value),
             "integer": lambda: not isinstance(value, bool) and isinstance(value, int)}
    if kind and not valid[kind]():
        raise AUAError("invalid_argument", f"{path} must be {kind}.")
    if "const" in schema and value != schema["const"]:
        raise AUAError("invalid_argument", f"Invalid {path} operation.")
    if "enum" in schema and value not in schema["enum"]:
        raise AUAError("invalid_argument", f"Invalid {path} option.")
    if kind == "object":
        props = schema.get("properties", {})
        if schema.get("additionalProperties") is False and set(value) - set(props):
            raise AUAError("invalid_argument",
                           f"Unknown fields in {path}: {', '.join(sorted(set(value) - set(props)))}. Use only the declared fields.",
                           details={"action_executed": False, "argument_path": path,
                                    "unknown_fields": sorted(set(value) - set(props)),
                                    "allowed_fields": sorted(props),
                                    "recovery": {"next_step": "Correct these fields using the current tool schema, then call once; no device action was executed."}})
        if set(schema.get("required", [])) - set(value):
            raise AUAError("invalid_argument", f"Missing required fields in {path}.")
        if len(value) < schema.get("minProperties", 0):
            raise AUAError("invalid_argument", f"{path} cannot be empty.")
        for k, v in value.items():
            if k in props:
                validate(v, props[k], path + "." + k)
    if kind in ("number", "integer") and not schema.get("minimum", -math.inf) <= value <= schema.get("maximum", math.inf):
        raise AUAError("invalid_argument", f"{path} is out of range.")
    if kind == "string" and not schema.get("minLength", 0) <= len(value) <= schema.get("maxLength", 100000):
        raise AUAError("invalid_argument", f"{path} has invalid length.")
    if kind == "array":
        if not schema.get("minItems", 0) <= len(value) <= schema.get("maxItems", 100000):
            raise AUAError("invalid_argument", f"{path} has invalid number of items.")
        for i, v in enumerate(value):
            validate(v, schema["items"], f"{path}[{i}]")


def validate_semantics(name, args):
    for k in ("selector", "expect", "end_selector"):
        if k in args:
            AndroidController._validate_selector(args[k])
    if name == "tap":
        semantic = "selector" in args
        coords = all(k in args for k in ("x", "y"))
        if semantic == coords or (semantic and any(k in args for k in ("x", "y"))):
            raise AUAError("invalid_argument", "tap requires either selector or x/y; observation_id is optional.")
    if name == "type_text":
        if "continue_token" in args:
            if len(args) != 1:
                raise AUAError("invalid_argument",
                               "continue_token resumes the earlier call with its own options; pass it alone.",
                               details={"action_executed": False})
            return
        if "text" not in args:
            raise AUAError("invalid_argument", "type_text needs text, or continue_token alone.",
                           details={"action_executed": False})
        if any(ord(c) < 32 and c not in ("\n", "\r") or ord(c) == 127 for c in args["text"]):
            raise AUAError("invalid_argument", "Control characters are not allowed in text.")
        if any(c in args["text"] for c in ("\n", "\r")) and not args.get("allow_newlines", False):
            raise AUAError("newline_requires_intent", "Line breaks require explicit multiline intent.")
    if name == "launch_app" and not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+", args["package_name"]):
        raise AUAError("invalid_argument", "Use the app's verified package name.")
    if name == "batch":
        for step in args["steps"]:
            validate_semantics(step["op"], step["args"])


def setting(name, default, low, high):
    try:
        value = float(os.environ.get(name, ""))
    except ValueError:
        return default
    return value if math.isfinite(value) and low <= value <= high else default


class Runtime:
    def __init__(self, state_dir=None):
        root = state_directory(state_dir)
        self.state_dir = Path(root).expanduser().resolve()
        if any((ancestor / ".git").exists() for ancestor in (self.state_dir, *self.state_dir.parents)):
            raise ValueError("Android Use state must stay outside Git checkouts.")
        self.state_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.state_dir.chmod(0o700)
        self.setup_manager = SetupManager(self.state_dir)
        self.client = self.setup_manager.client()
        self.phone = AndroidController(self.client, self.state_dir)
        self.apps = AppCatalog(self.state_dir, self.client, serial_getter=self.setup_manager.serial)
        self.screen = ScreenHub(token=self.client.token or "",
                                port=self.client.port, device_port=self.client.device_port)
        self.phone.settle_seconds = setting("AUA_SETTLE_SECONDS", SETTLE_SECONDS, 0, 2)
        self.phone.call_budget = setting("AUA_CALL_BUDGET_SECONDS", CALL_BUDGET, 5, 240)
        self.responses = collections.deque(maxlen=500)
        self._replied_at = None

    def _reload_client(self):
        """Setup may have changed serial/token/ports; rebuild bound components."""
        self.client = self.setup_manager.client()
        self.phone.client = self.client
        self.apps.client = self.client
        self.screen = ScreenHub(token=self.client.token or "",
                                port=self.client.port, device_port=self.client.device_port)

    def _dispatch(self, name, args):
        if not isinstance(name, str) or not name.startswith("aua_") or name[4:] not in SCHEMAS:
            raise AUAError("unknown_tool", "Unknown AUA tool.")
        validate(args, SCHEMAS[name[4:]])
        validate_semantics(name[4:], args)
        op = name[4:]
        if op == "screen_frame":
            return self.screen.frame(**args) or {"seq": 0, "timeout": True}
        if op == "screen":
            return self._screen(**args)
        lock_path = self.state_dir / "operation.lock"
        lock = lock_path.open("a")
        try:
            lock_path.chmod(0o600)
            if fcntl:
                try:
                    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    raise AUAError("device_busy",
                                   "Another Android Use operation is running. Wait for it to finish before continuing; no action was executed.")
            return self._call(name, args)
        finally:
            if fcntl:
                fcntl.flock(lock, fcntl.LOCK_UN)
            lock.close()

    def _screen(self, action="open"):
        if action == "pause":
            self.screen.stop()
            try:
                self.client.request("POST", "/screen/pause", {})
            except AUAError:
                pass
            return {"ok": True, "paused": True}
        if action == "resume":
            try:
                self.client.request("POST", "/screen/resume", {})
            except AUAError:
                pass
            self.screen.start()
            return {"ok": True, "paused": False}
        # open: write the preview page next to the state, then open the live stream URL.
        self.screen.start()
        page = self.state_dir / "phone_preview.html"
        try:
            template = (Path(__file__).resolve().parent / "phone_preview.html").read_text()
            page.write_text(template.replace("__AUA_SCREEN_URL__", self.screen.url())
                                    .replace("__AUA_TOKEN__", self.client.token or "")
                                    .replace("__AUA_BASE__",
                                             f"http://{self.screen.host}:{self.screen.port}"))
        except OSError:
            pass
        import webbrowser
        webbrowser.open(self.screen.url())
        return {"ok": True, "stream_url": self.screen.url(),
                "preview_page": str(page) if page.is_file() else None,
                "note": "The MJPEG stream is display-only; it does not prove control readiness."}

    def _call(self, name, args):
        op = name[4:]
        start = time.monotonic()
        error = None
        try:
            if op == "doctor":
                return self.setup_manager.status()
            if op == "apps":
                return self.apps.lookup(**args)
            if op == "setup":
                action = args["action"]
                if action == "discover":
                    return self.setup_manager.discover()
                if action == "configure":
                    result = self.setup_manager.configure(args.get("serial", ""))
                elif action == "status":
                    result = self.setup_manager.status()
                elif action == "enable_accessibility":
                    result = self.setup_manager.enable_accessibility()
                elif action == "pair":
                    result = self.setup_manager.pair()
                elif action == "start_bridge":
                    result = self.setup_manager.start_bridge()
                elif action == "stop_bridge":
                    result = self.setup_manager.stop_bridge()
                elif action == "forward":
                    result = self.setup_manager.forward()
                else:
                    raise AUAError("invalid_argument", f"Unknown setup action {action}.")
                if action in ("configure", "pair", "forward"):
                    self._reload_client()
                if result.get("ok") and action in ("stop_bridge", "configure"):
                    self.client.close()
                    self.client.session_id = None
                    self.phone.reset()
                return result
            if op == "ready":
                self.screen.start()
                ready = self.setup_manager.ready()
                if ready.get("ok"):
                    self.client.session_id = ready.get("session_id")
                    try:
                        ready["observation"] = self.phone.observe(
                            "both" if args.get("screenshot", True) else "tree")
                    except AUAError:
                        pass
                return ready
            if op == "metrics":
                result = self.metrics()
                if args.get("reset"):
                    self.client.clear_metrics()
                    self.phone.tool_records.clear()
                    self.responses.clear()
                    self._replied_at = None
                return result
            return getattr(self.phone, op)(**args)
        except AUAError as exc:
            error = exc.code
            if op in READS:
                exc.details.setdefault("action_executed", False)
            if exc.code in ("aua_unreachable", "action_uncertain"):
                exc.details.update(category="channel_runtime",
                                   recovery={"tool": "aua_ready", "arguments": {"screenshot": False},
                                             "replay_action": False})
            raise
        except (ValueError, TypeError) as exc:
            error = "invalid_argument"
            raise AUAError(error, str(exc)) from exc
        finally:
            self.phone.tool_records.append(
                {"tool": name, "seconds": round(time.monotonic() - start, 4), "error": error})

    def metrics(self):
        records = list(self.phone.tool_records)
        responses = list(self.responses)

        def grouped(items, fields):
            groups = {}
            for item in items:
                entry = groups.setdefault(item["tool"], {"count": 0, **{f: 0 for f in fields}})
                entry["count"] += 1
                for f in fields:
                    entry[f] = round(entry[f] + (item[f] or 0), 4)
            return groups

        waits = [r["wait_seconds"] for r in responses if r["wait_seconds"] is not None]
        return {**self.client.metrics(),
                "tools": {"count": len(records),
                          "seconds": round(sum(r["seconds"] for r in records), 4),
                          "errors": sum(bool(r["error"]) for r in records),
                          "by_tool": grouped([{**r, "errors": int(bool(r["error"]))} for r in records],
                                             ("seconds", "errors"))},
                "responses": {"count": len(responses),
                              "text_bytes": sum(r["text_bytes"] for r in responses),
                              "image_bytes": sum(r["image_bytes"] for r in responses),
                              "by_tool": grouped(responses, ("text_bytes", "image_bytes"))},
                "rounds": {"count": len(responses), "waits": len(waits),
                           "wait_seconds": round(sum(waits), 3),
                           "median_wait_seconds": round(statistics.median(waits), 3) if waits else None,
                           "max_wait_seconds": round(max(waits), 3) if waits else None,
                           "scope": "Time from one tool response to the next tool request: host, model and user time together."},
                "latency_scope": "HTTP, tool time and response sizes are measured here. The wait between calls is observed, not controlled."}

    def note_response(self, name, result, arrived):
        if name == "aua_screen_frame":
            return
        content = result.get("content", [])
        self.responses.append({
            "tool": name if isinstance(name, str) else "invalid",
            "text_bytes": sum(len(i["text"].encode()) for i in content if i.get("type") == "text"),
            "image_bytes": sum(len(i["data"]) for i in content if i.get("type") == "image"),
            "wait_seconds": None if self._replied_at is None else max(0.0, arrived - self._replied_at)})

    def replied(self):
        self._replied_at = time.monotonic()

    def close(self):
        try:
            self.screen.stop()
            self.client.close()
        except Exception:
            pass


def result_content(data):
    """One compact JSON text block, plus the screenshot when the result carries one."""
    content = [{"type": "text",
                "text": json.dumps(data, ensure_ascii=False, allow_nan=False, separators=(",", ":"))}]
    image = data.get("image") or data.get("observation", {}).get("image")
    if not image and isinstance(data.get("error"), dict):
        image = data["error"].get("observation", {}).get("image")
    if not image and data.get("steps"):
        last = data["steps"][-1].get("result") or {}
        image = last.get("image") or last.get("observation", {}).get("image")
    if image and image.get("base64"):
        content.append({"type": "image", "data": image["base64"],
                        "mimeType": image.get("mimeType", "image/jpeg")})
    return {"content": content, "isError": "error" in data}


def tool_result(runtime, params):
    name = params.get("name")
    try:
        data = runtime._dispatch(name, params.get("arguments", {}))
        if name == "aua_screen_frame":
            return {"content": [], "structuredContent": data, "isError": False}
        return result_content(data)
    except AUAError as exc:
        return result_content({"error": exc.as_dict()})
    except Exception:
        print("android-use tool failure: " + sys.exc_info()[0].__name__, file=sys.stderr)
        return result_content({"error": {"code": "internal_error",
                                         "message": "Local tool failed; inspect aua_setup status or local stderr.",
                                         "uncertain": True}})


INSTRUCTIONS = (
    "AUA means Android Use Agent; all Android Use tools use the aua_ prefix. "
    "First phone use in this chat: aua_setup(action=status); if not ok, run the listed steps (enable_accessibility, pair, start_bridge, forward), then aua_ready; only ready=true permits phone tasks, then reuse READY's observation and the healthy channel. "
    "If READY fails with aua_unreachable, continue initialization rather than end the task: aua_setup status, run the missing steps, then READY again. "
    "Results are one compact JSON text. Tree nodes give the short class type and rect=[x,y,width,height] in screen pixels; omitted flags are defaults. A listed node is not proven hittable: fixed headers and overlays can cover it. "
    "A screenshot arrives as an image in the same result: image pixels x image.pixel_to_point = screen pixels. Standalone observation uses mode; mutation output uses observe. "
    "Selectors copy label/name/value/type from fresh nodes; label_contains covers long or changing labels; text_regex/desc_regex replace the old predicate. Matches nested at one place resolve by themselves. "
    "Screenshot inspection is the fallback for abnormal UI state: selector/focus failure, unresolved scroll search, no scroll progress, input mismatch or a failed page expectation. Inspect the attached screenshot FIRST before any further mutation; if none is attached take aua_observe(mode=screenshot). For input, tap the visible editable field then type_text with text and no selector. Do not try other selector spellings first. Never blindly replay uncertain actions. Resolve unknown package names with aua_apps. "
    "Execute routine actions optimistically: observe=none and verify=false are defaults; verified=false/verification_deferred=true is normal. Chain known steps in batch. Send long text whole: when type_text returns input_complete=false, call it again with only continue_token; a batch stopped by input_continues or time_budget continues from stopped_at. "
    "Verify final critical results before reporting completion. Retry or replan only after observing a definite failure; never replay uncertain input/submission or an already executed multi-step operation wholesale. "
    "For passwords or payment screens call aua_screen(action=pause), let the user act on the phone, then aua_screen(action=resume). "
    "The MJPEG preview (aua_screen open) is display-only; its frames never substitute for a model observation or final verification."
)


def serve(runtime):
    """Read requests on this thread; run phone tools in order on one worker."""
    writing = threading.Lock()

    def send(response):
        line = json.dumps(response, ensure_ascii=False, allow_nan=False)
        with writing:
            print(line, flush=True)

    jobs = queue.Queue()

    def work():
        while True:
            job = jobs.get()
            if job is None:
                return
            ident, params, arrived = job
            result = tool_result(runtime, params)
            runtime.note_response(params.get("name"), result, arrived)
            send({"jsonrpc": "2.0", "id": ident, "result": result})
            runtime.replied()

    worker = threading.Thread(target=work, name="aua-tools", daemon=True)
    worker.start()
    try:
        for line in sys.stdin:
            request = None
            try:
                if len(line) > 1024 * 1024:
                    raise ValueError("Request exceeds 1 MiB")
                request = json.loads(line, parse_constant=lambda x: (_ for _ in ()).throw(ValueError(x)))
                if not isinstance(request, dict) or request.get("jsonrpc") != "2.0" or not isinstance(request.get("method"), str):
                    raise ValueError("Invalid request")
                if "id" not in request:
                    continue
                ident = request["id"]
                if isinstance(ident, bool) or not isinstance(ident, (str, int)):
                    raise ValueError("Invalid identifier")
                params = request.get("params", {})
                if not isinstance(params, dict):
                    raise AUAError("invalid_argument", "params must be an object.")
                method = request["method"]
                if method == "initialize":
                    offered = params.get("protocolVersion")
                    result = {"protocolVersion": offered if offered in PROTOCOLS else PROTOCOLS[0],
                              "capabilities": {"tools": {"listChanged": False}},
                              "serverInfo": {"name": "android-use", "version": VERSION},
                              "instructions": INSTRUCTIONS}
                elif method == "ping":
                    result = {}
                elif method == "tools/list":
                    result = {"tools": TOOLS}
                elif method == "tools/call":
                    arrived = time.monotonic()
                    if params.get("name") not in ("aua_screen_frame", "aua_screen"):
                        jobs.put((ident, params, arrived))
                        continue
                    result = tool_result(runtime, params)
                else:
                    send({"jsonrpc": "2.0", "id": ident,
                          "error": {"code": -32601, "message": "Method not found"}})
                    continue
                response = {"jsonrpc": "2.0", "id": ident, "result": result}
            except AUAError as exc:
                response = {"jsonrpc": "2.0",
                            "id": request.get("id") if isinstance(request, dict) else None,
                            "error": {"code": -32602, "message": str(exc)}}
            except (ValueError, TypeError, KeyError):
                response = {"jsonrpc": "2.0",
                            "id": request.get("id") if isinstance(request, dict) else None,
                            "error": {"code": -32700 if request is None else -32600,
                                      "message": "Parse error" if request is None else "Invalid request"}}
            send(response)
    finally:
        jobs.put(None)
        worker.join()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--state-dir")
    parser.add_argument("--doctor", action="store_true")
    parser.add_argument("--ready", action="store_true")
    args = parser.parse_args()
    runtime = Runtime(args.state_dir)
    try:
        if args.doctor or args.ready:
            try:
                data = runtime._dispatch("aua_doctor" if args.doctor else "aua_ready", {})
            except AUAError as exc:
                data = {"error": exc.as_dict()}
            print(json.dumps(data, ensure_ascii=False))
            return 1 if "error" in data or (args.ready and data.get("ready") is not True and data.get("ok") is not True) else 0
        serve(runtime)
        return 0
    finally:
        runtime.close()


if __name__ == "__main__":
    sys.exit(main())
