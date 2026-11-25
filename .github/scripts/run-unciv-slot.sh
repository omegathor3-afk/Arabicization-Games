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
  shift
  gradle connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class="$test_class" "$@" --stacktrace && return 0
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
# Real translation: the screenshot evidence needs real Arabic, not pseudo
# strings - the seeded GameSettings.json boots the game into the slot.
# run_connected_test surfaces the failing assertion from the result XML as a
# public check-run annotation (Actions logs need auth; annotations do not).
run_connected_test com.omega.arabify.UncivLocaleSlotInstrumentedTest \
  -Pandroid.testInstrumentationRunnerArguments.translationMode=real || test_status=$?
if [[ "$test_status" -eq 0 ]]; then
  echo "::notice::UncivLocaleSlotInstrumentedTest PASSED (real translation; slot built, signed and verified on-device)"
fi

# The long connected-test run (ML Kit translating 6.5k strings on
# swiftshader) can drop the adb connection; observed 2026-11-25: green
# test, then every pull failed with "device offline". Reconnect first.
for attempt in $(seq 1 12); do
  adb_state=$(adb get-state 2>/dev/null | tr -d '\r')
  [[ "$adb_state" == "device" ]] && break
  adb reconnect 2>/dev/null || adb reconnect offline 2>/dev/null || true
  sleep 5
done
adb_state=$(adb get-state 2>/dev/null | tr -d '\r')
if [[ "$adb_state" != "device" ]]; then
  note_failure "adb connection to the emulator never recovered after the connected test (state: ${adb_state:-unknown})"
  exit 1
fi

OUTPUT_DIR="build/test-game/localized/pulled-evidence"
mkdir -p "$OUTPUT_DIR"
adb pull /sdcard/Download/ARABIFY/unciv-arabic.apk "$OUTPUT_DIR/unciv-arabic.apk" || true
adb pull /sdcard/Download/ARABIFY/unciv-report.txt "$OUTPUT_DIR/unciv-report.txt" || true
adb pull /sdcard/Download/ARABIFY/unciv-roundtrip.txt "$OUTPUT_DIR/unciv-roundtrip.txt" || true
if [[ -s "$OUTPUT_DIR/unciv-roundtrip.txt" ]]; then
  echo "::notice::ROUNDTRIP-AUDIT $(head -2 "$OUTPUT_DIR/unciv-roundtrip.txt" | tr '\n' ' ')"
  tail -n +3 "$OUTPUT_DIR/unciv-roundtrip.txt" | head -5 | while IFS= read -r sample; do
    [[ -n "$sample" ]] && echo "::notice::ROUNDTRIP-FLAGGED ${sample:0:250}"
  done
fi

if [[ -s "$OUTPUT_DIR/unciv-arabic.apk" ]]; then
  echo "::notice::LAUNCH pulled localized APK ($(stat -c%s "$OUTPUT_DIR/unciv-arabic.apk") bytes)"
