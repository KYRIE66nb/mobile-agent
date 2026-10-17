"""Regression tests for conservative installed-app inventory handling."""

import hashlib
import json
import sys
import tempfile
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "server"))

from adb_apps import AppCatalog  # noqa: E402


class FakeAppsClient:
    def __init__(self, response):
        self.response = response
        self.calls = []

    def request(self, method, path):
        self.calls.append((method, path))
        if isinstance(self.response, BaseException):
            raise self.response
        return self.response


def write_cache(directory, apps, serial="dev1"):
    path = Path(directory) / "apps-installed-cache.json"
    path.write_text(json.dumps({
        "device_key": hashlib.sha256(serial.encode()).hexdigest(),
        "fetched_at": time.time(),
        "apps": apps,
    }))
    return path


class AppInventoryTests(unittest.TestCase):
    def catalog(self, directory, client):
        return AppCatalog(directory, client, serial_getter=lambda: "dev1")

    def test_empty_inventory_is_successful_and_cached(self):
        client = FakeAppsClient({"ok": True, "apps": []})
        with tempfile.TemporaryDirectory() as directory:
            catalog = self.catalog(directory, client)

            first = catalog.lookup("微信", source="installed")
            second = catalog.lookup("微信", source="installed")

        self.assertTrue(first["ok"])
        self.assertEqual(first["candidates"], [])
        self.assertFalse(first["warnings"])
        self.assertTrue(second["ok"])
        self.assertEqual(client.calls, [("GET", "/apps")])

    def test_malformed_inventory_does_not_crash_or_cache_and_catalog_falls_back(self):
        malformed = (None, [], {"ok": True}, {"ok": False, "apps": []},
                     {"ok": True, "apps": None},
                     {"ok": True, "apps": {}})
        for response in malformed:
            with self.subTest(response=response), tempfile.TemporaryDirectory() as directory:
                client = FakeAppsClient(response)
                result = self.catalog(directory, client).lookup("微信", source="auto")
                cache = Path(directory) / "apps-installed-cache.json"

                self.assertTrue(result["ok"])
                self.assertTrue(result["warnings"])
                self.assertTrue(any(row["package_name"] == "com.tencent.mm"
                                    and not row["installed_verified"]
                                    for row in result["candidates"]))
                self.assertFalse(cache.exists())

    def test_mixed_inventory_is_a_warning_without_installation_proof_or_cache(self):
        client = FakeAppsClient({"ok": True, "apps": [
            {"label": "微信", "package_name": "com.tencent.mm"},
            {"label": "没有包名"},
            "not-an-app-row",
        ]})
        with tempfile.TemporaryDirectory() as directory:
            result = self.catalog(directory, client).lookup("微信", source="auto")
            cache = Path(directory) / "apps-installed-cache.json"

        self.assertTrue(result["ok"])
        self.assertTrue(result["warnings"])
        candidate = next(row for row in result["candidates"]
                         if row["package_name"] == "com.tencent.mm")
        self.assertFalse(candidate["installed_verified"])
        self.assertFalse(candidate["installation_checked"])
        self.assertFalse(cache.exists())

    def test_valid_inventory_rows_are_marked_verified_and_cached_with_evidence(self):
        client = FakeAppsClient({"ok": True, "apps": [
            {"label": "微信", "package_name": "com.tencent.mm"},
        ]})
        with tempfile.TemporaryDirectory() as directory:
            result = self.catalog(directory, client).lookup("微信", source="installed")
            cache = json.loads((Path(directory) / "apps-installed-cache.json").read_text())

        candidate = result["candidates"][0]
        self.assertTrue(result["ok"])
        self.assertEqual(candidate["origin"], "installed")
        self.assertTrue(candidate["installed_verified"])
        self.assertTrue(candidate["installation_checked"])
        self.assertIn("WeChat", candidate["aliases"])
        self.assertTrue(candidate["verified_at"])
        self.assertEqual(cache["apps"][0]["package_name"], "com.tencent.mm")
        self.assertEqual(cache["apps"][0]["origin"], "installed")
        self.assertEqual(cache["apps"][0]["aliases"], [])
        self.assertTrue(cache["apps"][0]["installed_verified"])
        self.assertTrue(cache["apps"][0]["installation_checked"])

    def test_invalid_cache_rows_are_ignored_and_revalidated(self):
        bad_rows = [
            {"name": "微信", "package_name": "com.tencent.mm", "aliases": [],
             "origin": "catalog", "installed_verified": True,
             "installation_checked": True},
            {"name": "微信", "package_name": "com.tencent.mm", "aliases": [],
             "origin": "installed", "installed_verified": False,
             "installation_checked": True},
            {"name": "微信", "package_name": "not a package", "aliases": [],
             "origin": "installed", "installed_verified": True,
             "installation_checked": True},
            {"name": "微信", "package_name": "com.tencent.mm",
             "aliases": "微信", "origin": "installed",
             "installed_verified": True, "installation_checked": True},
        ]
        for bad_row in bad_rows:
            with self.subTest(bad_row=bad_row), tempfile.TemporaryDirectory() as directory:
                cache = write_cache(directory, [bad_row])
                client = FakeAppsClient({"ok": True, "apps": [
                    {"label": "微信", "package_name": "com.tencent.mm"},
                ]})
                result = self.catalog(directory, client).lookup("微信", source="installed")
                refreshed = json.loads(cache.read_text())

                self.assertTrue(result["ok"])
                self.assertEqual(client.calls, [("GET", "/apps")])
                self.assertTrue(result["candidates"][0]["installed_verified"])
                self.assertTrue(refreshed["apps"][0]["installation_checked"])
                self.assertEqual(refreshed["apps"][0]["origin"], "installed")


if __name__ == "__main__":
    unittest.main()
