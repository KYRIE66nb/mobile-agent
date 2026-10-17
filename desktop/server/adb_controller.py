"""Desktop-side phone controller for the on-device accessibility bridge.

Port of iphone-use's wda_controller semantics to Android: selectors resolve over the
bridge's node tree (matched locally), mutations go through /action with the freshest
observation_id, abnormal UI states hand a screenshot back to the model, and uncertain
outcomes are never replayed.
"""
import base64
import collections
import datetime as dt
import json
import math
import re
import time
import uuid
from pathlib import Path

from adb_client import BridgeError
from adb_text import split_text, request_timeout, utf16_length

SELECTOR_FIELDS = {"label", "label_contains", "name", "value", "type", "enabled",
                   "clickable", "editable", "scrollable", "text_regex", "desc_regex", "index"}
VISUAL_FAILURES = {"no_such_element", "ambiguous_target", "offscreen_target", "occluded_target",
                   "search_exhausted", "no_scroll_progress", "scroll_context_changed",
                   "postcondition_failed", "input_mismatch", "no_focused_field", "not_editable"}
SELECTOR_FAILURES = {"no_such_element", "ambiguous_target", "offscreen_target", "occluded_target",
                     "search_exhausted", "not_editable", "no_focused_field"}
DEFAULT_MAX_NODES = 200
CANDIDATE_LIMIT = 8
VIEWPORT_TTL = 5.0
INPUT_TTL = 120
INPUT_READBACK_TIMEOUT = 1.0
INPUT_READBACK_INTERVAL = 0.05
INPUT_GEOMETRY_TIMEOUT = 1.0
INPUT_GEOMETRY_INTERVAL = 0.1
TYPING_PIECE = 500          # gateway ACTION_SET_TEXT accepts <=500 UTF-16 units per piece
FIELD_TEXT_LIMIT = 2000     # gateway total field cap after appends (UTF-16 units)
CALL_BUDGET = 45            # seconds a single tool call may use before handing work back
SETTLE_SECONDS = 0.35
LAUNCHER_PACKAGES = {"com.android.launcher", "com.android.launcher2", "com.android.launcher3",
                     "com.google.android.apps.nexuslauncher", "com.miui.home", "com.huawei.android.launcher",
                     "com.oppo.launcher", "com.bbk.launcher2", "com.samsung.desktopsystemui",
                     "com.sec.android.app.launcher", "com.tencent.mm", "com.microsoft.launcher"}


def fail(code, message, **details):
    raise BridgeError(code, message, details=details)


def stale(message, reason, scope="page", **details):
    fail("stale_observation", message, action_executed=False, reason=reason, freshness_scope=scope,
         recovery={"next_tool": "aua_observe", "next_arguments": {"mode": "both"},
                   "same_observation_retry": False, "replay_action": False,
                   "next_step": "Inspect the current app and viewport before reusing coordinates. An observation ID is optional; changing text, numbers or screenshot pixels does not invalidate its app/viewport context."},
         **details)


def finite(value, name, low=0, high=10000):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not low <= value <= high:
        fail("invalid_argument", f"{name} must be a finite number in [{low}, {high}].")
    return value


def integer(value, name, low, high):
    finite(value, name, low, high)
    if not isinstance(value, int):
        fail("invalid_argument", f"{name} must be an integer.")
    return value


def contains(outer, inner, slack=1):
    return (outer["x"] - slack <= inner["x"] and outer["y"] - slack <= inner["y"]
            and inner["x"] + inner["width"] <= outer["x"] + outer["width"] + slack
            and inner["y"] + inner["height"] <= outer["y"] + outer["height"] + slack)


def compact_node(node):
    """Model-facing node: short type, each distinct text once, integer [x,y,width,height]."""
    result = {}
    if node.get("type"):
        result["type"] = node["type"]
    text, desc, value = node.get("text"), node.get("desc"), node.get("value")
    if text:
        result["label"] = text
    if desc and desc != text:
        result["desc"] = desc
    if value and value != text:
        result["value"] = value
    if node.get("id"):
        result["name"] = node["id"]
    for key, out_key in (("enabled", "enabled"), ("visible", "visible"), ("editable", "editable"),
                         ("focused", "focused"), ("clickable", "clickable"), ("scrollable", "scrollable"),
                         ("checked", "checked"), ("selected", "selected"), ("long_clickable", "long_clickable")):
        if key in node and node[key] not in (None, ""):
            result[out_key] = node[key]
    if node.get("rect"):
        result["rect"] = [round(v) for v in node["rect"]]
    if node.get("tap"):
        result["tap"] = node["tap"]
    return result


