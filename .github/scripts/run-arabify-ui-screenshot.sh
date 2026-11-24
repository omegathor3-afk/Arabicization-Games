#!/usr/bin/env bash
# Screenshot the Arabify app's own UI on the emulator (design review evidence).
set -euo pipefail
cd "$GITHUB_WORKSPACE"

note_failure() {
  local message="$1"
  message="${message//'%'/'%25'}"
  message="${message//$'\n'/'%0A'}"
  message="${message//$'\r'/'%0D'}"
  echo "::error::${message}"
}

dump_hierarchy() {
  local destination="$1"
  adb shell uiautomator dump --compressed /sdcard/arabify-window.xml >/dev/null 2>&1 || return 1
  adb exec-out cat /sdcard/arabify-window.xml > "$destination" || return 1
  [[ -s "$destination" ]]
}

mkdir -p build/arabify-ui
APK="app/build/outputs/apk/debug/app-debug.apk"
[[ -s "$APK" ]] || { note_failure "app-debug.apk missing"; exit 1; }
adb install -r "$APK" || { note_failure "adb install of Arabify failed"; exit 1; }

# Arabic UI: switch the emulator to ar-EG so the app renders its Arabic strings
# with RTL layout, reboot once, then launch.
adb shell settings put system system_locales ar-EG || true
timeout 30 adb reboot || true
timeout 180 adb wait-for-device || { note_failure "emulator did not return after locale switch"; exit 1; }
for attempt in $(seq 1 90); do
  BOOTED="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  [[ "$BOOTED" == "1" ]] && break
  sleep 2
done

adb shell svc power stayon true >/dev/null 2>&1 || true
adb shell settings put system screen_off_timeout 1800000 >/dev/null 2>&1 || true
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb shell am force-stop com.omega.arabify >/dev/null 2>&1 || true
adb shell am start -W -n com.omega.arabify/.MainActivity > build/arabify-ui/launch.txt 2>&1 || { note_failure "Arabify launch failed: $(tail -c 300 build/arabify-ui/launch.txt)"; exit 1; }

for attempt in $(seq 1 30); do
  fg="$(adb shell dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity' | tail -n 1 | tr -d '\r')"
  [[ "$fg" == *"com.omega.arabify"* ]] && break
  sleep 2
done
fg="$(adb shell dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity' | tail -n 1 | tr -d '\r')"
if [[ "$fg" != *"com.omega.arabify"* ]]; then
  note_failure "Arabify did not come to the foreground: $fg"
  exit 1
fi

# Confirm the requested Arabic system locale is active before taking evidence.
locale="$(adb shell settings get system system_locales | tr -d '\r')"
if [[ "$locale" != *"ar-EG"* ]]; then
  note_failure "emulator locale is not ar-EG: $locale"
  exit 1
fi

# Capture the initial view and its accessibility hierarchy.
sleep 8
dump_hierarchy build/arabify-ui/before-scroll.xml || { note_failure "could not capture the initial accessibility hierarchy"; exit 1; }
adb exec-out screencap -p > build/arabify-ui/arabify-ui-top.png
[[ -s build/arabify-ui/arabify-ui-top.png ]] || { note_failure "top screenshot missing"; exit 1; }

# Retry the gesture, and do not label a duplicate top-of-screen capture as a
# scrolled screenshot. The hierarchy must change after the scroll.
scrolled=0
for attempt in 1 2 3; do
  adb shell input swipe 540 2100 540 320 900 || true
  sleep 2
  dump_hierarchy build/arabify-ui/after-scroll.xml || { note_failure "could not capture the post-scroll accessibility hierarchy"; exit 1; }
  if python3 - build/arabify-ui/before-scroll.xml build/arabify-ui/after-scroll.xml <<'PY'
import sys
import xml.etree.ElementTree as ET

def visible_app_nodes(path):
    root = ET.parse(path).getroot()
    return tuple(
        (
            node.get("class", ""),
            node.get("resource-id", ""),
            node.get("text", ""),
            node.get("content-desc", ""),
            node.get("bounds", ""),
        )
        for node in root.iter("node")
        if node.get("package") == "com.omega.arabify"
    )

before = visible_app_nodes(sys.argv[1])
after = visible_app_nodes(sys.argv[2])
if not before or not after:
    raise SystemExit("Could not identify ARABIFY nodes in both accessibility hierarchies")
raise SystemExit(0 if before != after else 1)
PY
  then
    scrolled=1
    break
  fi
done
if [[ "$scrolled" -ne 1 ]]; then
  note_failure "the app accessibility hierarchy did not change after three scroll gestures; refusing to publish a false scrolled screenshot"
  exit 1
fi

adb exec-out screencap -p > build/arabify-ui/arabify-ui-scrolled.png
[[ -s build/arabify-ui/arabify-ui-scrolled.png ]] || { note_failure "scrolled screenshot missing"; exit 1; }

python3 - <<'PY'
from pathlib import Path
import struct

for name in ("arabify-ui-top.png", "arabify-ui-scrolled.png"):
    path = Path("build/arabify-ui") / name
    data = path.read_bytes()
    if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        raise SystemExit(f"{name} is not a valid PNG")
    width, height = struct.unpack(">II", data[16:24])
    if width < 320 or height < 480:
        raise SystemExit(f"{name} has implausible dimensions: {width}x{height}")
    print(f"{name}: {width}x{height}, {len(data)} bytes")
PY
exit 0
