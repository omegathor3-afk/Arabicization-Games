#!/usr/bin/env bash
# Second Mindustry pass: same locale-slot pipeline, but the instrumented test
# translates every string through the production OfflineArabicTranslator
# (ML Kit English-Arabic model) instead of the deterministic pseudo stand-in.
# Produces a real-translation screenshot for human review of label quality.
# Runs in the same emulator session as run-mindustry-slot.sh: the emulator is
# already booted and switched to ar-EG.
set -uo pipefail
cd "$GITHUB_WORKSPACE"

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
  echo "::notice::SCREENSHOT ${label}: upload failed :: ${err}"
}

mkdir -p build/test-game/localized/real

# The emulator should already be in ar-EG from the pseudo pass; if not (for
# example when this script runs alone), switch and reboot exactly once.
LOCALE_NOW="$(adb shell settings get system system_locales | tr -d '\r')"
if [[ "$LOCALE_NOW" != "ar-EG" ]]; then
  adb shell settings put system system_locales ar-EG || { note_failure "failed to switch emulator system locale to ar-EG"; exit 1; }
  timeout 30 adb reboot || { note_failure "adb reboot after locale switch failed"; exit 1; }
  timeout 180 adb wait-for-device || { note_failure "emulator did not return after Arabic locale reboot"; exit 1; }
  for attempt in $(seq 1 90); do
    BOOTED="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    [[ "$BOOTED" == "1" ]] && break
    sleep 2
  done
fi

# Real-translation pass of the same instrumented test.
gradle connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.omega.arabify.MindustryLocaleSlotInstrumentedTest \
  -Pandroid.testInstrumentationRunnerArguments.translationMode=real \
  --stacktrace
test_status=$?
if [[ "$test_status" -ne 0 ]]; then
  note_failure "real-translation Mindustry test failed (exit $test_status): ML Kit model download or translation unavailable on this emulator"
  exit "$test_status"
fi

adb pull /sdcard/Download/ARABIFY/mindustry-arabic-real.apk build/test-game/localized/real/mindustry-arabic-real.apk || true
adb pull /sdcard/Download/ARABIFY/mindustry-report-real.txt build/test-game/localized/real/mindustry-report-real.txt || true

GENERATED_APK="build/test-game/localized/real/mindustry-arabic-real.apk"
if [[ ! -s "$GENERATED_APK" ]]; then
  note_failure "real-translation Mindustry APK was not exported"
  exit 1
fi

# Surface the translated menu labels as a public annotation so translation
# quality is reviewable without artifact access.
if [[ -s build/test-game/localized/real/mindustry-report-real.txt ]]; then
  MENU_LINES="$(sed -n '/^menuTranslations:/,$p' build/test-game/localized/real/mindustry-report-real.txt | tail -n +2 | head -20)"
  while IFS= read -r line; do
    [[ -n "$line" ]] && echo "::notice::TRANSLATION ${line}"
  done <<< "$MENU_LINES"
fi

APKSIGNER="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -n1)"
if [[ ! -x "$APKSIGNER" ]] || ! "$APKSIGNER" verify --verbose "$GENERATED_APK"; then
  note_failure "apksigner verification failed for the real-translation Mindustry APK"
  exit 1
fi

adb uninstall io.anuke.mindustry || true
adb install "$GENERATED_APK" || { note_failure "install of real-translation Mindustry APK failed"; exit 1; }

LAUNCH_COMPONENT="io.anuke.mindustry/mindustry.android.AndroidLauncher"
adb shell cmd package compile -m speed -f io.anuke.mindustry > build/test-game/localized/real/dexopt.txt 2>&1 || true
sleep 5
adb shell settings put secure immersive_mode_confirmations confirmed >/dev/null 2>&1 || true
adb shell settings put global policy_control immersive.confirmations=confirmed >/dev/null 2>&1 || true

launch_ok=0
for attempt in 1 2 3; do
  adb shell am force-stop io.anuke.mindustry >/dev/null 2>&1 || true
  adb shell am start -W -n "$LAUNCH_COMPONENT" > build/test-game/localized/real/launch-attempt-"$attempt".txt 2>&1 || true
  for wait_attempt in $(seq 1 30); do
    game_pid="$(adb shell pidof io.anuke.mindustry 2>/dev/null | tr -d '\r')"
    foreground="$(adb shell dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity' | tail -n 1 | tr -d '\r')"
    if [[ -n "$game_pid" && "$foreground" == *"io.anuke.mindustry"* ]]; then
      launch_ok=1
      break
    fi
    sleep 2
  done
  [[ "$launch_ok" -eq 1 ]] && break
  sleep 3
done
if [[ "$launch_ok" -eq 0 ]]; then
  note_failure "real-translation Mindustry did not stay in the foreground"
  exit 1
fi

adb shell svc power stayon true >/dev/null 2>&1 || true
adb shell settings put system screen_off_timeout 1800000 >/dev/null 2>&1 || true
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
last_ok_shot="build/test-game/localized/real/mindustry-real-last-foreground.png"
last_ok_tick=0
for tick in $(seq 1 12); do
  sleep 5
  fg_now="$(adb shell dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity' | tail -n 1 | tr -d '\r')"
  if [[ "$fg_now" == *"io.anuke.mindustry"* ]]; then
    adb exec-out screencap -p > "$last_ok_shot.tmp" 2>/dev/null && [[ -s "$last_ok_shot.tmp" ]] && mv "$last_ok_shot.tmp" "$last_ok_shot" && last_ok_tick=$tick
  else
    echo "game left foreground at tick $tick" > build/test-game/localized/real/game-exit-tick.txt
    break
  fi
done
foreground="$(adb shell dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity' | tail -n 1 | tr -d '\r')"
game_pid="$(adb shell pidof io.anuke.mindustry 2>/dev/null | tr -d '\r')"
if [[ -z "$game_pid" || "$foreground" != *"io.anuke.mindustry"* ]]; then
  note_failure "real-translation Mindustry left the foreground before screenshot capture"
  [[ -s "$last_ok_shot" ]] && upload_shot "$last_ok_shot" "mindustry-real-last-foreground-frame-tick-${last_ok_tick}"
  exit 1
fi
adb exec-out screencap -p > build/test-game/localized/real/mindustry-real-translation-screen.png
if [[ ! -s build/test-game/localized/real/mindustry-real-translation-screen.png ]]; then
  note_failure "real-translation Mindustry screenshot missing or empty"
  exit 1
fi
final_foreground="$(adb shell dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity' | tail -n 1 | tr -d '\r')"
final_pid="$(adb shell pidof io.anuke.mindustry 2>/dev/null | tr -d '\r')"
if [[ -z "$final_pid" || "$final_foreground" != *"io.anuke.mindustry"* ]]; then
  note_failure "Mindustry exited during real-translation screenshot capture; refusing to publish"
  exit 1
fi
upload_shot build/test-game/localized/real/mindustry-real-translation-screen.png "mindustry-real-translation-screen"
exit 0
