#!/usr/bin/env bash
set -uo pipefail
cd "$GITHUB_WORKSPACE"

mkdir -p build/test-game/localized
adb install build/test-game/minesweeper.apk || exit $?

test_status=0
gradle connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.omega.arabify.RealGameLocalizationInstrumentedTest --stacktrace || test_status=$?

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
        adb reboot || launch_status=$?
        adb wait-for-device
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
      if [[ "$launch_status" -eq 0 ]]; then
        adb shell monkey -p com.joeld.minesweeper 1 || launch_status=$?
        sleep 8
        adb shell pidof com.joeld.minesweeper > build/test-game/localized/game-process.txt || launch_status=$?
        adb shell uiautomator dump /sdcard/minesweeper-window.xml >/dev/null 2>&1 || launch_status=$?
        adb shell cat /sdcard/minesweeper-window.xml > build/test-game/localized/minesweeper-window.xml || launch_status=$?
        if ! grep -q "لعبة جديدة" build/test-game/localized/minesweeper-window.xml; then
          echo "Arabic UI text 'لعبة جديدة' was not visible after setting system locale to ar-EG." >&2
          launch_status=1
        fi
        adb exec-out screencap -p > build/test-game/localized/minesweeper-arabic-screen.png || launch_status=$?
        adb logcat -d -v time > build/test-game/localized/game-launch-logcat.txt || true
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
  fi
fi
if [[ "$test_status" -eq 0 && "$ui_status" -ne 0 ]]; then
  test_status="$ui_status"
fi

exit "$test_status"
