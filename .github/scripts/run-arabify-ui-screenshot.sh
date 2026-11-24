#!/usr/bin/env bash
# Screenshot the Arabify app's own UI on the emulator (design review evidence).
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

mkdir -p build/arabify-ui
APK="app/build/outputs/apk/debug/app-debug.apk"
[[ -s "$APK" ]] || { note_failure "app-debug.apk missing"; exit 1; }
adb install -r "$APK" || { note_failure "adb install of Arabify failed"; exit 1; }

# Arabic UI: switch the emulator to ar-EG so the app renders its Arabic strings
# with RTL layout, reboot once, then launch.
adb shell settings put system system_locales ar-EG || true
timeout 30 adb reboot || true
timeout 180 adb wait-for-device || { note_failure "emulator did not return after locale switch"; exit 1; }
for attempt in $(seq 1 90); do
  BOOTED="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  [[ "$BOOTED" == "1" ]] && break
  sleep 2
done

adb shell svc power stayon true >/dev/null 2>&1 || true
adb shell settings put system screen_off_timeout 1800000 >/dev/null 2>&1 || true
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb shell am force-stop com.omega.arabify >/dev/null 2>&1 || true
adb shell am start -W -n com.omega.arabify/.MainActivity > build/arabify-ui/launch.txt 2>&1 || { note_failure "Arabify launch failed: $(tail -c 300 build/arabify-ui/launch.txt)"; exit 1; }

status=0
for attempt in $(seq 1 30); do
  fg="$(adb shell dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity' | tail -n 1 | tr -d '\r')"
  [[ "$fg" == *"com.omega.arabify"* ]] && break
  sleep 2
done
fg="$(adb shell dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity' | tail -n 1 | tr -d '\r')"
if [[ "$fg" != *"com.omega.arabify"* ]]; then
  note_failure "Arabify did not come to the foreground: $fg"
  exit 1
fi

# Compose needs a beat to lay out and render the first frames.
sleep 12
adb exec-out screencap -p > build/arabify-ui/arabify-ui-top.png || status=1
[[ -s build/arabify-ui/arabify-ui-top.png ]] || { note_failure "top screenshot missing"; status=1; }

# Scroll down for the second section of the home screen.
adb shell input swipe 540 1700 540 500 700 || true
sleep 3
adb exec-out screencap -p > build/arabify-ui/arabify-ui-scrolled.png || status=1
[[ -s build/arabify-ui/arabify-ui-scrolled.png ]] || { note_failure "scrolled screenshot missing"; status=1; }

upload_shot build/arabify-ui/arabify-ui-top.png "arabify-ui-top"
upload_shot build/arabify-ui/arabify-ui-scrolled.png "arabify-ui-scrolled"
exit "$status"
