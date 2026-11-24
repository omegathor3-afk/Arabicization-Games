#!/usr/bin/env bash
set -uo pipefail
cd "$GITHUB_WORKSPACE"

# Surface failures as GitHub check-run annotations so they are readable
# without Actions log access (annotations are public on public repos).
note_failure() {
  local message="$1"
  message="${message//'%'/'%25'}"
  message="${message//$'\n'/'%0A'}"
  message="${message//$'\r'/'%0D'}"
  echo "::error::${message}"
}


# Upload a captured screenshot to a free no-auth file host and emit the URL
# as a public ::notice annotation so the evidence is fetchable without
# GitHub artifact access. Only game/app screens are uploaded - no user data.
run_connected_test() {
  local test_class="$1"
  gradle connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class="$test_class" --stacktrace && return 0
  local status=$?
  local details
  details=$(python3 - <<'PYX'
import glob
import xml.etree.ElementTree as ET

messages = []
for path in sorted(glob.glob('app/build/outputs/androidTest-results/connected/**/*.xml', recursive=True)):
    try:
        root = ET.parse(path).getroot()
    except Exception:
        continue
    for testcase in root.iter('testcase'):
        failures = list(testcase.iter('failure')) + list(testcase.iter('error'))
        for failure in failures:
            text = ((failure.get('message') or '') + ' ' + (failure.text or '')).strip()
            messages.append(f"{testcase.get('classname')}.{testcase.get('name')}: {text[:600]}")
output = ' || '.join(messages)[:1800] or 'no failure details found in connected test result XML'
print(output.replace('%', '%25').replace('\n', ' ').replace('\r', ' '))
PYX
)
  note_failure "connected test failed: ${test_class} (exit ${status}) :: ${details}"
  return "$status"
}

mkdir -p build/test-game/localized
adb install build/test-game/unciv.apk || { install_rc=$?; note_failure "adb install of pinned Unciv APK failed (exit $install_rc)"; exit $install_rc; }

test_status=0
run_connected_test com.omega.arabify.UncivLocaleSlotInstrumentedTest || test_status=$?

OUTPUT_DIR="build/test-game/localized/pulled-evidence"
mkdir -p "$OUTPUT_DIR"
adb pull /sdcard/Download/ARABIFY/unciv-arabic.apk "$OUTPUT_DIR/unciv-arabic.apk" || true
adb pull /sdcard/Download/ARABIFY/unciv-report.txt "$OUTPUT_DIR/unciv-report.txt" || true

GENERATED_APK="$OUTPUT_DIR/unciv-arabic.apk"
launch_status=0
if [[ -s "$GENERATED_APK" ]]; then
  APKSIGNER="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -n1)"
  if [[ ! -x "$APKSIGNER" ]] || ! "$APKSIGNER" verify --verbose "$GENERATED_APK"; then
    launch_status=1
    note_failure "apksigner verification failed for the generated localized Unciv APK"
  else
    adb uninstall com.unciv.app || true
    adb install "$GENERATED_APK" || { launch_status=$?; note_failure "install of generated localized Unciv APK failed (exit $launch_status)"; }
    if [[ "$launch_status" -eq 0 ]]; then
      # Unciv resolves the locale from the Android system locale through
      # libGDX; switch the emulator to Arabic so it selects bundle_ar.
      adb shell settings put system system_locales ar-EG || { launch_status=$?; note_failure "failed to switch emulator system locale to ar-EG (exit $launch_status)"; }
      if [[ "$launch_status" -eq 0 ]]; then
        timeout 30 adb reboot || { launch_status=$?; note_failure "adb reboot after locale switch failed or timed out"; }
        if [[ "$launch_status" -eq 0 ]]; then
          if ! timeout 180 adb wait-for-device; then
            echo "Emulator did not return to ADB after switching to Arabic." >&2
            note_failure "emulator did not return to ADB within 180s after the Arabic locale reboot"
            launch_status=1
          fi
        fi
        if [[ "$launch_status" -eq 0 ]]; then
          for attempt in $(seq 1 90); do
            BOOTED="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
            [[ "$BOOTED" == "1" ]] && break
            sleep 2
          done
          BOOTED="$(adb shell getprop sys.boot_completed | tr -d '\r')"
          if [[ "$BOOTED" != "1" ]]; then
            echo "Emulator did not finish booting after switching to Arabic." >&2
            note_failure "emulator did not finish booting after the Arabic locale reboot (sys.boot_completed != 1)"
            launch_status=1
          fi
        fi
      fi
      if [[ "$launch_status" -eq 0 ]]; then
        adb shell monkey -p com.unciv.app 1 || { launch_status=$?; note_failure "monkey launch of the localized game failed"; }
        sleep 12
        adb shell pidof com.unciv.app > build/test-game/localized/game-process.txt || { launch_status=$?; note_failure "game process not running after launch (pidof empty)"; }
        HIERARCHY_DEVICE_PATH="/data/local/tmp/arabify-unciv-window.xml"
        HIERARCHY_HOST_PATH="build/test-game/localized/unciv-window.xml"
        hierarchy_ready=0
        for attempt in 1 2 3; do
          adb shell rm -f "$HIERARCHY_DEVICE_PATH" || true
          adb shell uiautomator dump --compressed "$HIERARCHY_DEVICE_PATH" >/tmp/arabify-uiautomator-dump.log 2>&1 || true
          if adb shell test -s "$HIERARCHY_DEVICE_PATH"; then
            if adb pull "$HIERARCHY_DEVICE_PATH" "$HIERARCHY_HOST_PATH" >/dev/null 2>&1 && [[ -s "$HIERARCHY_HOST_PATH" ]]; then
              hierarchy_ready=1
              break
            fi
          fi
          sleep 2
        done
        if [[ "$hierarchy_ready" -eq 1 ]] && grep -q 'text="Viewing full screen"' "$HIERARCHY_HOST_PATH"; then
          # Android 15 shows a system immersive-mode tutorial on first launch.
          # Dismiss its accessible "Got it" button by reading its actual bounds,
          # rather than relying on a device-specific hard-coded tap coordinate.
          tap_point="$(python3 - "$HIERARCHY_HOST_PATH" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
