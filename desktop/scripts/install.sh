#!/bin/sh
# Android Use desktop install: verify prerequisites and print the MCP registration.
# The phone side is set up per-device through aua_setup (adb), not by this script.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

fail=0
if ! command -v python3 >/dev/null 2>&1; then
  printf '%s\n' "MISSING: python3 is required." >&2
  fail=1
fi
adb_override="${ADB:-}"
adb_configured=""
if [ -n "$adb_override" ]; then
  if [ -f "$adb_override" ] && [ -x "$adb_override" ]; then
    adb_configured="$adb_override"
  else
    adb_from_path=$(command -v "$adb_override" 2>/dev/null || true)
    if [ -n "$adb_from_path" ] && [ -f "$adb_from_path" ] && [ -x "$adb_from_path" ]; then
      adb_configured="$adb_from_path"
    else
      printf '%s\n' "MISSING: configured ADB is not executable: $adb_override" >&2
      fail=1
    fi
  fi
elif ! command -v adb >/dev/null 2>&1 && { [ ! -f "$HOME/Library/Android/sdk/platform-tools/adb" ] || [ ! -x "$HOME/Library/Android/sdk/platform-tools/adb" ]; }; then
  printf '%s\n' "MISSING: adb not found. Install Android platform-tools or set ADB." >&2
  fail=1
fi
[ "$fail" -eq 0 ] || exit 1

printf '%s\n' "Register this MCP server in your agent host:"
python3 - "$root" "$adb_configured" <<'PY'
import json
import os
import shutil
import sys

root = sys.argv[1]
adb = sys.argv[2]
server = {
    "type": "stdio",
    "command": "python3",
    "args": [os.path.join(root, "server", "android_use.py")],
    "cwd": root,
}
if adb:
    resolved_adb = os.path.abspath(shutil.which(adb) or adb)
    if not os.path.isfile(resolved_adb) or not os.access(resolved_adb, os.X_OK):
        raise SystemExit(f"MISSING: configured ADB is not executable: {adb}")
    server["env"] = {"ADB": resolved_adb}

print(json.dumps({"mcpServers": {"android_use": server}}, indent=2))
PY
printf '\n%s\n' "Then connect an Android 11+ device (USB debugging) and complete these steps:"
printf '%s\n' "  aua_setup discover -> configure -> enable_accessibility -> pair"
printf '%s\n' "  In the phone app, manually enable Desktop control (USB), then call:"
printf '%s\n' "  aua_setup start_bridge -> forward -> aua_ready"
