"""App identity resolution: bridge /apps inventory plus a local catalog of common apps.

Only matching app metadata is returned. The installed-app list is fetched over the
loopback bridge and cached briefly; nothing leaves the machine.
"""
from __future__ import annotations

import datetime as dt
import hashlib
import json
import re
import time
from pathlib import Path

from adb_client import BridgeError

CATALOG_PATH = Path(__file__).resolve().parent / "apps.json"
INSTALLED_TTL = 120


def _normalise(value):
    return " ".join(str(value).casefold().split())


def _read(path, default=None):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return default


def _valid_package(value):
    return isinstance(value, str) and bool(
        re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+", value))


def _valid_app_text(value):
    return isinstance(value, str) and 1 <= len(value.strip()) <= 200


def _valid_aliases(value):
    return isinstance(value, list) and all(_valid_app_text(alias) for alias in value)


class AppCatalog:
    def __init__(self, state_dir, client, serial_getter=None):
        self.state_dir = Path(state_dir)
        self.client = client
        self.serial_getter = serial_getter or (lambda: None)

    @staticmethod
    def _catalog():
        document = _read(CATALOG_PATH, {})
        rows = []
        for app in document.get("apps", []) if isinstance(document, dict) else []:
            if not isinstance(app, dict) or not _valid_package(app.get("package_name")):
                continue
            rows.append({"name": app.get("name", app["package_name"]),
                         "aliases": app.get("aliases", []),
                         "package_name": app["package_name"],
                         "origin": "catalog", "installed_verified": False})
        return rows

    @staticmethod
    def _valid_cached_row(row):
        return (isinstance(row, dict)
                and _valid_package(row.get("package_name"))
                and _valid_app_text(row.get("name"))
                and _valid_aliases(row.get("aliases"))
                and row.get("origin") == "installed"
                and row.get("installed_verified") is True
                and row.get("installation_checked") is True
                and _valid_app_text(row.get("verified_at")))

    @classmethod
    def _cached_apps(cls, cache, device_key):
        if not isinstance(cache, dict) or cache.get("device_key") != device_key:
            return None
        fetched_at = cache.get("fetched_at")
        if (isinstance(fetched_at, bool) or not isinstance(fetched_at, (int, float))
                or not 0 <= time.time() - fetched_at < INSTALLED_TTL):
            return None
        apps = cache.get("apps")
        if not isinstance(apps, list) or not all(cls._valid_cached_row(row) for row in apps):
            return None
        return apps

    @staticmethod
    def _parse_installed_response(document):
        if not isinstance(document, dict):
            return [], False, "Installed-app query returned a non-object response; installation was not verified."
        if document.get("ok") is False:
            return [], False, "Installed-app query reported failure; installation was not verified."
        apps = document.get("apps")
        if not isinstance(apps, list):
            return [], False, "Installed-app query returned an invalid apps list; installation was not verified."
        rows, seen, malformed = [], set(), False
        for app in apps:
            if not isinstance(app, dict):
                malformed = True
                continue
            package = app.get("package_name")
            label = app.get("label")
            if not _valid_package(package) or not _valid_app_text(label):
                malformed = True
                continue
            if package in seen:
                continue
            seen.add(package)
            rows.append({"name": label[:200], "package_name": package, "aliases": [],
                         "origin": "installed", "installed_verified": True,
                         "installation_checked": True,
                         "verified_at": dt.datetime.now(dt.timezone.utc).isoformat()})
        if malformed:
            return rows, False, "Installed-app query contained malformed rows; valid rows are not complete installation proof."
        return rows, True, None

    def _installed(self):
        serial = self.serial_getter() or "default"
        cache_path = self.state_dir / "apps-installed-cache.json"
        key = hashlib.sha256(serial.encode()).hexdigest()
        cache = _read(cache_path, {})
        cached = self._cached_apps(cache, key)
        if cached is not None:
            return cached, True, None
        try:
            result = self.client.request("GET", "/apps")
        except BridgeError as error:
            return [], False, f"Installed-app query failed ({error.code}): {error}"
        rows, installation_checked, warning = self._parse_installed_response(result)
        if not installation_checked:
            return rows, False, warning
        try:
            cache_path.write_text(json.dumps(
                {"device_key": key, "fetched_at": time.time(), "apps": rows}, ensure_ascii=False))
            cache_path.chmod(0o600)
        except OSError:
            pass
        return rows, True, None

    @staticmethod
    def _score(row, query):
        target = _normalise(query)
        values = [_normalise(v) for v in
                  (row.get("name", ""), row["package_name"], *row.get("aliases", []))]
        if target in values:
            return 2
        return 1 if any(target in v for v in values if v) else 0

    def lookup(self, query, source="auto", limit=10):
        if (not isinstance(query, str) or not 1 <= len(query.strip()) <= 100
                or source not in {"auto", "catalog", "installed"}
                or isinstance(limit, bool) or not isinstance(limit, int) or not 1 <= limit <= 30):
            return {"ok": False, "error": {"code": "invalid_argument",
                                           "message": "query needs 1-100 characters, source auto/catalog/installed, limit 1-30."}}
        query = query.strip()
        warnings, searched, candidates = [], [], []
        installed, installation_checked = [], False
        if source in {"auto", "installed"}:
            installed, installation_checked, error = self._installed()
            searched.append("installed")
            if error:
                warnings.append(error)
            aliases = {row["package_name"]: row for row in self._catalog()}
            for row in installed:
                row = dict(row)
                reference = aliases.get(row["package_name"], {})
                row["aliases"] = [*reference.get("aliases", []), reference.get("name", "")]
                row["installation_checked"] = installation_checked
                if self._score(row, query):
                    candidates.append(row)
        if source in {"auto", "catalog"}:
            searched.append("catalog")
            candidates.extend(row for row in self._catalog() if self._score(row, query))
        installed_ids = {row["package_name"] for row in installed}
        merged = {}
        for row in candidates:
            row = dict(row)
            package = row["package_name"]
            row["installed_verified"] = installation_checked and package in installed_ids
            if row.get("origin") == "installed":
                row["installation_checked"] = installation_checked
            if package not in merged:
                merged[package] = row
            else:
                for key, value in row.items():
                    if key not in merged[package] or merged[package][key] in (None, "", []):
                        merged[package][key] = value
        ranked = sorted(merged.values(),
                        key=lambda row: (-self._score(row, query), -int(row["installed_verified"]),
                                         row["name"], row["package_name"]))
        exact = [row for row in ranked if self._score(row, query) == 2]
        if exact:
            ranked = exact
        ok = not (source == "installed" and not installation_checked)
        result = {"ok": ok, "query": query, "source": source,
                  "candidates": ranked[:limit], "total_matches": len(ranked),
                  "searched_sources": searched, "warnings": warnings,
                  "next_action": "Choose the matching app, then aua_launch_app with its package_name and verify foreground. Catalog metadata alone does not prove installation."}
        if not ok:
            result["error"] = {"code": "app_lookup_unavailable",
                                "message": " ".join(warnings) or "Installed-app query could not be completed.",
                                "uncertain": False}
        return result
