"""Verify the real MCP stdio process publishes and validates the new tools."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "server"))
import android_use


class StdioTests(unittest.TestCase):
    def test_invalid_surrogate_cannot_break_the_stdio_worker_or_reach_the_device(self):
        server = Path(__file__).resolve().parent.parent / "server" / "android_use.py"
        requests = [
            {"jsonrpc": "2.0", "id": 1, "method": "initialize"},
            {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {
                "name": "aua_type_text", "arguments": {"text": "\ud800"}}},
            {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {
                "name": "aua_screenshot", "arguments": {"\ud800": True}}},
            {"jsonrpc": "2.0", "id": 4, "method": "tools/call", "params": {
                "name": "aua_screenshot", "arguments": {"retry": True}}},
        ]
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run([sys.executable, str(server), "--state-dir", directory],
                                    input="".join(json.dumps(row) + "\n" for row in requests),
                                    capture_output=True, text=True, timeout=20,
                                    env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"})
        self.assertEqual(result.returncode, 0, result.stderr)
        responses = {row["id"]: row["result"] for row in map(json.loads, result.stdout.splitlines())}
        for ident in (2, 3, 4):
            self.assertIn(ident, responses, result.stderr)
            self.assertEqual(json.loads(responses[ident]["content"][0]["text"])["error"]["code"], "invalid_argument")

    def test_screenshot_pixels_are_sent_once_as_an_image_not_again_in_text(self):
        image = {"base64": "large-encoded-screenshot", "mimeType": "image/jpeg",
                 "path": "/private/screen.jpg", "pixel_to_point": [1.5, 1.5]}
        for data in ({"image": image}, {"observation": {"image": image}},
                     {"error": {"observation": {"image": image}}},
                     {"steps": [{"result": {"observation": {"image": image}}}]}):
            with self.subTest(data=data):
                result = android_use.result_content(data)
                self.assertNotIn("large-encoded-screenshot", result["content"][0]["text"])
                self.assertIn("/private/screen.jpg", result["content"][0]["text"])
                self.assertEqual(result["content"][1]["data"], "large-encoded-screenshot")
                self.assertEqual(image["base64"], "large-encoded-screenshot")

    def test_new_tools_are_published_and_invalid_mutations_fail_before_device_access(self):
        server = Path(__file__).resolve().parent.parent / "server" / "android_use.py"
        requests = [
            {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-11-25"}},
            {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
            {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {
                "name": "aua_drag", "arguments": {"x1": -1, "y1": 10, "x2": 20, "y2": 30}}},
            {"jsonrpc": "2.0", "id": 4, "method": "tools/call", "params": {
                "name": "aua_screenshot", "arguments": {"retry": True}}},
        ]
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run([sys.executable, str(server), "--state-dir", directory],
                                    input="".join(json.dumps(row) + "\n" for row in requests),
                                    capture_output=True, text=True, timeout=10,
                                    env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"})
        self.assertEqual(result.returncode, 0, result.stderr)
        responses = {row["id"]: row["result"] for row in map(json.loads, result.stdout.splitlines())}
        self.assertEqual(responses[1]["serverInfo"]["version"], "0.2.0")
        tools = {tool["name"]: tool for tool in responses[2]["tools"]}
        self.assertIn("aua_drag", tools)
        self.assertFalse(tools["aua_screenshot"]["annotations"]["readOnlyHint"])
        self.assertFalse(tools["aua_screenshot"]["annotations"]["idempotentHint"])
        for ident in (3, 4):
            self.assertTrue(responses[ident]["isError"])
            error = json.loads(responses[ident]["content"][0]["text"])["error"]
            self.assertEqual(error["code"], "invalid_argument")


if __name__ == "__main__":
    unittest.main()