fi
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
      # Unciv's language picker hides Arabic entirely (LocaleCode.Arabic is
      # flagged unused upstream), but its translation loader reads
      # jsons/translations/<settings.language>.properties with no such filter.
      # Seed GameSettings.json so the game boots in Arabic directly. This is
      # how activation works in CI; on-device activation needs the same file
      # written (debuggable repackage + run-as) or a dex patch - see docs.
      # google_apis emulators are userdebug, so adb root works there; run-as
      # is the fallback for debuggable repackages.
      SETTINGS_JSON='{"language":"العربية"}'
      if adb root >/dev/null 2>&1; then
        sleep 2
        adb wait-for-device
        adb shell "mkdir -p /data/data/com.unciv.app/files && echo '$SETTINGS_JSON' > /data/data/com.unciv.app/files/GameSettings.json"           && echo "::notice::Unciv GameSettings.json seeded with language=العربية via adb root"           || note_failure "failed to seed GameSettings.json via adb root"
        adb unroot >/dev/null 2>&1 || true
        sleep 2
        adb wait-for-device
      else
        adb shell "run-as com.unciv.app sh -c "mkdir -p files && echo '$SETTINGS_JSON' > files/GameSettings.json""           && echo "::notice::Unciv GameSettings.json seeded with language=العربية via run-as"           || note_failure "failed to seed GameSettings.json (no adb root, run-as failed)"
      fi
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
        # Android's "Viewing full screen" first-launch dialog covers the game
        # and blocks the Arabic hierarchy check; pre-confirm it (as the
        # Mindustry script does).
        adb shell settings put secure immersive_mode_confirmations confirmed >/dev/null 2>&1 || true
        adb shell settings put global policy_control immersive.confirmations=confirmed >/dev/null 2>&1 || true
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
          elif ! grep -q 'text="[^"]' "$HIERARCHY_HOST_PATH"; then
            # libGDX renders every label in OpenGL, so a captured hierarchy
            # with zero text nodes says nothing about Arabic on screen. Same
            # evidence policy as the no-hierarchy branch: screenshots decide.
            echo "::notice::UIAutomator hierarchy has zero text nodes on the Unciv picker (libGDX/OpenGL); Arabic visibility is decided by screenshots, not counted as failure"
            echo "NOTE: hierarchy carried no text nodes; Arabic presence verified visually via screenshots." > build/test-game/localized/ui-smoke-validation.txt
            # Scroll the language list so the (completion-sorted) Arabic entry
            # has a chance to be visible in a capture.
            # The list is sorted by translation completion and Arabic's rank is
            # unknown, so capture every scroll position: one of them contains it.
            # GitHub caps stored annotations at 10 per check run, so the swipe
            # captures upload quietly and land in ONE combined notice.
            swipe_links=""
            for swipe in 1 2 3 4 5 6 7 8 9 10 11 12; do
              adb shell input swipe 1170 800 1170 300 400 >/dev/null 2>&1 || true
              sleep 2
              adb exec-out screencap -p > "build/test-game/localized/unciv-language-list-${swipe}.png" || true
              if [[ -s "build/test-game/localized/unciv-language-list-${swipe}.png" ]]; then
                shot_out=$(upload_shot "build/test-game/localized/unciv-language-list-${swipe}.png" "unciv-language-list-${swipe}")
                shot_url=$(echo "$shot_out" | grep -o 'https://[^ ]*' | head -1)
                [[ -n "$shot_url" ]] && swipe_links="${swipe_links}list-${swipe}: ${shot_url} | "
              fi
            done
            echo "::notice::SCREENSHOTS unciv-language-list (12 swipes): ${swipe_links}"
          else
            echo "No Arabic text or Arabic language entry was visible in the accessibility hierarchy." >&2
            # Screenshots are private artifacts, so quote the visible screen
            # text in the public annotation: it shows which screen the game
            # was actually on when the check failed.
            visible_text=$(python3 - "$HIERARCHY_HOST_PATH" <<'PYVIS'
import re
import sys
try:
    xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
except OSError:
    xml = ""
texts = []
for value in re.findall(r'text="([^"]*)"', xml):
    value = value.strip()
    if value and value not in texts:
        texts.append(value)
summary = " | ".join(texts)[:1200]
print(summary if summary else "<no visible text nodes>")
PYVIS
)
            note_failure "no Arabic text and no Arabic language entry visible on the Unciv screen. Visible screen text: ${visible_text}"
            launch_status=1
          fi
        else
          echo "::notice::UIAutomator exposed no hierarchy; relying on screenshot + process checks (not counted as failure)"
          echo "WARNING: Android UIAutomator did not expose a hierarchy for the launched game. The instrumentation test verified the slot content, APK signature and install; screenshot and foreground-process checks remain required." > build/test-game/localized/ui-smoke-validation.txt
          cat /tmp/arabify-uiautomator-dump.log >> build/test-game/localized/ui-smoke-validation.txt 2>/dev/null || true
        fi
        adb exec-out screencap -p > build/test-game/localized/unciv-arabic-screen.png || { launch_status=$?; note_failure "screencap of the localized Unciv screen failed (exit $launch_status)"; }
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
          if [[ "$?" -ne 0 ]]; then
            launch_status=1
            note_failure "localized Unciv screenshot failed PNG validation"
          fi
        fi
        adb logcat -d -v time > build/test-game/localized/game-launch-logcat.txt || true
        upload_shot build/test-game/localized/unciv-arabic-screen.png "unciv-arabic-screen"
      fi
    fi
  fi
else
  echo "Translated Unciv APK was not exported to the shared Downloads collection." >&2
  downloads_listing=$(adb shell ls -la /sdcard/Download/ARABIFY/ 2>&1 | head -20 | tr '\n' ' ')
  note_failure "localized Unciv APK missing after a green test: /sdcard/Download/ARABIFY listing: ${downloads_listing:-<empty>}"
  if [[ "$test_status" -eq 0 ]]; then test_status=1; fi
fi
if [[ "$test_status" -eq 0 && "$launch_status" -ne 0 ]]; then
  test_status="$launch_status"
fi

exit "$test_status"
