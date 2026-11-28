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

# The pinned game is armeabi-v7a-only (Unity 2019 Mono). Every x86_64
# emulator image available to CI rejects 32-bit ARM (verified: NO_MATCHING_ABIS
# on API 35 run 46092000904 and API 33 run 46095596352), so the game is NOT
# installed; the test analyzes the APK from /sdcard instead. Install/launch
# of ARM32-only Mono games is permanently out of scope for x86_64 CI.
adb shell mkdir -p /sdcard/Android/data/com.omega.arabify/files
adb push build/test-game/carrunner.apk /sdcard/Android/data/com.omega.arabify/files/carrunner-original.apk || { push_rc=$?; note_failure "adb push of pinned Car Runner APK failed (exit $push_rc)"; exit $push_rc; }

test_status=0
run_connected_test com.omega.arabify.UnityMonoRealPlayerInstrumentedTest || test_status=$?
if [[ "$test_status" -eq 0 ]]; then
  echo "::notice::UnityMonoRealPlayerInstrumentedTest PASSED (real MIT Unity Mono game: detector=MONO, honest no-text refusal verified, ARABIFY re-sign verified)"
fi

OUTPUT_DIR="build/test-game/localized/pulled-evidence"
adb_with_reconnect pull /sdcard/Download/ARABIFY/carrunner-resigned.apk "$OUTPUT_DIR/carrunner-resigned.apk" || true

verify_status=0
if [[ "$test_status" -ne 0 ]]; then
  verify_status=1
elif [[ ! -s "$OUTPUT_DIR/carrunner-resigned.apk" ]]; then
  note_failure "re-signed Car Runner APK missing from Downloads/ARABIFY after a green test"
  verify_status=1
else
  APKSIGNER="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -n1)"
  if [[ ! -x "$APKSIGNER" ]] || ! "$APKSIGNER" verify --verbose "$OUTPUT_DIR/carrunner-resigned.apk"; then
    note_failure "apksigner verification failed for the re-signed Car Runner APK"
    verify_status=1
  fi
fi
if [[ "$test_status" -eq 0 && "$verify_status" -eq 0 ]]; then
  echo "::notice::REAL-MONO-ANALYSIS Car Runner (real Unity 2019 Mono player, MIT): detector=MONO, honest no-text refusal, ARABIFY re-sign apksigner-verified ($(stat -c%s "$OUTPUT_DIR/carrunner-resigned.apk") bytes). SCOPE: the game is armeabi-v7a-only and x86_64 CI images reject 32-bit ARM (proven on API 33 + 35), so install/launch of ARM32-only Mono games is out of scope for CI and stays device-only; the game has no in-scope loose text, so localization itself is honestly refused - scene MonoBehaviour text rewriting is the proposed coverage item"
fi
if [[ "$test_status" -eq 0 && "$verify_status" -ne 0 ]]; then
  test_status="$verify_status"
fi

exit "$test_status"