class AndroidController:
    def __init__(self, client, state_dir):
        self.client, self.state_dir = client, Path(state_dir)
        self.snapshots = collections.OrderedDict()
        self.tool_records = collections.deque(maxlen=500)
        self.accepted_actions = 0
        self.pending_input = None
        self.last_observation_id = None
        self.last_app = None
        self.call_budget = CALL_BUDGET
        self._deadline = None
        self._last_raw_observations = collections.OrderedDict()

    # ------------------------------------------------------------------ plumbing

    def reset(self):
        self.snapshots.clear()
        self.pending_input = None
        self.last_observation_id = None
        self.last_app = None

    def external_action(self):
        self.accepted_actions += 1
        self.pending_input = None

    def _request(self, method, path, payload=None, timeout=None):
        return self.client.session(method, path, payload, timeout=timeout)

    def _action(self, action, observation_id=None, timeout=None):
        """One device mutation; counts accepted actions for uncertain-result semantics."""
        body = {"action": action}
        if observation_id:
            body["observation_id"] = observation_id
        result = self._request("POST", "/action", body, timeout=timeout)
        self.accepted_actions += 1
        return result

    def _inspect(self, max_nodes=DEFAULT_MAX_NODES):
        """Cheap fresh tree read (no screenshot). Updates the fresh observation context."""
        result = self._request("POST", "/inspect", {"mode": "tree", "max_nodes": max_nodes})
        self._remember(result)
        return result

    def _remember(self, observation):
        ident = observation.get("observation_id")
        if ident:
            self.last_observation_id = ident
            self.snapshots[ident] = {"time": time.monotonic(),
                                     "viewport": observation.get("viewport"),
                                     "app": observation.get("foreground_package"),
                                     "rotation": observation.get("rotation", 0),
                                     "session_id": observation.get("session_id")}
            while len(self.snapshots) > 32:
                self.snapshots.popitem(last=False)
            self._last_raw_observations[ident] = observation.get("nodes") or []
            while len(self._last_raw_observations) > 32:
                self._last_raw_observations.popitem(last=False)
        if observation.get("foreground_package"):
            self.last_app = observation["foreground_package"]
        return observation

    # ------------------------------------------------------------------ observation

    def capture(self, observation):
        """Decode the bridge JPEG into a bounded artifact; model sees path + exact scale."""
        image = observation.get("image")
        if not image or not image.get("base64"):
            return None
        try:
            data = base64.b64decode(image["base64"], validate=True)
        except (ValueError, TypeError):
            fail("invalid_response", "Bridge screenshot is not valid base64.")
        artifacts = self.state_dir / "artifacts"
        artifacts.mkdir(mode=0o700, parents=True, exist_ok=True)
        dest = artifacts / (dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
                            + "-" + uuid.uuid4().hex[:8] + ".jpg")
        dest.write_bytes(data)
        dest.chmod(0o600)
        for old in sorted(artifacts.glob("*.jpg"))[:-100]:
            old.unlink()
        viewport = observation.get("viewport") or {}
        width, height = image.get("width"), image.get("height")
        scale = [round(viewport["width"] / width, 4), round(viewport["height"] / height, 4)] \
            if width and height and viewport.get("width") else [1, 1]
        return {"path": str(dest), "mimeType": image.get("mime", "image/jpeg"),
                "width": width, "height": height, "pixel_to_point": scale,
                "base64": image["base64"]}

    def observation_from_state(self, raw, mode, max_nodes=DEFAULT_MAX_NODES):
        self._remember(raw)
        result = {"observation_id": raw.get("observation_id"),
                  "observed_at": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                  "app": raw.get("foreground_package"),
                  "viewport": {**(raw.get("viewport") or {}), "units": "pixels"}}
        nodes = raw.get("nodes") or []
        if mode in ("tree", "both"):
            result.update({"nodes": [compact_node(n) for n in nodes[:max_nodes]],
                           "total_nodes": raw.get("node_count", len(nodes)),
                           "truncated": bool(raw.get("truncated"))})
            if not nodes:
                result["warnings"] = ["Empty accessibility tree: inspect a screenshot for lock screen, permission dialogs or custom-rendered content before acting."]
        if mode in ("screenshot", "both") and raw.get("image"):
            result["image"] = self.capture(raw)
        return result

    def observe(self, mode="tree", include_invisible=False, max_nodes=DEFAULT_MAX_NODES, **_):
        if mode not in ("tree", "screenshot", "both"):
            fail("invalid_argument", "mode must be tree, screenshot or both.")
        integer(max_nodes, "max_nodes", 1, 500)
        if mode == "tree":
            raw = self._request("POST", "/inspect", {"mode": "tree", "max_nodes": max_nodes})
        else:
            raw = self._request("POST", "/observe", {"mode": mode, "max_nodes": max_nodes})
        return self.observation_from_state(raw, mode, max_nodes)

    def snapshot(self, observation_id):
        old = self.snapshots.get(observation_id)
        if not old:
            stale("This observation is unknown to the current Runtime. Observe again or use current viewport coordinates.",
                  "unknown_observation")
        return old

    def guard(self, observation_id=None):
        """Model-supplied observation ids only prove the page context they were taken in."""
        if observation_id is None:
            return
        old = self.snapshot(observation_id)
        fresh = self._inspect()
        if old["app"] is not None and fresh.get("foreground_package") != old["app"]:
            stale("The foreground app changed. Observe again.", "foreground_changed")
        if old["viewport"] and fresh.get("viewport") != old["viewport"]:
            stale("Orientation or viewport changed. Observe again before using the earlier coordinates.",
                  "viewport_changed")
        if old.get("rotation", 0) != fresh.get("rotation", 0):
            stale("The display rotation changed. Observe again before using the earlier coordinates.",
                  "rotation_changed")

    # ------------------------------------------------------------------ selectors

    @staticmethod
    def _validate_selector(selector):
        if not isinstance(selector, dict) or not selector or set(selector) - SELECTOR_FIELDS:
            fail("invalid_selector",
                 "Use label/label_contains/name/value/type/enabled/clickable/editable/scrollable/text_regex/desc_regex; index picks one match.")
        if "index" in selector:
            index = selector["index"]
            if isinstance(index, bool) or not isinstance(index, int) or not 0 <= index <= 199:
                fail("invalid_selector", "index must be an integer 0..199 choosing one match in tree order.")
        criteria = {k: v for k, v in selector.items() if k != "index"}
        if not criteria:
            fail("invalid_selector", "index only chooses among matches; add a selector field.")
        for key, value in criteria.items():
            if key in ("enabled", "clickable", "editable", "scrollable"):
                if not isinstance(value, bool) and value not in ("true", "false"):
                    fail("invalid_selector", f"{key} must be a boolean or the exact string true/false.")
            elif key in ("text_regex", "desc_regex"):
                if not isinstance(value, str) or not 1 <= len(value) <= 500:
                    fail("invalid_selector", f"{key} must be a 1..500 character regex.")
                try:
                    re.compile(value)
                except re.error as exc:
                    fail("invalid_selector", f"{key} is not a valid regex: {exc}.")
            elif not isinstance(value, str) or not 1 <= len(value) <= 1000 or "\x00" in value:
                fail("invalid_selector", "Selector fields must be nonempty strings up to 1000 characters without NUL.")
        return criteria

    def match(self, selector, nodes):
        criteria = self._validate_selector(selector)
        out = []

        def flag(node, name):
            value = criteria.get(name)
            if value is None:
                return True
            expected = value if isinstance(value, bool) else value == "true"
            # enabled/visible appear only when false; the others only when true.
            return bool(node.get(name, name in ("enabled", "visible"))) == expected

        for position, node in enumerate(nodes):
            if "label" in criteria and not (node.get("text") == criteria["label"]
                                          or node.get("desc") == criteria["label"]):
                continue
            if "label_contains" in criteria:
                needle = criteria["label_contains"]
                if not (needle in (node.get("text") or "") or needle in (node.get("desc") or "")):
                    continue
            if "name" in criteria and node.get("id") != criteria["name"]:
                continue
            if "value" in criteria and node.get("text") != criteria["value"]:
                continue
            if "type" in criteria and node.get("type") != criteria["type"]:
                continue
            if "text_regex" in criteria and not (node.get("text")
                                                 and re.search(criteria["text_regex"], node["text"])):
                continue
            if "desc_regex" in criteria and not (node.get("desc")
                                                 and re.search(criteria["desc_regex"], node["desc"])):
                continue
            if not (flag(node, "enabled") and flag(node, "clickable")
                    and flag(node, "editable") and flag(node, "scrollable")):
                continue
            if node.get("enabled") is False or node.get("visible") is False:
                continue
            out.append({"node": node, "index": position})
        index = selector.get("index")
        if index is not None:
            out = [out[index]] if index < len(out) else []
        return out

    @staticmethod
    def _on_screen(node, viewport):
        rect = node.get("rect")
        if not rect or not viewport:
            return False
        cx, cy = rect[0] + rect[2] / 2, rect[1] + rect[3] / 2
        return rect[2] > 0 and rect[3] > 0 and 0 <= cx < viewport["width"] and 0 <= cy < viewport["height"]

    def describe(self, match_entry, viewport):
        node = match_entry["node"]
        described = {"index": match_entry["index"], **compact_node(node)}
        if "tap" not in described and node.get("rect"):
            rect = node["rect"]
            described["tap"] = [round(rect[0] + rect[2] / 2), round(rect[1] + rect[3] / 2)]
        described["hittable"] = bool(node.get("enabled", True)) and self._on_screen(node, viewport)
        return described

    def choose(self, found, viewport, editable):
        """Reduce several matches to the one a tap can only mean, or report the candidates."""
        recovery = {"replay_action": False,
                    "next_step": "Tap the intended candidate's tap point with x/y, or repeat the call with selector.index. Nothing was sent to the phone."}
        candidates = [m for m in found if self._on_screen(m["node"], viewport)]
        if not candidates:
            fail("offscreen_target",
                 "Several elements match, but none is inside the viewport. Scroll the intended one into view and observe again.",
                 action_executed=False, matches=len(found),
                 candidates=[self.describe(m, viewport) for m in found[:CANDIDATE_LIMIT]],
                 viewport=viewport,
                 recovery={"next_tool": "aua_observe", "next_arguments": {"mode": "both"}, "replay_action": False})
        if editable:
            fields = [m for m in candidates if m["node"].get("editable")]
            if fields:
                candidates = fields
        if len(candidates) == 1:
            return candidates[0]
        listed = candidates[:CANDIDATE_LIMIT]
        ordered = sorted(listed, key=lambda m: m["node"]["rect"][2] * m["node"]["rect"][3])
        nested = all(contains(
            {"x": ordered[i + 1]["node"]["rect"][0], "y": ordered[i + 1]["node"]["rect"][1],
             "width": ordered[i + 1]["node"]["rect"][2], "height": ordered[i + 1]["node"]["rect"][3]},
            {"x": ordered[i]["node"]["rect"][0], "y": ordered[i]["node"]["rect"][1],
             "width": ordered[i]["node"]["rect"][2], "height": ordered[i]["node"]["rect"][3]})
            for i in range(len(ordered) - 1))
        if nested:
            # Nested matches share one touch point: prefer the innermost actionable node.
            for match in ordered:
                if match["node"].get("clickable") or match["node"].get("editable"):
                    return match
            return ordered[0]
        fail("ambiguous_target",
             "Several separate elements match. Tap the intended candidate's tap point with x/y, or choose it by selector.index.",
             action_executed=False, matches=len(found),
             candidates=[self.describe(m, viewport) for m in listed],
             candidates_truncated=len(listed) < len(candidates), recovery=recovery)

    def resolve(self, selector, editable=False, nodes=None, viewport=None):
        """One reachable node for the selector, with its ref and tap point."""
        if nodes is None or viewport is None:
            fresh = self._inspect()
            nodes, viewport = nodes or fresh.get("nodes") or [], viewport or fresh.get("viewport") or {}
        found = self.match(selector, nodes)
        if not found:
            fail("no_such_element",
                 "No element matches this selector. Locate the target on the screen and act by coordinates instead of trying other selector spellings.",
                 action_executed=False)
        match = found[0] if len(found) == 1 or selector.get("index") is not None else self.choose(found, viewport, editable)
        node = match["node"]
        rect = node.get("rect") or [0, 0, 0, 0]
        cx, cy = rect[0] + rect[2] / 2, rect[1] + rect[3] / 2
        if not self._on_screen(node, viewport):
            fail("offscreen_target",
                 "Target center is outside the viewport. Scroll the actual list into view, observe again and re-find the target; a stopped batch has not completed its failed step.",
                 action_executed=False, target_rect=rect, viewport=viewport,
                 recovery={"next_tool": "aua_observe", "next_arguments": {"mode": "both"}, "replay_action": False})
        if editable and not node.get("editable"):
            fail("not_editable",
                 "Select an editable text field (EditText). Ask the user to handle password fields.",
                 secure_field=bool(node.get("type") and "password" in node["type"].lower()))
        return match

    def focused_field(self, nodes):
        for node in nodes:
            if node.get("editable") and node.get("focused") and node.get("enabled", True):
                return node
        editables = [n for n in nodes if n.get("editable") and n.get("enabled", True)]
        if len(editables) == 1:
            return editables[0]
        fail("no_focused_field",
             "No text field has keyboard focus; no text was entered. Inspect the screenshot for a blocking popup or wrong target, then tap the visible field with aua_tap x/y. Do not repeat a failed point blindly.",
             action_executed=False)

    def find(self, selector, limit=10):
        integer(limit, "limit", 1, 30)
        fresh = self._inspect()
        found = self.match(selector, fresh.get("nodes") or [])
        viewport = fresh.get("viewport") or {}
        return {"matches": len(found),
                "elements": [self.describe(m, viewport) for m in found[:limit]],
                "truncated": len(found) > limit and selector.get("index") is None}

    # ------------------------------------------------------------------ actions

    def _fresh_context(self):
        fresh = self._inspect()
        return fresh.get("observation_id"), fresh.get("nodes") or [], fresh.get("viewport") or {}

    def _post_action(self, expect=None, observe="none", max_nodes=DEFAULT_MAX_NODES):
        result = {"action_executed": True, "action_complete": True, "verified": False,
                  "verification_deferred": True}
        if expect:
            result["postcondition"] = self.wait(expect)
            result.update(verified=True, verification_deferred=False)
        if observe != "none":
            if self.settle_seconds:
                time.sleep(self.settle_seconds)
            result["observation"] = self.observe(observe, max_nodes=max_nodes)
        return result

    settle_seconds = SETTLE_SECONDS

    def _wrap_error(self, error, typing=False):
        """Hand abnormal UI state back with a screenshot before the model chooses another action."""
        if error.code not in VISUAL_FAILURES or error.details.get("secure_field"):
            return error
        if error.details.get("recovery", {}).get("visual_check_required"):
            return error
        error.details.setdefault("action_executed", False)
        try:
            observed = error.details.get("observation")
            if observed is None:
                error.details["observation"] = self.observe("screenshot")
            elif "image" not in observed:
                error.details["observation"]["image"] = self.capture(
                    self._request("POST", "/observe", {"mode": "screenshot"}))
        except BridgeError as failure:
            error.details["observation_error"] = {"code": failure.code, "message": str(failure)}
        if error.code == "offscreen_target":
            step = ("Inspect the attached screenshot FIRST. The match lies outside the viewport; check the actual list, "
                    "direction and overlays before deciding whether to swipe toward it.")
        elif error.code in ("search_exhausted", "no_scroll_progress", "scroll_context_changed"):
            step = ("Inspect the attached screenshot FIRST for the last row/end marker, unchanged content, wrong region, "
                    "or popup. Decide whether another swipe is warranted; do not automatically repeat scrolling. "
                    "No progress alone does not prove an empty or complete list.")
        elif error.code in ("postcondition_failed", "input_mismatch"):
            step = ("Inspect the attached screenshot FIRST to establish the actual page, field text or submission result. "
                    "Continue only the missing work; do not blindly repeat the previous click, input or submission.")
        elif typing:
            step = ("Inspect the attached screenshot FIRST for a popup or wrong page. Tap the visible editable field "
                    "with aua_tap x/y, then call aua_type_text with text and no selector.")
        else:
            step = "Inspect the attached screenshot FIRST, then tap the visible target with aua_tap x/y."
        recovery = {**error.details.get("recovery", {}),
                    "use": "coordinates" if error.code in SELECTOR_FAILURES else "screenshot",
                    "visual_check_required": True, "replay_action": False,
                    "next_step": step + " Image pixels x image.pixel_to_point give screen pixels. Inspect the image content, not its base64 text."}
        error.details["recovery"] = recovery
        return error

    def _run(self, function, typing=False, **kwargs):
        before = self.accepted_actions
        try:
            return function(**kwargs)
        except BridgeError as error:
            if self.accepted_actions > before:
                error.details.update(action_executed=True, action_complete=False)
                error.details.setdefault(
                    "verification_required",
                    "At least one phone action was accepted before the error. Read actual state; do not automatically replay the operation.")
            raise self._wrap_error(error, typing=typing)

    def tap(self, selector=None, x=None, y=None, observation_id=None, expect=None, observe="none"):
        if observe not in ("none", "tree", "screenshot", "both"):
            fail("invalid_argument", "Invalid observation mode.")
        if expect:
            self._validate_selector(expect)
        if selector is not None:
            if x is not None or y is not None:
                fail("invalid_argument", "Choose selector or coordinates.")
            observation_id, nodes, viewport = self._fresh_context()
            try:
                match = self.resolve(selector, nodes=nodes, viewport=viewport)
            except BridgeError as error:
                raise self._wrap_error(error)
            node = match["node"]
            if node.get("clickable"):
                self._action({"kind": "node_action", "node_ref": node["ref"], "action": "click"},
                             observation_id)
            else:
                tap = node.get("tap") or [0, 0]
                self._action({"kind": "tap", "x": tap[0], "y": tap[1]}, observation_id)
            result = self._post_action(expect, observe)
            result["target"] = self.describe(match, viewport)
            return result
        finite(x, "x")
        finite(y, "y")
        self.guard(observation_id)
        fresh_id, nodes, viewport = self._fresh_context()
        if x >= viewport["width"] or y >= viewport["height"]:
            fail("invalid_argument", "Coordinates are outside the screen viewport.")
        self._action({"kind": "tap", "x": int(x), "y": int(y)}, fresh_id)
        return self._post_action(expect, observe)

    def long_press(self, x, y, duration_ms=800, observation_id=None, observe="none"):
        finite(x, "x"); finite(y, "y")
        integer(duration_ms, "duration_ms", 100, 5000)
        self.guard(observation_id)
        fresh_id, _, viewport = self._fresh_context()
        if x >= viewport["width"] or y >= viewport["height"]:
            fail("invalid_argument", "Coordinates are outside the screen viewport.")
        self._action({"kind": "long_press", "x": int(x), "y": int(y), "duration_ms": duration_ms}, fresh_id)
        return self._post_action(None, observe)

    def launch_app(self, package_name, expect=None, observe="none", verify=False):
        if not isinstance(package_name, str) or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+", package_name):
            fail("invalid_argument", "Use the app's verified package name.")
        if observe not in ("none", "tree", "screenshot", "both"):
            fail("invalid_argument", "Invalid observe mode.")
        if not isinstance(verify, bool):
            fail("invalid_argument", "verify must be boolean.")
        if expect:
            self._validate_selector(expect)
        self._action({"kind": "open_app", "package_name": package_name}, timeout=45)
        if not verify:
            result = self._post_action(expect, observe)
            result["foreground_verified"] = False
            return result
        deadline = time.monotonic() + 5
        app = None
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                fail("postcondition_failed",
                     "Activation was accepted, but the requested app did not become foreground within five seconds. Inspect loading, login or system prompts before continuing; do not replay activation automatically.",
                     requested_app=package_name, foreground_app=app, foreground_verified=False,
                     recovery={"next_tool": "aua_observe", "next_arguments": {"mode": "both"}, "replay_action": False})
            app = (self._inspect().get("foreground_package"))
            if app == package_name:
                break
            time.sleep(min(.15, max(0, deadline - time.monotonic())))
        result = self._post_action(expect, observe)
        result.update(verified=True, verification_deferred=False, foreground_verified=True,
                      verification_scope="Requested app is foreground; user task completion is separate.")
        return result

    def press_button(self, name, observe="none", verify=False, expect=None):
        supported = ("home", "back", "recents", "volume_up", "volume_down", "enter",
                     "escape", "delete", "tab", "dpad_up", "dpad_down", "dpad_left", "dpad_right")
        if name not in supported:
            fail("invalid_argument", f"Supported buttons: {', '.join(supported)}.")
        if observe not in ("none", "tree", "screenshot", "both"):
            fail("invalid_argument", "Invalid observe mode.")
        if not isinstance(verify, bool):
            fail("invalid_argument", "verify must be boolean.")
        if expect:
            self._validate_selector(expect)
        before_app = self.last_app if name in ("home", "back", "recents") else None
        self._action({"kind": "press_key", "key": name})
        if not verify or name not in ("home", "back", "recents"):
            result = self._post_action(expect, observe)
            result["foreground_verified"] = False
            return result
        deadline = time.monotonic() + 2.5
        app = before_app
        while time.monotonic() < deadline:
            app = self._inspect().get("foreground_package")
            if name == "home" and app and (app in LAUNCHER_PACKAGES or "launcher" in app or ".home" in app):
                break
            if name in ("back", "recents") and app != before_app:
                break
            time.sleep(0.15)
        result = self._post_action(expect, observe)
        result.update(verified=True, verification_deferred=False, foreground_verified=True,
                      foreground_app=app,
                      verification_scope="Navigation accepted; user task completion is separate.")
        return result

    def region(self, region, viewport):
        if region is None:
            return {"x": viewport["width"] * .15, "y": viewport["height"] * .25,
                    "width": viewport["width"] * .7, "height": viewport["height"] * .5}
        if not isinstance(region, dict) or set(region) != {"x", "y", "width", "height"}:
            fail("invalid_argument", "region must have x/y/width/height in screen pixels.")
        for k, v in region.items():
            finite(v, k, 0 if k in ("x", "y") else 1)
        if region["x"] + region["width"] > viewport["width"] or region["y"] + region["height"] > viewport["height"]:
            fail("invalid_argument", "Gesture region is outside viewport.")
        return region

    @staticmethod
    def _anchors(nodes, region):
        found = collections.defaultdict(list)
        for node in nodes:
            rect = node.get("rect")
            if not rect:
                continue
            if region:
                cx, cy = rect[0] + rect[2] / 2, rect[1] + rect[3] / 2
                if not (region["x"] <= cx <= region["x"] + region["width"]
                        and region["y"] <= cy <= region["y"] + region["height"]):
                    continue
            for field in ("text", "desc"):
                value = node.get(field)
                if isinstance(value, str) and value:
                    found[(node.get("type"), field, value)].append(rect)
        return {key: rects[0] for key, rects in found.items() if len(rects) == 1}

    def scroll_progress(self, before, after, region, direction):
        old = self._anchors(before, None)
        new = self._anchors(after, None)
        axis, cross = ("y", "x") if direction in ("up", "down") else ("x", "y")
        ai, ci = (1, 0) if axis == "y" else (0, 1)
        sign = -1 if direction in ("up", "left") else 1
        for key in old.keys() & new.keys():
            a, b = old[key], new[key]
            if region and not any(
                    region["x"] <= rect[0] + rect[2] / 2 <= region["x"] + region["width"]
                    and region["y"] <= rect[1] + rect[3] / 2 <= region["y"] + region["height"]
                    for rect in (a, b)):
                continue
            if ((b[ai] - a[ai]) * sign > 2 and abs(b[ci] - a[ci]) <= 3
                    and all(abs(b[k] - a[k]) <= 2 for k in (2, 3))):
                return True
        return False

    def swipe(self, direction="up", region=None, observation_id=None, expect=None,
              verify=False, max_attempts=1, observe="none", _baseline=None, _max_nodes=DEFAULT_MAX_NODES):
        if direction not in ("up", "down", "left", "right"):
            fail("invalid_argument", "Invalid direction.")
        if observe not in ("none", "tree", "screenshot", "both"):
            fail("invalid_argument", "Invalid observe mode.")
        integer(max_attempts, "max_attempts", 1, 2)
        if not isinstance(verify, bool):
            fail("invalid_argument", "verify must be boolean.")
        if expect:
            self._validate_selector(expect)
        self.guard(observation_id)
        fresh_id, before_nodes, viewport = self._fresh_context()
        if _baseline is not None:
            before_nodes, viewport = _baseline
        area = self.region(region, viewport)
        x = area["x"] + area["width"] / 2
        y = area["y"] + area["height"] / 2
        dx = area["width"] * .32
        dy = area["height"] * .32
        points = {"up": (x, y + dy, x, y - dy), "down": (x, y - dy, x, y + dy),
                  "left": (x + dx, y, x - dx, y), "right": (x - dx, y, x + dx, y)}[direction]
        self._action({"kind": "swipe", "x1": int(points[0]), "y1": int(points[1]),
                      "x2": int(points[2]), "y2": int(points[3]), "duration_ms": 300}, fresh_id)
        if not verify:
            result = self._post_action(expect, observe, max_nodes=_max_nodes)
            result.update(strategy="short_drag", attempts=1, progress_verified=False)
            return result
        time.sleep(self.settle_seconds)
        after = self._inspect()
        after_nodes, after_viewport = after.get("nodes") or [], after.get("viewport") or {}
        reasons = []
        if after_viewport != viewport:
            reasons.append("viewport_changed")
        if after.get("foreground_package") != self.snapshots.get(fresh_id, {}).get("app") and self.snapshots.get(fresh_id, {}).get("app"):
            reasons.append("foreground_changed")
        if reasons:
            details = {"action_executed": True, "verified": False, "changed": False, "attempts": 1,
                       "reasons": reasons, "recovery": {"same_gesture_retry": False, "replay_action": False}}
            if observe in ("tree", "both"):
                details["observation"] = self.observation_from_state(
                    {**after, "image": None}, "tree", _max_nodes)
            error = BridgeError("scroll_context_changed",
                                "A gesture was accepted, but the viewport or app context changed. Inspect the screenshot before choosing another action.",
                                details=details)
            raise self._wrap_error(error)
        if self.scroll_progress(before_nodes, after_nodes, area, direction):
            result = {"action_executed": True, "action_complete": True, "verified": True,
                      "verification_deferred": False, "changed": True, "progress_verified": True,
                      "verification_scope": "Stable accessibility anchors moved in the requested direction; business coverage is separate.",
                      "attempts": 1, "strategy": "short_drag"}
            if observe != "none":
                result["observation"] = self.observe(observe, max_nodes=_max_nodes)
            if expect:
                result["postcondition"] = self.wait(expect)
            return result
        details = {"action_executed": True, "verified": False, "changed": False, "attempts": 1, "region": area,
                   "recovery": {"next_tool": "aua_observe", "next_arguments": {"mode": "both"},
                                "next_step": "Inspect the current page and list entrance. If this is an overview, tap the actual list entry; if at the end, reconcile counts. Otherwise inspect a screenshot, or choose another stable region.",
                                "same_gesture_retry": False, "end_of_list_proven": False}}
        if observe in ("tree", "both"):
            details["observation"] = self.observation_from_state({**after, "image": None}, "tree", _max_nodes)
        error = BridgeError("no_scroll_progress",
                            "Gestures executed but stable accessibility anchors did not show movement in the requested direction. This does not prove an empty or complete list; inspect returned state before choosing the next action.",
                            details=details)
        raise self._wrap_error(error)

    # ------------------------------------------------------------------ typing

    def focused_field_path(self, selector, nodes, viewport):
        if selector is not None:
            return self.resolve(selector, editable=True, nodes=nodes, viewport=viewport)["node"]
        return self.focused_field(nodes)

    def _input_context_error(self, reason, message, **details):
        """Invalidate a continuation before it can send text to a new target."""
        self.pending_input = None
        fail("input_continuation_expired", message, action_executed=False, reason=reason,
             **details,
             recovery={"replay_action": False,
                       "next_step": "Read the field's actual text, then type only the missing remainder with replace=false."})

    def _stabilize_input_target(self, observation_id, nodes, viewport, node):
        """Wait briefly for the already selected ref's geometry to settle before planning input."""
        context = self.snapshots.get(observation_id) or {}
        plan = {"ref": node.get("ref"), "app": context.get("app"),
                "viewport": context.get("viewport", viewport),
                "rotation": context.get("rotation", 0),
                "focused": bool(node.get("focused")),
                "session_id": getattr(self.client, "session_id", None)}
        previous_rect = tuple(node.get("rect") or ())
        deadline = time.monotonic() + INPUT_GEOMETRY_TIMEOUT
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                self._input_context_error(
                    "target_changed",
                    "The original input field did not settle before typing began. Nothing was typed now.")
            time.sleep(min(INPUT_GEOMETRY_INTERVAL, remaining))
            fresh_id, fresh_nodes, fresh_viewport = self._fresh_context()
            fresh_context = self.snapshots.get(fresh_id) or {}
            if (fresh_context.get("session_id") is not None and plan.get("session_id") is not None
                    and fresh_context.get("session_id") != plan.get("session_id")):
                self._input_context_error(
                    "session_changed",
                    "The bridge session changed while the input field was settling. Nothing was typed now.")
            if fresh_context.get("app") != plan.get("app"):
                self._input_context_error(
                    "app_changed",
                    "The foreground app changed while the input field was settling. Nothing was typed now.")
            if fresh_context.get("viewport") != plan.get("viewport"):
                self._input_context_error(
                    "viewport_changed",
                    "The display viewport changed while the input field was settling. Nothing was typed now.")
            if fresh_context.get("rotation", 0) != plan.get("rotation", 0):
                self._input_context_error(
                    "rotation_changed",
                    "The display rotation changed while the input field was settling. Nothing was typed now.")
            matches = [candidate for candidate in fresh_nodes if candidate.get("ref") == plan["ref"]]
            if not matches:
                # Do not bind to a replacement ref while the keyboard animation is in flight.
                continue
            if len(matches) != 1:
                self._input_context_error(
                    "target_changed",
                    "The original input field is no longer unique while settling. Nothing was typed now.")
            current = matches[0]
            if not current.get("editable") or not current.get("enabled", True) or not current.get("visible", True):
                self._input_context_error(
                    "target_unusable",
                    "The original input field is no longer visible, enabled and editable. Nothing was typed now.")
            if bool(current.get("focused")) != plan["focused"]:
                self._input_context_error(
                    "focus_changed",
                    "The original input field's focus changed while settling. Nothing was typed now.")
            current_rect = tuple(current.get("rect") or ())
            if current_rect == previous_rect:
                return fresh_id, fresh_nodes, fresh_viewport, current
            previous_rect = current_rect

    def _validate_input_context(self, plan, observation_id, nodes, viewport,
                                expected_text=None, require_focus=False):
        """Check every continuation read against the original app, display and node."""
        if getattr(self.client, "session_id", None) != plan.get("session_id"):
            self._input_context_error("session_changed",
                                      "The bridge session changed while text input was in progress. Nothing was typed now.")
        context = self.snapshots.get(observation_id)
        if not context:
            self._input_context_error("observation_unavailable",
                                      "The original input context is unavailable. Nothing was typed now.")
        if (context.get("session_id") is not None and plan.get("session_id") is not None
                and context.get("session_id") != plan.get("session_id")):
            self._input_context_error("session_changed",
                                      "The bridge session changed while text input was in progress. Nothing was typed now.")
        if context.get("app") != plan.get("app"):
            self._input_context_error("app_changed",
                                      "The foreground app changed while text input was in progress. Nothing was typed now.")
        if context.get("viewport") != plan.get("viewport"):
            self._input_context_error("viewport_changed",
                                      "The display viewport changed while text input was in progress. Nothing was typed now.")
        if context.get("rotation", 0) != plan.get("rotation", 0):
            self._input_context_error("rotation_changed",
                                      "The display rotation changed while text input was in progress. Nothing was typed now.")

        matches = [node for node in nodes if node.get("ref") == plan.get("ref")]
        if len(matches) != 1:
            self._input_context_error("target_changed",
                                      "The original input field is no longer unique. Nothing was typed now.")
        node = matches[0]
        if not node.get("editable") or not node.get("enabled", True) or not node.get("visible", True):
            self._input_context_error("target_unusable",
                                      "The original input field is no longer visible, enabled and editable. Nothing was typed now.")
        planned_focus = plan.get("focused")
        if planned_focus is not None and bool(node.get("focused")) != planned_focus:
            self._input_context_error("focus_changed",
                                      "The original input field's focus changed while text input was in progress. Nothing was typed now.")
        if expected_text is not None and (node.get("text") or "") != expected_text:
            self._input_context_error("field_changed",
                                      "The original field no longer contains the text prefix already written. Nothing was typed now.")
        if require_focus and not node.get("focused"):
            self._input_context_error("focus_changed",
                                      "The original input field is no longer focused. Nothing was submitted.")
        return node

    def _wait_for_input_text(self, plan, expected_text, require_focus=False, return_last=False):
        """Poll a read-only field snapshot while preserving the original input context."""
        deadline = time.monotonic() + INPUT_READBACK_TIMEOUT
        last = None
        while True:
            fresh_id, nodes, viewport = self._fresh_context()
            current = self._validate_input_context(
                plan, fresh_id, nodes, viewport, require_focus=require_focus)
            last = (fresh_id, current, viewport)
            actual = current.get("text") or ""
            if actual == expected_text:
                return last
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                if return_last:
                    return last
                self._input_context_error(
                    "field_changed",
                    "The original field did not reflect the text just written before the readback deadline. Nothing was typed now.",
                    expected_length=len(expected_text), actual_length=len(actual),
                    expected_utf16_units=utf16_length(expected_text),
                    actual_utf16_units=utf16_length(actual),
                )
            time.sleep(min(INPUT_READBACK_INTERVAL, remaining))

    def type_text(self, selector=None, text=None, allow_newlines=False, submit=False, replace=True,
                  observe="none", verify=False, expect=None, continue_token=None):
        if continue_token is not None:
            if any(v is not None and v is not False for v in (selector, text, expect)) \
                    or allow_newlines or submit or not replace or verify or observe != "none":
                fail("invalid_argument",
                     "continue_token resumes the earlier call with the options given there; pass it alone.",
                     action_executed=False)
            return self.continue_input(continue_token)
        if text is None:
            fail("invalid_argument", "type_text needs text, or continue_token alone.", action_executed=False)
        if not isinstance(text, str) or not 1 <= len(text) <= 10000 or "\x00" in text:
            fail("invalid_argument", "text must have 1..10000 characters without NUL.")
        if any(0xD800 <= ord(character) <= 0xDFFF for character in text):
            fail("invalid_argument", "text must not contain isolated UTF-16 surrogate code points.",
                 action_executed=False)
        if ("\n" in text or "\r" in text) and not allow_newlines:
            fail("newline_requires_intent",
                 "This text contains line breaks. Use allow_newlines only for an observed multiline editor.")
        if observe not in ("none", "tree", "screenshot", "both"):
            fail("invalid_argument", "Invalid observe mode.")
        if not isinstance(verify, bool):
            fail("invalid_argument", "verify must be boolean.")
        if expect:
            self._validate_selector(expect)
        fresh_id, nodes, viewport = self._fresh_context()
        try:
            node = self.focused_field_path(selector, nodes, viewport)
        except BridgeError as error:
            raise self._wrap_error(error, typing=True)
        fresh_id, nodes, viewport, node = self._stabilize_input_target(
            fresh_id, nodes, viewport, node)
        initial_value = node.get("text") or ""
        expected = text if replace else initial_value + text
        if utf16_length(expected) > FIELD_TEXT_LIMIT:
            fail("invalid_argument",
                 f"The resulting field text cannot exceed {FIELD_TEXT_LIMIT} UTF-16 units.",
                 action_executed=False, utf16_units=utf16_length(expected),
                 limit=FIELD_TEXT_LIMIT)
        try:
            pieces = split_text(text, TYPING_PIECE)
        except ValueError as error:
            fail("invalid_argument", str(error), action_executed=False)
        context = self.snapshots.get(fresh_id) or {}
        plan = {"ref": node.get("ref"), "pieces": pieces,
                "typed": 0, "total": len(text),
                "typed_text": "", "total_utf16": utf16_length(text),
                "initial_value": initial_value, "expected": expected,
                "replace_first": replace,
                "verify": verify, "submit": submit, "expect": expect, "observe": observe,
                "app": context.get("app"), "viewport": context.get("viewport", viewport),
                "rotation": context.get("rotation", 0),
                "focused": bool(node.get("focused")),
                "session_id": getattr(self.client, "session_id", None),
                "first_context": (fresh_id, nodes, viewport)}
        return self.type_pieces(plan)

    def type_pieces(self, plan):
        """Send bounded input_text pieces; stop at the call budget and keep a continuation."""
        deadline = time.monotonic() + self.call_budget
        if self._deadline is not None:
            deadline = min(deadline, self._deadline)
        sent = 0
        first_context = plan.pop("first_context", None)
        while plan["pieces"]:
            if sent and time.monotonic() >= deadline:
                break
            piece = plan["pieces"][0]
            prefix = plan["initial_value"] + plan["typed_text"] if not plan["replace_first"] else plan["typed_text"]
            if plan["typed"]:
                fresh_id, node, viewport = self._wait_for_input_text(
                    plan, prefix, require_focus=plan.get("focused", False))
            elif first_context is not None:
                fresh_id, nodes, viewport = first_context
                first_context = None
                node = self._validate_input_context(plan, fresh_id, nodes, viewport)
            else:
                fresh_id, nodes, viewport = self._fresh_context()
                node = self._validate_input_context(plan, fresh_id, nodes, viewport)
            mode = "replace" if plan["replace_first"] and plan["typed"] == 0 else "append"
            try:
                self._action({"kind": "input_text", "text": piece,
                              "node_ref": plan["ref"], "mode": mode}, fresh_id,
                             timeout=request_timeout(utf16_length(piece), 30, getattr(self.client, "timeout", 15)))
            except BridgeError as error:
                error.details.update(characters_confirmed=plan["typed"], characters_total=plan["total"])
                error.details.update(utf16_units_confirmed=utf16_length(plan["typed_text"]),
                                    utf16_units_total=plan["total_utf16"])
                self.pending_input = None
                raise
            plan["pieces"].pop(0)
            plan["typed"] += len(piece)
            plan["typed_text"] += piece
            prefix = plan["initial_value"] + plan["typed_text"] if not plan["replace_first"] else plan["typed_text"]
            self._wait_for_input_text(plan, prefix, require_focus=plan.get("focused", False))
            sent += 1
        if plan["pieces"]:
            plan.update(token=uuid.uuid4().hex[:12], mark=self.accepted_actions,
                        created=time.monotonic())
            self.pending_input = plan
            return {"action_executed": True, "action_complete": False, "input_complete": False,
                    "verified": False, "verification_deferred": True,
                    "characters": plan["typed"], "remaining_characters": plan["total"] - plan["typed"],
                    "submitted": False, "continue_token": plan["token"],
                    "next_step": "Call aua_type_text with only continue_token to type the rest. Do not resend the text or act on the phone in between."}
        self.pending_input = None
        result = {"action_executed": True, "action_complete": True, "verified": False,
                  "verification_deferred": True, "characters": plan["total"], "submitted": False}
        if plan["verify"]:
            _, current, _ = self._wait_for_input_text(plan, plan["expected"],
                                                       require_focus=plan.get("focused", False),
                                                       return_last=True)
            actual = current.get("text") or ""
            expected = plan["expected"]
            if actual != expected:
                fail("input_mismatch",
                     "Typed text did not round-trip exactly. Do not submit or blindly type it again.",
                     expected_length=len(expected), actual_length=len(actual))
            result.update(verified=True, verification_deferred=False, exact_readback=True)
        if plan["submit"]:
            try:
                fresh_id, _, _ = self._wait_for_input_text(plan, plan["expected"], require_focus=True)
                self._action({"kind": "press_key", "key": "enter"}, fresh_id)
                result.update(submitted=True, submission_verified=False,
                              verification_required="Check the final submission result before claiming task completion; do not automatically repeat submission.")
            except BridgeError as error:
                if error.code == "unsupported":
                    error.details.update(
                        next_step="The accessibility channel cannot inject Enter. Tap the app's send/submit button with aua_tap instead; the typed text is already in the field.")
                raise
        if plan["expect"]:
            result["postcondition"] = self.wait(plan["expect"])
            result.update(verified=True, verification_deferred=False)
            if plan["submit"]:
                result.update(submission_verified=True,
                              verification_scope="Expected selector is present; verify the final business outcome before claiming task completion.")
        if plan["observe"] != "none":
            result["observation"] = self.observe(plan["observe"])
        return result

    def continue_input(self, token):
        plan = self.pending_input
        if (not plan or plan["token"] != token or self.accepted_actions != plan["mark"]
                or time.monotonic() - plan["created"] > INPUT_TTL):
            self.pending_input = None
            fail("input_continuation_expired",
                 "This continuation is no longer usable: it was already finished, it expired, or another phone action ran since. Nothing was typed now.",
                 action_executed=False,
                 recovery={"replay_action": False,
                           "next_step": "Read the field's actual text, then type only the missing remainder with replace=false."})
        return self.type_pieces(plan)

    # ------------------------------------------------------------------ waits & search

    def wait(self, selector, timeout_seconds=6):
        finite(timeout_seconds, "timeout_seconds", 0, 20)
        self._validate_selector(selector)
        deadline = time.monotonic() + timeout_seconds
        attempts = 0
        while True:
            attempts += 1
            fresh = self._inspect()
            found = self.match(selector, fresh.get("nodes") or [])
            if found:
                return {"verified": True, "matches": len(found), "polls": attempts,
                        "criterion": "selector exists; presence alone does not prove business success"}
            if time.monotonic() >= deadline:
                fail("postcondition_failed",
                     "Expected target did not appear within the wait budget.", polls=attempts)
            time.sleep(min(0.4, max(0, deadline - time.monotonic())))

    def scroll_find(self, selector, direction="up", max_swipes=1):
        self._validate_selector(selector)
        integer(max_swipes, "max_swipes", 0, 10)
        if direction not in ("up", "down", "left", "right"):
            fail("invalid_argument", "Invalid direction.")
        count = 0
        for count in range(min(max_swipes, 1) + 1):
            fresh = self._inspect()
            found = self.match(selector, fresh.get("nodes") or [])
            if found:
                try:
                    match = self.resolve(selector, nodes=fresh.get("nodes"),
                                         viewport=fresh.get("viewport"))
                    return {"verified": True, "swipes": count, "matches": len(found),
                            "target": self.describe(match, fresh.get("viewport"))}
                except BridgeError as exc:
                    exc.details.update(swipes=count, max_swipes=max_swipes)
                    if exc.code != "offscreen_target" or count or not max_swipes:
                        raise self._wrap_error(exc)
            if count == min(max_swipes, 1):
                break
            self.swipe(direction, verify=False, observe="none")
        error = BridgeError("search_exhausted",
                            "No hittable match after this search step. Inspect the screenshot before deciding whether to scroll again; do not automatically repeat the search or increase max_swipes.",
                            details={"swipes": count, "max_swipes": max_swipes,
                                     "stop_reason": "visual_check_required",
                                     "action_executed": count > 0})
        raise self._wrap_error(error)

    def collect_list(self, row_type=None, max_pages=6, end_selector=None):
        integer(max_pages, "max_pages", 1, 10)
        if end_selector:
            self._validate_selector(end_selector)
        if row_type is not None and (not isinstance(row_type, str)
                                     or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*", row_type)):
            fail("invalid_argument", "Invalid row_type (short class name such as LinearLayout or TextView).")
        rows, pages, seen_pages, reason, end = {}, [], set(), "page_budget", False
        observed = self.observe(mode="tree", max_nodes=500)
        raw_id = observed["observation_id"]
        raw_nodes = self._raw_nodes(raw_id)
        for index in range(max_pages):
            current = [n for n in raw_nodes
                       if row_type is None or n.get("type") == row_type]
            for n in current:
                key = json.dumps({k: n[k] for k in ("type", "text", "desc") if k in n},
                                 ensure_ascii=False, sort_keys=True)
                rows.setdefault(key, n)
            pages.append({"page": index + 1, "rows_seen": len(current),
                          "truncated": observed.get("truncated", False)})
            if end_selector:
                found = self.match(end_selector, raw_nodes)
                if found:
                    try:
                        self.resolve(end_selector, nodes=raw_nodes,
                                     viewport=observed.get("viewport"))
                        end, reason = True, "explicit_end_marker"
                        break
                    except BridgeError as exc:
                        if exc.code not in ("offscreen_target", "occluded_target", "ambiguous_target"):
                            raise
            page_key = json.dumps([{k: n[k] for k in ("type", "text", "desc", "rect") if k in n}
                                   for n in current], ensure_ascii=False, sort_keys=True)
            if page_key in seen_pages:
                reason = "no_progress"
                break
            seen_pages.add(page_key)
            if index == max_pages - 1:
                break
            observed = self.swipe("up", verify=False, observe="tree",
                                  _baseline=(raw_nodes, observed["viewport"]),
                                  _max_nodes=500)["observation"]
            raw_nodes = self._raw_nodes(observed["observation_id"])
        result = {"rows": [compact_node(r) for r in rows.values()], "pages": pages,
                  "stop_reason": reason, "end_marker_seen": end, "complete": False,
                  "coverage_verified": end and not any(p["truncated"] for p in pages),
                  "limitations": ["Rows deduplicate by identical type/text/desc; identical rows may collapse.",
                                  "Only exposed accessibility text is collected. Reconcile expected counts, screenshot-only fields, totals, currencies and dates before claiming business completeness."]}
        if reason == "no_progress":
            error = BridgeError("no_scroll_progress",
                                "Repeated collection page; inspect the screenshot before continuing.",
                                details={"action_executed": index > 0,
                                         "recovery": {"same_gesture_retry": False, "end_of_list_proven": False}})
            error = self._wrap_error(error)
            result.update({k: v for k, v in error.details.items()
                           if k in ("observation", "observation_error", "recovery")})
        return result

    def _raw_nodes(self, observation_id):
        """Raw bridge nodes for an observation id this controller issued."""
        return self._last_raw_observations.get(observation_id, [])

    def batch(self, steps):
        if not isinstance(steps, list) or not 1 <= len(steps) <= 20:
            fail("invalid_argument", "steps must contain 1..20 operations.")
        allowed = {"tap": self.tap, "swipe": self.swipe, "type_text": self.type_text,
                   "launch_app": self.launch_app, "press_button": self.press_button,
                   "wait": self.wait, "observe": self.observe, "scroll_find": self.scroll_find,
                   "long_press": self.long_press}
        for step in steps:
            if (not isinstance(step, dict) or set(step) - {"op", "args"}
                    or step.get("op") not in allowed or not isinstance(step.get("args", {}), dict)):
                fail("invalid_argument", "Each batch step needs a supported op and args object.")
        results = []
        self._deadline = time.monotonic() + self.call_budget
        try:
            for index, step in enumerate(steps):
                if index and self._deadline - time.monotonic() < 6:
                    return self._batch_result(steps, results, index, "time_budget",
                                              "The batch stopped inside its time budget; continue from stopped_at.")
                try:
                    outcome = allowed[step["op"]](**step.get("args", {}))
                except BridgeError as error:
                    if error.details.get("input_continues") or error.details.get("continue_token"):
                        return self._batch_result(steps, results, index, "input_continues", error)
                    return self._batch_result(steps, results, index, "error", error)
                results.append({"op": step["op"], "result": outcome})
                if (isinstance(outcome, dict) and outcome.get("input_complete") is False):
                    return self._batch_result(steps, results, index + 1, "input_continues",
                                              "Long input continues in a separate call with continue_token.")
                if self._deadline and time.monotonic() >= self._deadline:
                    return self._batch_result(steps, results, index + 1, "time_budget",
                                              "The batch stopped inside its time budget; continue from stopped_at.")
            return self._batch_result(steps, results, len(steps), None, None)
        finally:
            self._deadline = None

    def _batch_result(self, steps, results, stopped_at, stop_reason, error):
        body = {"batch_complete": stop_reason is None,
                "planned_steps": len(steps), "completed_steps": len(results),
                "stopped_at": stopped_at, "steps": results}
        if stop_reason:
            body["stop_reason"] = stop_reason
            if isinstance(error, BridgeError):
                body["error"] = error.as_dict()
            else:
                body["message"] = str(error)
        return body
