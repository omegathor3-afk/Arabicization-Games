#!/usr/bin/env bash
set -uo pipefail
cd "$GITHUB_WORKSPACE"

mkdir -p build/test-game/localized
adb install build/test-game/minesweeper.apk || exit $?

test_status=0
gradle connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.omega.arabify.RealGameLocalizationInstrumentedTest --stacktrace || test_status=$?

# The instrumentation test writes evidence into app-private storage. Pull it
# through run-as rather than /sdcard/Android/data, which adb shell cannot
# reliably traverse on recent Android releases.
EVIDENCE_DIR="files/real-game-test"
OUTPUT_DIR="build/test-game/localized/pulled-evidence"
mkdir -p "$OUTPUT_DIR"
if adb shell run-as com.omega.arabify test -s "$EVIDENCE_DIR/minesweeper-arabic.apk"; then
  adb exec-out run-as com.omega.arabify cat "$EVIDENCE_DIR/minesweeper-arabic.apk" > "$OUTPUT_DIR/minesweeper-arabic.apk" || true
  adb exec-out run-as com.omega.arabify cat "$EVIDENCE_DIR/report.txt" > "$OUTPUT_DIR/report.txt" || true
fi

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
      adb shell monkey -p com.joeld.minesweeper 1 || launch_status=$?
      sleep 8
      adb shell pidof com.joeld.minesweeper > build/test-game/localized/game-process.txt || launch_status=$?
      adb exec-out screencap -p > build/test-game/localized/minesweeper-arabic-screen.png || launch_status=$?
      adb logcat -d -v time > build/test-game/localized/game-launch-logcat.txt || true
    fi
  fi
else
  echo "Translated APK was not preserved by the instrumentation test." >&2
  if [[ "$test_status" -eq 0 ]]; then test_status=1; fi
fi
if [[ "$test_status" -eq 0 && "$launch_status" -ne 0 ]]; then
  test_status="$launch_status"
fi

# Launch ARABIFY using its explicit manifest component; package-only monkey
# discovery returned no launcher activity on the API 35 CI image.
ui_status=0
mkdir -p build/maestro
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
fi
if [[ "$test_status" -eq 0 && "$ui_status" -ne 0 ]]; then
  test_status="$ui_status"
fi

exit "$test_status"
