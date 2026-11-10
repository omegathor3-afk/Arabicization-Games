#!/usr/bin/env bash
set -uo pipefail
cd "$GITHUB_WORKSPACE"

mkdir -p build/test-game/localized
adb install build/test-game/minesweeper.apk || exit $?

test_status=0
gradle connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.omega.arabify.RealGameLocalizationInstrumentedTest --stacktrace || test_status=$?

PRESERVED_DIR="/sdcard/Android/data/com.omega.arabify/files/real-game-test"
adb pull "$PRESERVED_DIR" build/test-game/localized/pulled-evidence || true

GENERATED_APK="build/test-game/localized/pulled-evidence/minesweeper-arabic.apk"
if [[ -s "$GENERATED_APK" ]]; then
  APKSIGNER="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -n1)"
  launch_status=0
  if [[ ! -x "$APKSIGNER" ]] || ! "$APKSIGNER" verify --verbose "$GENERATED_APK"; then
    launch_status=1
  fi
  adb uninstall com.joeld.minesweeper || true
  adb install "$GENERATED_APK" || launch_status=$?
  if [[ "$launch_status" -eq 0 ]]; then
    adb shell monkey -p com.joeld.minesweeper 1 || launch_status=$?
    sleep 8
    adb shell pidof com.joeld.minesweeper > build/test-game/localized/game-process.txt || launch_status=$?
    adb exec-out screencap -p > build/test-game/localized/minesweeper-arabic-screen.png || launch_status=$?
    adb logcat -d -v time > build/test-game/localized/game-launch-logcat.txt || true
    maestro_status=0
    if command -v maestro >/dev/null 2>&1; then
      mkdir -p build/maestro
      maestro --version | tee build/maestro/version.txt
      maestro test --format junit --output build/maestro/report.xml --test-output-dir build/maestro/results .maestro/flows || maestro_status=$?
    else
      echo "Maestro CLI is not available on PATH." >&2
      maestro_status=127
    fi
    if [[ "$test_status" -eq 0 && "$maestro_status" -ne 0 ]]; then
      test_status="$maestro_status"
    fi
  fi
  if [[ "$test_status" -eq 0 && "$launch_status" -ne 0 ]]; then
    test_status="$launch_status"
  fi
else
  echo "Translated APK was not preserved by the instrumentation test." >&2
  if [[ "$test_status" -eq 0 ]]; then test_status=1; fi
fi

exit "$test_status"
