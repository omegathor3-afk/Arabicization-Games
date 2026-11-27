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

# The long connected-test runs can drop the adb connection; reconnect first.
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
adb install build/test-game/pixelwheels.apk || { install_rc=$?; note_failure "adb install of pinned Pixel Wheels APK failed (exit $install_rc)"; exit $install_rc; }

test_status=0

# Pseudo leg: deterministic, and it carries the planner-dispatch assertion on
# the REAL game (the gettext wrapper must win with no fallback) plus the
# cross-font merge path that synthetic fixtures honestly cannot exercise.
run_connected_test com.omega.arabify.PixelWheelsGettextInstrumentedTest || test_status=$?
if [[ "$test_status" -eq 0 ]]; then
  echo "::notice::PixelWheelsGettextInstrumentedTest PASSED pseudo (real F-Droid game: gettext wrapper selected by planner dispatch with no fallback, cross-font merge exercised, ar.po shaped + nplurals=6, fr.po byte-identical, output signed+verified)"
fi

# Real leg: production OfflineArabicTranslator (ML Kit) plus the round-trip
# audit, same evidence policy as Unciv/Mindustry (flags are evidence, never
# a rewrite).
real_status=0
run_connected_test com.omega.arabify.PixelWheelsGettextInstrumentedTest \
  -Pandroid.testInstrumentationRunnerArguments.translationMode=real || real_status=$?
if [[ "$real_status" -eq 0 ]]; then
  echo "::notice::PixelWheelsGettextInstrumentedTest PASSED real (production ML Kit translation + round-trip audit on the real game)"
else
  test_status="$real_status"
fi

OUTPUT_DIR="build/test-game/localized/pulled-evidence"
adb_with_reconnect pull /sdcard/Download/ARABIFY/pixelwheels-arabic.apk "$OUTPUT_DIR/pixelwheels-arabic.apk" || true
adb_with_reconnect pull /sdcard/Download/ARABIFY/pixelwheels-report.txt "$OUTPUT_DIR/pixelwheels-report.txt" || true
adb_with_reconnect pull /sdcard/Download/ARABIFY/pixelwheels-arabic-real.apk "$OUTPUT_DIR/pixelwheels-arabic-real.apk" || true
adb_with_reconnect pull /sdcard/Download/ARABIFY/pixelwheels-report-real.txt "$OUTPUT_DIR/pixelwheels-report-real.txt" || true
adb_with_reconnect pull /sdcard/Download/ARABIFY/pixelwheels-roundtrip-real.txt "$OUTPUT_DIR/pixelwheels-roundtrip-real.txt" || true

for leg in "" "-real"; do
  if [[ -s "$OUTPUT_DIR/pixelwheels-report${leg}.txt" ]]; then
    echo "::notice::PIXELWHEELS-REPORT${leg} $(tr '\n' ' ' < "$OUTPUT_DIR/pixelwheels-report${leg}.txt" | head -c 900)"
  fi
done
if [[ -s "$OUTPUT_DIR/pixelwheels-roundtrip-real.txt" ]]; then
  echo "::notice::ROUNDTRIP-AUDIT $(head -2 "$OUTPUT_DIR/pixelwheels-roundtrip-real.txt" | tr '\n' ' ')"
  tail -n +3 "$OUTPUT_DIR/pixelwheels-roundtrip-real.txt" | head -5 | while IFS= read -r sample; do
    [[ -n "$sample" ]] && echo "::notice::ROUNDTRIP-FLAGGED ${sample:0:250}"
  done
fi

for leg in "" "-real"; do
  GENERATED_APK="$OUTPUT_DIR/pixelwheels-arabic${leg}.apk"
  if [[ -s "$GENERATED_APK" ]]; then
    APKSIGNER="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -n1)"
    if [[ ! -x "$APKSIGNER" ]] || ! "$APKSIGNER" verify --verbose "$GENERATED_APK"; then
      note_failure "apksigner verification failed for the generated Pixel Wheels APK (mode${leg:- pseudo})"
      test_status=1
    else
      echo "::notice::APK-VERIFIED pixelwheels-arabic${leg}.apk ($(stat -c%s "$GENERATED_APK") bytes, ARABIFY-signed)"
    fi
  else
    note_failure "generated Pixel Wheels APK missing from Downloads/ARABIFY (mode${leg:- pseudo})"
    test_status=1
  fi
done

# Honest scope note: this job proves build/sign/verify + dispatch + merge on
# the real game. Booting Pixel Wheels in Arabic on-screen (its languages.xml
# picker wiring) is NOT covered yet and is tracked in the handoff.
echo "::notice::SCOPE game-launch Arabic activation for Pixel Wheels is not covered by this job yet; build/sign/dispatch/merge evidence only"

exit "$test_status"
