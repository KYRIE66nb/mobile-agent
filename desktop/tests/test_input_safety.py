"""Regression coverage for Android accessibility text-input safety boundaries."""
import copy
import sys
from pathlib import Path
from unittest.mock import patch

import unittest

TESTS = Path(__file__).resolve().parent
sys.path.insert(0, str(TESTS))

from test_android_use import BridgeFixture, FakeBridgeHandler  # noqa: E402
from adb_text import split_text  # noqa: E402
from adb_client import BridgeError  # noqa: E402


class InputSafetyTests(BridgeFixture):
    @staticmethod
    def _utf16_length(text):
        return len(text.encode("utf-16-le")) // 2

    def _raw(self, package="com.app", viewport=None, rotation=0, nodes=None, ident="changed"):
        return {
            "ok": True,
            "observation_id": ident,
            "session_id": self.client.session_id or "sess-1",
            "captured_at": 0,
            "viewport": viewport or {"width": 1080, "height": 2400},
            "rotation": rotation,
            "foreground_package": package,
            "nodes": copy.deepcopy(nodes if nodes is not None else FakeBridgeHandler.nodes),
            "node_count": len(nodes if nodes is not None else FakeBridgeHandler.nodes),
            "truncated": False,
        }

    def _mutating_inspect(self, phone, transform):
        original = phone._inspect

        def inspect(*args, **kwargs):
            raw = transform(copy.deepcopy(original(*args, **kwargs)))
            phone._remember(raw)
            return raw

        return patch.object(phone, "_inspect", side_effect=inspect)

    def _after_first_write(self, phone, transform):
        original = phone._inspect

        def inspect(*args, **kwargs):
            raw = original(*args, **kwargs)
            if any(action["kind"] == "input_text" for action in FakeBridgeHandler.actions):
                raw = transform(raw)
                phone._remember(raw)
            return raw

        return patch.object(phone, "_inspect", side_effect=inspect)

    def _start_continuation(self, phone, text="x" * 600):
        phone.call_budget = 0
        result = phone.type_text(text=text)
        self.assertFalse(result["input_complete"])
        return result["continue_token"]

    def test_split_text_caps_each_piece_by_utf16_units(self):
        text = "😀" * 251

        pieces = split_text(text, 500)

        self.assertEqual("".join(pieces), text)
        self.assertEqual([self._utf16_length(piece) for piece in pieces], [500, 2])

    def test_split_text_rejects_single_grapheme_over_utf16_limit(self):
        with self.assertRaises(ValueError):
            split_text("😀", 1)

    def test_utf16_total_limit_rejects_before_first_write(self):
        phone = self.controller()

        with self.assertRaises(BridgeError) as caught:
            phone.type_text(text="😀" * 1001)

        self.assertEqual(caught.exception.code, "invalid_argument")
        self.assertEqual([a for a in FakeBridgeHandler.actions if a["kind"] == "input_text"], [])

    def test_isolated_surrogate_rejects_before_any_device_read(self):
        phone = self.controller()

        with patch.object(phone, "_fresh_context", side_effect=AssertionError("device read")):
            with self.assertRaises(BridgeError) as caught:
                phone.type_text(text="bad\ud800text")

        self.assertEqual(caught.exception.code, "invalid_argument")
        self.assertEqual(FakeBridgeHandler.actions, [])

    def test_utf16_append_limit_rejects_existing_prefix_before_first_write(self):
        field = next(node for node in FakeBridgeHandler.nodes if node.get("ref", "").endswith("input|EditText"))
        field["text"] = "a" * 1999
        phone = self.controller()

        with self.assertRaises(BridgeError) as caught:
            phone.type_text(text="😀", replace=False)

        self.assertEqual(caught.exception.code, "invalid_argument")
        self.assertEqual([a for a in FakeBridgeHandler.actions if a["kind"] == "input_text"], [])

    def test_explicit_unfocused_selector_can_write_its_first_piece(self):
        field = next(node for node in FakeBridgeHandler.nodes if node.get("ref", "").endswith("input|EditText"))
        field["focused"] = False
        phone = self.controller()

        phone.type_text(selector={"type": "EditText"}, text="hello")

        writes = [a for a in FakeBridgeHandler.actions if a["kind"] == "input_text"]
        self.assertEqual(len(writes), 1)
        self.assertEqual(writes[0]["node_ref"], field["ref"])

    def test_initial_target_waits_for_original_ref_to_stabilize(self):
        phone = self.controller()
        original = phone._inspect
        calls = {"count": 0}
        original_ref = "w0|com.app:id/input|EditText"

        def inspect(*args, **kwargs):
            calls["count"] += 1
            raw = original(*args, **kwargs)
            if calls["count"] == 2:
                nodes = copy.deepcopy(raw["nodes"])
                field = next(node for node in nodes if node.get("ref") == original_ref)
                field["ref"] = "w1|com.app:id/input|EditText"
                raw = {**raw, "nodes": nodes}
            elif calls["count"] == 3:
                nodes = copy.deepcopy(raw["nodes"])
                field = next(node for node in nodes if node.get("ref") == original_ref)
                field["rect"] = [65, 605, 960, 140]
                raw = {**raw, "nodes": nodes}
            phone._remember(raw)
            return raw

        with patch.object(phone, "_inspect", side_effect=inspect):
            result = phone.type_text(text="hello")

        self.assertTrue(result["action_complete"])
        writes = [action for action in FakeBridgeHandler.actions if action["kind"] == "input_text"]
        self.assertEqual(len(writes), 1)
        self.assertEqual(writes[0]["node_ref"], original_ref)

    def test_guard_rejects_foreground_change_from_fresh_observation(self):
        phone = self.controller()
        observed = phone.observe("tree")
        changed = self._raw(package="other.app")

        with patch.object(phone, "_inspect", return_value=changed):
            with self.assertRaises(BridgeError) as caught:
                phone.guard(observed["observation_id"])

        self.assertEqual(caught.exception.code, "stale_observation")
        self.assertEqual(caught.exception.details["reason"], "foreground_changed")

    def test_continuation_rejects_changed_app_without_another_write(self):
        phone = self.controller()
        token = self._start_continuation(phone)
        before = len([a for a in FakeBridgeHandler.actions if a["kind"] == "input_text"])

        with self._mutating_inspect(
            phone,
            lambda raw: {**raw, "foreground_package": "other.app"},
        ):
            with self.assertRaises(BridgeError) as caught:
                phone.type_text(continue_token=token)

        self.assertEqual(caught.exception.code, "input_continuation_expired")
        self.assertEqual(len([a for a in FakeBridgeHandler.actions if a["kind"] == "input_text"]), before)

    def test_continuation_rejects_bridge_session_change_without_another_write(self):
        phone = self.controller()
        token = self._start_continuation(phone)
        phone.client.session_id = "replacement-session"
        before = len([a for a in FakeBridgeHandler.actions if a["kind"] == "input_text"])

        with self.assertRaises(BridgeError) as caught:
            phone.type_text(continue_token=token)

        self.assertEqual(caught.exception.code, "input_continuation_expired")
        self.assertEqual(caught.exception.details["reason"], "session_changed")
        self.assertEqual(len([a for a in FakeBridgeHandler.actions if a["kind"] == "input_text"]), before)

    def test_each_piece_rechecks_context_before_writing(self):
        phone = self.controller()
        calls = {"count": 0}

        def transform(raw):
            calls["count"] += 1
            return {**raw, "foreground_package": "other.app"} if calls["count"] == 3 else raw

        with self._mutating_inspect(phone, transform):
            with self.assertRaises(BridgeError) as caught:
                phone.type_text(text="x" * 600)

        self.assertEqual(caught.exception.code, "input_continuation_expired")
        self.assertEqual(len([a for a in FakeBridgeHandler.actions if a["kind"] == "input_text"]), 1)

    def test_delayed_piece_readback_waits_before_continuation_without_duplicate_write(self):
        phone = self.controller()
        phone.call_budget = 0
        stale_reads = {"remaining": 1}

        def transform(raw):
            if stale_reads["remaining"]:
                stale_reads["remaining"] -= 1
                nodes = copy.deepcopy(raw["nodes"])
                field = next(node for node in nodes if node.get("ref", "").endswith("input|EditText"))
                field["text"] = ""
                return {**raw, "nodes": nodes}
            return raw

        with self._after_first_write(phone, transform):
            token = phone.type_text(text="x" * 600)
            result = phone.type_text(continue_token=token["continue_token"])

        writes = [action for action in FakeBridgeHandler.actions if action["kind"] == "input_text"]
        self.assertTrue(result["action_complete"])
        self.assertEqual(len(writes), 2)
        self.assertEqual([write["text"] for write in writes], ["x" * 500, "x" * 100])

    def test_final_verify_waits_for_delayed_readback_before_input_mismatch(self):
        phone = self.controller()
        stale_reads = {"remaining": 1}

        def transform(raw):
            if stale_reads["remaining"]:
                stale_reads["remaining"] -= 1
                nodes = copy.deepcopy(raw["nodes"])
                field = next(node for node in nodes if node.get("ref", "").endswith("input|EditText"))
                field["text"] = ""
                return {**raw, "nodes": nodes}
            return raw

        with self._after_first_write(phone, transform):
            result = phone.type_text(text="hello", verify=True)

        self.assertTrue(result["verified"])
        writes = [action for action in FakeBridgeHandler.actions if action["kind"] == "input_text"]
        self.assertEqual(len(writes), 1)

    def test_persistent_wrong_readback_rejects_without_append_or_submit(self):
        phone = self.controller()
        phone.call_budget = 0

        def transform(raw):
            nodes = copy.deepcopy(raw["nodes"])
            field = next(node for node in nodes if node.get("ref", "").endswith("input|EditText"))
            field["text"] = "wrong"
            return {**raw, "nodes": nodes}

        with self._after_first_write(phone, transform):
            with self.assertRaises(BridgeError) as caught:
                phone.type_text(text="x" * 600, submit=True)

        self.assertEqual(caught.exception.code, "input_continuation_expired")
        self.assertEqual(caught.exception.details["reason"], "field_changed")
        writes = [action for action in FakeBridgeHandler.actions if action["kind"] == "input_text"]
        self.assertEqual(len(writes), 1)
        self.assertFalse(any(action["kind"] == "press_key" for action in FakeBridgeHandler.actions))

    def test_readback_wait_stops_immediately_when_app_changes(self):
        phone = self.controller()
        phone.call_budget = 0

        with self._after_first_write(phone, lambda raw: {**raw, "foreground_package": "other.app"}):
            with self.assertRaises(BridgeError) as caught:
                phone.type_text(text="x" * 600, submit=True)

        self.assertEqual(caught.exception.code, "input_continuation_expired")
        self.assertEqual(caught.exception.details["reason"], "app_changed")
        writes = [action for action in FakeBridgeHandler.actions if action["kind"] == "input_text"]
        self.assertEqual(len(writes), 1)
        self.assertFalse(any(action["kind"] == "press_key" for action in FakeBridgeHandler.actions))

    def test_readback_wait_stops_immediately_when_focus_changes(self):
        phone = self.controller()

        def transform(raw):
            nodes = copy.deepcopy(raw["nodes"])
            field = next(node for node in nodes if node.get("ref", "").endswith("input|EditText"))
            field["focused"] = False
            return {**raw, "nodes": nodes}

        with self._after_first_write(phone, transform):
            with self.assertRaises(BridgeError) as caught:
                phone.type_text(text="hello", verify=True)

        self.assertEqual(caught.exception.code, "input_continuation_expired")
        self.assertEqual(caught.exception.details["reason"], "focus_changed")
        writes = [action for action in FakeBridgeHandler.actions if action["kind"] == "input_text"]
        self.assertEqual(len(writes), 1)
        self.assertFalse(any(action["kind"] == "press_key" for action in FakeBridgeHandler.actions))

    def test_each_piece_rejects_focus_switch_to_another_field(self):
        phone = self.controller()
        calls = {"count": 0}

        def transform(raw):
            calls["count"] += 1
            if calls["count"] != 3:
                return raw
            nodes = copy.deepcopy(raw["nodes"])
            field = next(node for node in nodes if node.get("ref", "").endswith("input|EditText"))
            field["focused"] = False
            other = copy.deepcopy(field)
            other["ref"] = "w0|com.app:id/other|EditText"
            other["focused"] = True
            return {**raw, "nodes": nodes + [other]}

        with self._mutating_inspect(phone, transform):
            with self.assertRaises(BridgeError) as caught:
                phone.type_text(text="x" * 600)

        self.assertEqual(caught.exception.code, "input_continuation_expired")
        self.assertEqual(caught.exception.details["reason"], "focus_changed")
        self.assertEqual(len([a for a in FakeBridgeHandler.actions if a["kind"] == "input_text"]), 1)

    def test_continuation_rejects_changed_viewport_and_rotation(self):
        for changed in (
            {"viewport": {"width": 1200, "height": 2400}},
            {"rotation": 90},
        ):
            with self.subTest(changed=changed):
                phone = self.controller()
                token = self._start_continuation(phone)
                before = len([a for a in FakeBridgeHandler.actions if a["kind"] == "input_text"])

                with self._mutating_inspect(phone, lambda raw, changed=changed: {**raw, **changed}):
                    with self.assertRaises(BridgeError) as caught:
                        phone.type_text(continue_token=token)

                self.assertEqual(caught.exception.code, "input_continuation_expired")
                self.assertEqual(len([a for a in FakeBridgeHandler.actions if a["kind"] == "input_text"]), before)

    def test_continuation_rejects_nonunique_or_invisible_target(self):
        for mutate in ("duplicate", "invisible", "disabled", "not_editable"):
            with self.subTest(mutate=mutate):
                phone = self.controller()
                token = self._start_continuation(phone)
                nodes = copy.deepcopy(FakeBridgeHandler.nodes)
                field = next(node for node in nodes if node.get("ref", "").endswith("input|EditText"))
                if mutate == "duplicate":
                    nodes.append(copy.deepcopy(field))
                elif mutate == "invisible":
                    field["visible"] = False
                elif mutate == "disabled":
                    field["enabled"] = False
                else:
                    field["editable"] = False
                before = len([a for a in FakeBridgeHandler.actions if a["kind"] == "input_text"])

                with self._mutating_inspect(phone, lambda raw, nodes=nodes: {**raw, "nodes": nodes}):
                    with self.assertRaises(BridgeError) as caught:
                        phone.type_text(continue_token=token)

                self.assertEqual(caught.exception.code, "input_continuation_expired")
                self.assertEqual(len([a for a in FakeBridgeHandler.actions if a["kind"] == "input_text"]), before)

    def test_continuation_rejects_field_text_that_is_not_written_prefix(self):
        phone = self.controller()
        token = self._start_continuation(phone)
        field = next(node for node in FakeBridgeHandler.nodes if node.get("ref", "").endswith("input|EditText"))
        field["text"] = "tampered"
        before = len([a for a in FakeBridgeHandler.actions if a["kind"] == "input_text"])

        with self._mutating_inspect(phone, lambda raw: {**raw, "nodes": copy.deepcopy(FakeBridgeHandler.nodes)}):
            with self.assertRaises(BridgeError) as caught:
                phone.type_text(continue_token=token)

        self.assertEqual(caught.exception.code, "input_continuation_expired")
        self.assertEqual(len([a for a in FakeBridgeHandler.actions if a["kind"] == "input_text"]), before)

    def test_submit_rechecks_original_target_focus_before_enter(self):
        phone = self.controller()
        nodes = copy.deepcopy(FakeBridgeHandler.nodes)
        field = next(node for node in nodes if node.get("ref", "").endswith("input|EditText"))
        other = copy.deepcopy(field)
        other["ref"] = "w0|com.app:id/other|EditText"
        other["focused"] = True
        field["text"] = "hello"
        field["focused"] = False
        calls = {"count": 0}

        def transform(raw):
            calls["count"] += 1
            return {**raw, "nodes": nodes + [other]} if calls["count"] == 3 else raw

        with self._mutating_inspect(phone, transform):
            with self.assertRaises(BridgeError) as caught:
                phone.type_text(text="hello", submit=True)

        self.assertEqual(caught.exception.code, "input_continuation_expired")
        self.assertFalse(any(action["kind"] == "press_key" for action in FakeBridgeHandler.actions))

    def test_verify_reports_final_field_mismatch_after_context_checks(self):
        phone = self.controller()
        calls = {"count": 0}

        def transform(raw):
            calls["count"] += 1
            if calls["count"] < 4:
                return raw
            nodes = copy.deepcopy(raw["nodes"])
            field = next(node for node in nodes if node.get("ref", "").endswith("input|EditText"))
            field["text"] = "wrong"
            return {**raw, "nodes": nodes}

        with self._mutating_inspect(phone, transform):
            with self.assertRaises(BridgeError) as caught:
                phone.type_text(text="hello", verify=True)

        self.assertEqual(caught.exception.code, "input_mismatch")


if __name__ == "__main__":
    unittest.main()
