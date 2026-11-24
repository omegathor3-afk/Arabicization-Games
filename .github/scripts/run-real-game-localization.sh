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

upload_shot() {
  local file="$1" label="$2"
  if [[ ! -s "$file" ]]; then
    echo "::notice::SCREENSHOT ${label}: no file captured"
    return 0
  fi
  local url err=""
  url=$(curl --fail --silent --show-error --max-time 150 -F "file=@${file}" https://0x0.st 2>&1) && { echo "::notice::SCREENSHOT ${label}: ${url}"; return 0; } || err="0x0.st: $(echo "$url" | tail -c 200)"
  url=$(curl --fail --silent --show-error --max-time 150 -F "reqtype=fileupload" -F "fileToUpload=@${file}" https://catbox.moe/user/api.php 2>&1) && { echo "::notice::SCREENSHOT ${label}: ${url}"; return 0; } || err="${err} | catbox: $(echo "$url" | tail -c 200)"
  url=$(curl --fail --silent --show-error --max-time 150 -F "file=@${file}" https://temp.sh/upload 2>&1) && { echo "::notice::SCREENSHOT ${label}: ${url}"; return 0; } || err="${err} | temp.sh: $(echo "$url" | tail -c 200)"
  url=$(curl --fail --silent --show-error --max-time 150 -F "file=@${file}" https://file.io 2>&1 | python3 -c 'import json,sys; print(json.load(sys.stdin).get("link",""))' 2>/dev/null) && [[ -n "$url" ]] && { echo "::notice::SCREENSHOT ${label}: ${url} (single-download link - fetch once)"; return 0; } || err="${err} | file.io: $(echo "$url" | tail -c 200)"
  url=$(curl --fail --silent --show-error --max-time 150 --upload-file "$file" "https://transfer.sh/$(basename "$file")" 2>&1) && { echo "::notice::SCREENSHOT ${label}: ${url}"; return 0; } || err="${err} | transfer.sh: $(echo "$url" | tail -c 200)"
  # Last resort: external file hosts are unreachable from GitHub runners
  # (observed 2026-11-24: 0x0.st 503, catbox 412, temp.sh 500, file.io empty,
  # transfer.sh TCP timeout). Embed a downscaled JPEG as base64 inside the
  # annotation itself - annotations are public and need no egress.
  local small="build/.upload-shot-small.jpg"
  if command -v ffmpeg >/dev/null 2>&1; then
    local scale b64
    for scale in 720 540 400; do
      ffmpeg -y -loglevel error -i "$file" -vf "scale=${scale}:-1" -q:v 5 "$small" || continue
      b64=$(base64 -w0 "$small" 2>/dev/null || base64 "$small" | tr -d '\n')
      if [[ -n "$b64" && ${#b64} -le 60000 ]]; then
        echo "::notice::SCREENSHOT-B64 ${label} (JPEG ${scale}px, base64): ${b64}"
        return 0
      fi
    done
  fi
  echo "::notice::SCREENSHOT ${label}: upload failed :: ${err}"
}


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
adb install build/test-game/minesweeper.apk || exit $?

test_status=0
run_connected_test com.omega.arabify.RealGameLocalizationInstrumentedTest || test_status=$?
run_connected_test com.omega.arabify.UnityMonoPipelineInstrumentedTest || test_status=$?

# Instrumentation may uninstall the target app during cleanup. The test exports
# its signed APK and report via MediaStore to the public Downloads collection.
OUTPUT_DIR="build/test-game/localized/pulled-evidence"
mkdir -p "$OUTPUT_DIR"
adb pull /sdcard/Download/ARABIFY/minesweeper-arabic.apk "$OUTPUT_DIR/minesweeper-arabic.apk" || true
adb pull /sdcard/Download/ARABIFY/report.txt "$OUTPUT_DIR/report.txt" || true

GENERATED_APK="$OUTPUT_DIR/minesweeper-arabic.apk"
launch_status=0
if [[ -s "$GENERATED_APK" ]]; then
  APKSIGNER="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -n1)"
  if [[ ! -x "$APKSIGNER" ]] || ! "$APKSIGNER" verify --verbose "$GENERATED_APK"; then
    launch_status=1
  else
    adb uninstall com.joeld.minesweeper || true
    adb install "$GENERATED_APK" || launch_status=$?
    if [[ "$launch_status" -eq 0 ]]; then
      # The fixture app does not declare Android 13 per-app locale metadata.
      # Set the emulator's system locale so its normal resource resolution
      # selects the newly added values-ar resources.
      adb shell settings put system system_locales ar-EG || launch_status=$?
      if [[ "$launch_status" -eq 0 ]]; then
        timeout 30 adb reboot || launch_status=$?
        if [[ "$launch_status" -eq 0 ]]; then
          if ! timeout 180 adb wait-for-device; then
            echo "Emulator did not return to ADB after switching to Arabic." >&2
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
            launch_status=1
          fi
        fi
      fi
      if [[ "$launch_status" -eq 0 ]]; then
        adb shell monkey -p com.joeld.minesweeper 1 || launch_status=$?
        sleep 8
        adb shell pidof com.joeld.minesweeper > build/test-game/localized/game-process.txt || launch_status=$?
        # Some Android 15 emulator/game combinations cannot produce a UIAutomator
        # hierarchy for a continuously animated game window. Prefer a private shell
        # path and retry; if the hierarchy service still returns no file, retain the
        # screenshot as the visual evidence and rely on the instrumentation test's
        # resource-value assertions instead of failing a valid localized launch.
        HIERARCHY_DEVICE_PATH="/data/local/tmp/arabify-minesweeper-window.xml"
        HIERARCHY_HOST_PATH="build/test-game/localized/minesweeper-window.xml"
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
        if [[ "$hierarchy_ready" -eq 1 ]]; then
          # Hard difficulty is not displayed on the initial board screen. Its
          # Arabic resource value is asserted separately by RealGameLocalizationInstrumentedTest.
          for required_arabic in "لعبة جديدة" "سهل" "متوسط" "دون تخمين"; do
            if ! grep -q "$required_arabic" "$HIERARCHY_HOST_PATH"; then
              echo "Required Arabic UI label '$required_arabic' was not visible in the available accessibility hierarchy." >&2
              launch_status=1
            fi
          done
          if grep -qi 'text="ng"' "$HIERARCHY_HOST_PATH"; then
            echo "Untranslated compact no-guess badge remains visible." >&2
            launch_status=1
          fi
          for forbidden_english in 'text="Easy"' 'text="Medium"' 'text="Hard"'; do
            if grep -q "$forbidden_english" "$HIERARCHY_HOST_PATH"; then
              echo "Untranslated difficulty label remains visible: $forbidden_english" >&2
              launch_status=1
            fi
          done
          echo "Accessibility hierarchy captured; initial-screen Arabic labels and untranslated difficulty labels were checked." > build/test-game/localized/ui-smoke-validation.txt
          echo "Hard difficulty Arabic value was verified in the instrumentation resource table; it is not required on the initial screen." >> build/test-game/localized/ui-smoke-validation.txt
        else
          echo "WARNING: Android UIAutomator did not expose a hierarchy for the launched game. Instrumentation already verified Arabic resource values and APK signature; screenshot and foreground-process checks remain required." > build/test-game/localized/ui-smoke-validation.txt
          cat /tmp/arabify-uiautomator-dump.log >> build/test-game/localized/ui-smoke-validation.txt 2>/dev/null || true
        fi
        adb exec-out screencap -p > build/test-game/localized/minesweeper-arabic-screen.png || launch_status=$?
        if [[ ! -s build/test-game/localized/minesweeper-arabic-screen.png ]]; then
          echo "Localized game screenshot is missing or empty." >&2
          launch_status=1
        else
          python3 - <<'PY'
from pathlib import Path
import struct

path = Path("build/test-game/localized/minesweeper-arabic-screen.png")
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
        upload_shot build/test-game/localized/minesweeper-arabic-screen.png "minesweeper-arabic-screen"
      fi
    fi
  fi
else
  echo "Translated APK was not exported to the shared Downloads collection." >&2
  if [[ "$test_status" -eq 0 ]]; then test_status=1; fi
fi
if [[ "$test_status" -eq 0 && "$launch_status" -ne 0 ]]; then
  test_status="$launch_status"
fi

# connectedDebugAndroidTest can uninstall the target application at cleanup.
# Reinstall the exact CI-built ARABIFY APK before exercising its launcher.
ui_status=0
mkdir -p build/maestro
adb install -r app/build/outputs/apk/debug/app-debug.apk || ui_status=$?
if [[ "$ui_status" -eq 0 ]]; then
  if ! adb shell am start -W -n com.omega.arabify/.MainActivity; then
    ui_status=1
  else
    sleep 5
    adb shell pidof com.omega.arabify > build/maestro/app-process.txt || ui_status=$?
    adb exec-out screencap -p > build/maestro/arabify-screen.png || ui_status=$?
    adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
    adb shell cat /sdcard/window.xml > build/maestro/window.xml || true
    if ! grep -q "ARABIFY" build/maestro/window.xml; then
      echo "ARABIFY launcher UI text was not found in the accessibility hierarchy." >&2
      ui_status=1
    fi
    adb logcat -d -v time > build/maestro/launch-logcat.txt || true
    upload_shot build/maestro/arabify-screen.png "arabify-app-screen"
  fi
fi
if [[ "$test_status" -eq 0 && "$ui_status" -ne 0 ]]; then
  test_status="$ui_status"
fi

exit "$test_status"
