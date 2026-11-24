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
adb install build/test-game/mindustry.apk || { install_rc=$?; note_failure "adb install of pinned Mindustry APK failed (exit $install_rc)"; exit $install_rc; }

test_status=0
run_connected_test com.omega.arabify.MindustryLocaleSlotInstrumentedTest || test_status=$?

OUTPUT_DIR="build/test-game/localized/pulled-evidence"
mkdir -p "$OUTPUT_DIR"
adb pull /sdcard/Download/ARABIFY/mindustry-arabic.apk "$OUTPUT_DIR/mindustry-arabic.apk" || true
adb pull /sdcard/Download/ARABIFY/mindustry-report.txt "$OUTPUT_DIR/mindustry-report.txt" || true

GENERATED_APK="$OUTPUT_DIR/mindustry-arabic.apk"
launch_status=0
if [[ -s "$GENERATED_APK" ]]; then
  APKSIGNER="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -n1)"
  if [[ ! -x "$APKSIGNER" ]] || ! "$APKSIGNER" verify --verbose "$GENERATED_APK"; then
    launch_status=1
    note_failure "apksigner verification failed for the generated localized Mindustry APK"
  else
    adb uninstall io.anuke.mindustry || true
    adb install "$GENERATED_APK" || { launch_status=$?; note_failure "install of generated localized Mindustry APK failed (exit $launch_status)"; }
    if [[ "$launch_status" -eq 0 ]]; then
      # Mindustry resolves the locale from the Android system locale through
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
        adb shell monkey -p io.anuke.mindustry 1 || { launch_status=$?; note_failure "monkey launch of the localized game failed"; }
        sleep 12
        adb shell pidof io.anuke.mindustry > build/test-game/localized/game-process.txt || { launch_status=$?; note_failure "game process not running after launch (pidof empty)"; }
        HIERARCHY_DEVICE_PATH="/data/local/tmp/arabify-mindustry-window.xml"
        HIERARCHY_HOST_PATH="build/test-game/localized/mindustry-window.xml"
        # Mindustry either opens its first-run language picker (listing
        # العربية from the new bundle_ar) or boots its menu in Arabic. The
        # game can take a while to build its first screen after install;
        # poll the hierarchy for Arabic for up to 90 seconds instead of
        # taking a single early snapshot.
        hierarchy_ready=0
        arabic_found=0
        for attempt in $(seq 1 18); do
          adb shell rm -f "$HIERARCHY_DEVICE_PATH" || true
          adb shell uiautomator dump --compressed "$HIERARCHY_DEVICE_PATH" >/tmp/arabify-uiautomator-dump.log 2>&1 || true
          if adb shell test -s "$HIERARCHY_DEVICE_PATH"; then
            if adb pull "$HIERARCHY_DEVICE_PATH" "$HIERARCHY_HOST_PATH" >/dev/null 2>&1 && [[ -s "$HIERARCHY_HOST_PATH" ]]; then
              hierarchy_ready=1
              if grep -qP '[\x{0600}-\x{06FF}]' "$HIERARCHY_HOST_PATH"; then
                arabic_found=1
                break
              fi
            fi
          fi
          sleep 5
        done
        if [[ "$hierarchy_ready" -eq 1 ]]; then
          if [[ "$arabic_found" -eq 0 ]]; then
            echo "No Arabic text was visible in the accessibility hierarchy after 90s of polling." >&2
            note_failure "no Arabic text visible on the Mindustry screen after 90s of hierarchy polling"
            launch_status=1
          else
            echo "Accessibility hierarchy captured; Arabic text is visible on the Mindustry screen." > build/test-game/localized/ui-smoke-validation.txt
          fi
        else
          echo "::notice::UIAutomator exposed no hierarchy; relying on screenshot + process checks (not counted as failure)"
          echo "WARNING: Android UIAutomator did not expose a hierarchy for the launched game. The instrumentation test verified the slot content, APK signature and install; screenshot and foreground-process checks remain required." > build/test-game/localized/ui-smoke-validation.txt
          cat /tmp/arabify-uiautomator-dump.log >> build/test-game/localized/ui-smoke-validation.txt 2>/dev/null || true
        fi
        adb exec-out screencap -p > build/test-game/localized/mindustry-arabic-screen.png || launch_status=$?
        if [[ ! -s build/test-game/localized/mindustry-arabic-screen.png ]]; then
          echo "Localized game screenshot is missing or empty." >&2
          note_failure "localized Mindustry screenshot missing or empty after launch"
          launch_status=1
        else
          python3 - <<'PY'
from pathlib import Path
import struct

path = Path("build/test-game/localized/mindustry-arabic-screen.png")
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
  echo "Translated Mindustry APK was not exported to the shared Downloads collection." >&2
  if [[ "$test_status" -eq 0 ]]; then test_status=1; fi
fi
if [[ "$test_status" -eq 0 && "$launch_status" -ne 0 ]]; then
  test_status="$launch_status"
fi

exit "$test_status"