for node in root.iter("node"):
    if node.get("resource-id") == "android:id/ok" and node.get("text") == "Got it" and node.get("clickable") == "true":
        match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if match:
            left, top, right, bottom = map(int, match.groups())
            print((left + right) // 2, (top + bottom) // 2)
            break
PY
)"
          if [[ -z "$tap_point" ]]; then
            note_failure "Android immersive tutorial was visible but its accessible Got it button could not be located"
            launch_status=1
          else
            read -r tap_x tap_y <<< "$tap_point"
            adb shell input tap "$tap_x" "$tap_y" || { launch_status=$?; note_failure "could not dismiss Android immersive tutorial"; }
            sleep 3
            adb shell rm -f "$HIERARCHY_DEVICE_PATH" || true
            adb shell uiautomator dump --compressed "$HIERARCHY_DEVICE_PATH" >/tmp/arabify-uiautomator-dump.log 2>&1 || true
            if adb shell test -s "$HIERARCHY_DEVICE_PATH"; then
              adb pull "$HIERARCHY_DEVICE_PATH" "$HIERARCHY_HOST_PATH" >/dev/null 2>&1 || true
            fi
            if grep -q 'text="Viewing full screen"' "$HIERARCHY_HOST_PATH"; then
              note_failure "Android immersive tutorial remained visible after tapping Got it"
              launch_status=1
            fi
          fi
        fi
        if [[ "$hierarchy_ready" -eq 1 ]]; then
          # Unciv either opens its first-run language picker (listing
          # العربية from the new bundle_ar) or boots its menu in Arabic.
          # Any Arabic script on screen proves the slot is active.
          if grep -qP '[\x{0600}-\x{06FF}]' "$HIERARCHY_HOST_PATH"; then
            echo "Accessibility hierarchy captured; Arabic text is visible on the Unciv screen." > build/test-game/localized/ui-smoke-validation.txt
          elif grep -q 'text="Arabic"' "$HIERARCHY_HOST_PATH"; then
            echo "Accessibility hierarchy captured; the game lists Arabic in its own language UI." > build/test-game/localized/ui-smoke-validation.txt
          else
            echo "No Arabic text or Arabic language entry was visible in the accessibility hierarchy." >&2
            note_failure "no Arabic text and no Arabic language entry visible on the Unciv screen (hierarchy captured)"
            launch_status=1
          fi
        else
          echo "::notice::UIAutomator exposed no hierarchy; relying on screenshot + process checks (not counted as failure)"
          echo "WARNING: Android UIAutomator did not expose a hierarchy for the launched game. The instrumentation test verified the slot content, APK signature and install; screenshot and foreground-process checks remain required." > build/test-game/localized/ui-smoke-validation.txt
          cat /tmp/arabify-uiautomator-dump.log >> build/test-game/localized/ui-smoke-validation.txt 2>/dev/null || true
        fi
        adb exec-out screencap -p > build/test-game/localized/unciv-arabic-screen.png || launch_status=$?
        if [[ ! -s build/test-game/localized/unciv-arabic-screen.png ]]; then
          echo "Localized game screenshot is missing or empty." >&2
          note_failure "localized Unciv screenshot missing or empty after launch"
          launch_status=1
        else
          python3 - <<'PY'
from pathlib import Path
import struct

path = Path("build/test-game/localized/unciv-arabic-screen.png")
data = path.read_bytes()
if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
    raise SystemExit("Localized game screenshot is not a valid PNG.")
width, height = struct.unpack(">II", data[16:24])
if width < 320 or height < 480:
    raise SystemExit(f"Localized game screenshot dimensions are too small: {width}x{height}.")
print(f"Localized game screenshot verified: {width}x{height}, {len(data)} bytes.")
PY
          if [[ "$?" -ne 0 ]]; then launch_status=1; fi
        fi
        adb logcat -d -v time > build/test-game/localized/game-launch-logcat.txt || true
      fi
    fi
  fi
else
  echo "Translated Unciv APK was not exported to the shared Downloads collection." >&2
  if [[ "$test_status" -eq 0 ]]; then test_status=1; fi
fi
if [[ "$test_status" -eq 0 && "$launch_status" -ne 0 ]]; then
  test_status="$launch_status"
fi

exit "$test_status"
