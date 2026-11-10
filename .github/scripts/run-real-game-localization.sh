#!/usr/bin/env bash
set -uo pipefail
cd "$GITHUB_WORKSPACE"

mkdir -p build/test-game/localized
adb install build/test-game/minesweeper.apk

test_status=0
gradle connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.omega.arabify.RealGameLocalizationInstrumentedTest --stacktrace || test_status=$?

PRESERVED_DIR="/sdcard/Android/data/com.omega.arabify/files/real-game-test"
adb pull "$PRESERVED_DIR" build/test-game/localized/pulled-evidence || true

GENERATED_APK="build/test-game/localized/pulled-evidence/minesweeper-arabic.apk"
if [[ -s "$GENERATED_APK" ]]; then
  APKSIGNER="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -n1)"
  test -x "$APKSIGNER"
  "$APKSIGNER" verify --verbose "$GENERATED_APK"
  adb uninstall com.joeld.minesweeper || true
  adb install "$GENERATED_APK"
  adb shell monkey -p com.joeld.minesweeper 1
  sleep 8
  adb shell pidof com.joeld.minesweeper > build/test-game/localized/game-process.txt
  adb exec-out screencap -p > build/test-game/localized/minesweeper-arabic-screen.png
  adb logcat -d -v time > build/test-game/localized/game-launch-logcat.txt
else
  echo "Translated APK was not preserved by the instrumentation test." >&2
fi

exit "$test_status"
