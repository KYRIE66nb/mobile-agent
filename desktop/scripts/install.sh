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
if ! command -v adb >/dev/null 2>&1 && [ ! -f "$HOME/Library/Android/sdk/platform-tools/adb" ]; then
  printf '%s\n' "MISSING: adb not found. Install Android platform-tools or set ADB." >&2
  fail=1
fi
[ "$fail" -eq 0 ] || exit 1

printf '%s\n' "Register this MCP server in your agent host:"
printf '\n%s\n' '  {'
printf '%s\n' '    "mcpServers": {'
printf '%s\n' '      "android_use": {'
printf '%s\n' '        "type": "stdio",'
printf '%s\n' '        "command": "python3",'
printf '%s\n' "        \"args\": [\"$root/server/android_use.py\"],"
printf '%s\n' "        \"cwd\": \"$root\""
printf '%s\n' '      }'
printf '%s\n' '    }'
printf '%s\n' '  }'
printf '\n%s\n' "Then connect an Android device (USB debugging) and call:"
printf '%s\n' "  aua_setup discover -> configure -> enable_accessibility -> pair -> start_bridge -> forward"
printf '%s\n' "  aua_ready"
