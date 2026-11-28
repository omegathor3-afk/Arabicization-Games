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

# Keep screenshots inside the workflow artifact. Never upload screenshot bytes
# to third-party hosts or embed them in public log annotations (repo privacy
# guard: .github/scripts/test-screenshot-privacy.py).
retain_shot() {
  local file="$1" label="$2"
  if [[ ! -s "$file" ]]; then
    echo "::notice::SCREENSHOT ${label}: no file captured"
    return 0
  fi
  echo "::notice::SCREENSHOT ${label}: retained only in GitHub Actions artifact (${file})"
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

adb_with_reconnect() {
  local tries=0
  while [[ $tries -lt 24 ]]; do
    if adb "$@"; then return 0; fi
    tries=$((tries+1))
    adb reconnect 2>/dev/null || adb reconnect offline 2>/dev/null || true
    sleep 5
  done
  return 1
}

mkdir -p build/test-game/localized/pulled-evidence

# The pinned game is armeabi-v7a-only (Unity 2019 Mono). x86_64 emulators on
# API 30+ google_apis images carry ARM binary translation; if this install
# fails with NO_MATCHING_ABIS the whole leg is environmental and says so.
if ! install_out=$(adb install build/test-game/carrunner.apk 2>&1); then
  if echo "$install_out" | grep -q 'NO_MATCHING_ABIS'; then
    note_failure "ENVIRONMENTAL: the x86_64 emulator rejected the ARM-only Car Runner APK (NO_MATCHING_ABIS); the real-player leg needs an ARM-capable image - no product defect proven"
  else
    note_failure "adb install of pinned Car Runner APK failed: $(echo "$install_out" | tail -2 | tr '\n' ' ')"
  fi
  exit 1
fi

test_status=0
run_connected_test com.omega.arabify.UnityMonoRealPlayerInstrumentedTest || test_status=$?
if [[ "$test_status" -eq 0 ]]; then
  echo "::notice::UnityMonoRealPlayerInstrumentedTest PASSED (real MIT Unity Mono game: detector=MONO, honest no-text refusal verified, ARABIFY re-sign verified)"
fi

OUTPUT_DIR="build/test-game/localized/pulled-evidence"
adb_with_reconnect pull /sdcard/Download/ARABIFY/carrunner-resigned.apk "$OUTPUT_DIR/carrunner-resigned.apk" || true

# Install/launch leg: the ARABIFY-signed copy of the real Mono game must
# install through the normal path and come up (repackaging-route safety on a
# real Unity Mono player). Launch hardening mirrors the Pixel Wheels leg:
# process poll, explicit activity start fallback, crash logcat on failure.
launch_status=0
if [[ "$test_status" -ne 0 ]]; then
  launch_status=1
elif [[ ! -s "$OUTPUT_DIR/carrunner-resigned.apk" ]]; then
  note_failure "re-signed Car Runner APK missing from Downloads/ARABIFY after a green test"
  launch_status=1
else
  APKSIGNER="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -n1)"
  if [[ ! -x "$APKSIGNER" ]] || ! "$APKSIGNER" verify --verbose "$OUTPUT_DIR/carrunner-resigned.apk"; then
    note_failure "apksigner verification failed for the re-signed Car Runner APK"
    launch_status=1
  fi
fi
if [[ "$launch_status" -eq 0 ]]; then
  adb_with_reconnect uninstall com.PotneAEiI.CarRunner || true
  if ! install_out=$(adb_with_reconnect install "$OUTPUT_DIR/carrunner-resigned.apk" 2>&1); then
    if echo "$install_out" | grep -q 'NO_MATCHING_ABIS'; then
      note_failure "ENVIRONMENTAL: the x86_64 emulator rejected the ARM-only re-signed APK (NO_MATCHING_ABIS); install/launch evidence needs an ARM-capable image"
    else
      note_failure "install of the ARABIFY-signed Car Runner APK failed: $(echo "$install_out" | tail -2 | tr '\n' ' ')"
    fi
    launch_status=1
  fi
fi
if [[ "$launch_status" -eq 0 ]]; then
  adb shell settings put secure immersive_mode_confirmations confirmed >/dev/null 2>&1 || true
  adb shell settings put global policy_control immersive.confirmations=confirmed >/dev/null 2>&1 || true
  adb logcat -c || true
  adb shell monkey -p com.PotneAEiI.CarRunner 1 || { launch_status=1; note_failure "monkey launch of the re-signed Car Runner failed"; }
fi
if [[ "$launch_status" -eq 0 ]]; then
  process_alive=0
  for attempt in $(seq 1 9); do
    sleep 10
    if adb shell pidof com.PotneAEiI.CarRunner > build/test-game/localized/game-process.txt 2>/dev/null && [[ -s build/test-game/localized/game-process.txt ]]; then
      process_alive=1
      break
    fi
  done
  if [[ "$process_alive" -eq 0 ]]; then
    adb shell am start -n com.PotneAEiI.CarRunner/com.unity3d.player.UnityPlayerActivity || true
    for attempt in $(seq 1 6); do
      sleep 10
      if adb shell pidof com.PotneAEiI.CarRunner > build/test-game/localized/game-process.txt 2>/dev/null && [[ -s build/test-game/localized/game-process.txt ]]; then
        process_alive=1
        break
      fi
    done
  fi
  if [[ "$process_alive" -eq 0 ]]; then
    adb logcat -d -v time > build/test-game/localized/game-launch-logcat.txt || true
    adb logcat -b crash -d -v time > build/test-game/localized/game-crash-logcat.txt || true
    fatal_excerpt=$(grep -m1 -A12 'FATAL EXCEPTION\|Fatal signal\|AndroidRuntime' build/test-game/localized/game-launch-logcat.txt 2>/dev/null | head -13 | tr '\n' ' ' | cut -c1-800)
    note_failure "re-signed Car Runner process not running after monkey + explicit start. Fatal excerpt: ${fatal_excerpt:-<no FATAL EXCEPTION in logcat; see game-launch-logcat.txt in the workflow artifact>}"
    launch_status=1
  fi
fi
if [[ "$launch_status" -eq 0 ]]; then
  sleep 10
  adb exec-out screencap -p > build/test-game/localized/carrunner-launched-screen.png || true
  if [[ -s build/test-game/localized/carrunner-launched-screen.png ]]; then
    python3 - <<'PY'
from pathlib import Path
import struct
path = Path("build/test-game/localized/carrunner-launched-screen.png")
data = path.read_bytes()
if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
    raise SystemExit("Car Runner launch screenshot is not a valid PNG.")
width, height = struct.unpack(">II", data[16:24])
print(f"Car Runner launch screenshot verified: {width}x{height}, {len(data)} bytes.")
PY
    [[ "$?" -ne 0 ]] && { launch_status=1; note_failure "Car Runner launch screenshot failed PNG validation"; }
  else
    note_failure "Car Runner launch screenshot missing or empty"
    launch_status=1
  fi
  retain_shot build/test-game/localized/carrunner-launched-screen.png "carrunner-launched-screen"
fi
if [[ "$test_status" -eq 0 && "$launch_status" -eq 0 ]]; then
  echo "::notice::REAL-MONO-LAUNCH ARABIFY-signed Car Runner (real Unity 2019 Mono player, MIT) installed and launched on the emulator (process alive, valid frame retained). SCOPE: the game has no in-scope loose text, so localization itself was honestly refused; install/launch safety of the repackaging route on a real Mono player is what this leg proves"
fi
if [[ "$test_status" -eq 0 && "$launch_status" -ne 0 ]]; then
  test_status="$launch_status"
fi

exit "$test_status"
