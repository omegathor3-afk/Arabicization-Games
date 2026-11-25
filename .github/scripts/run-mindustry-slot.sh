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
  url=$(curl --fail --silent --show-error --max-time 150 -F "file=@${file}" https://file.io 2>&1 | python3 -c 'import json,sys; print(json.load(sys.stdin).get("link",""))' 2>/dev/null) && [[ -n "$url" ]] && { echo "::notice::SCREENSHOT ${label}: ${url} (single-download link - fetch once)"; return 0; } || err="${err} | file.io: $(echo "$url" | tail -c 200)"
  url=$(curl --fail --silent --show-error --max-time 150 --upload-file "$file" "https://transfer.sh/$(basename "$file")" 2>&1) && { echo "::notice::SCREENSHOT ${label}: ${url}"; return 0; } || err="${err} | transfer.sh: $(echo "$url" | tail -c 200)"
  # Last resort: external file hosts are unreachable from GitHub runners
  # (observed 2026-11-24: 0x0.st 503, catbox 412, temp.sh 500, file.io empty,
  # transfer.sh TCP timeout). Embed a downscaled JPEG as base64 inside the
  # annotation itself - annotations are public and need no egress.
  local small="build/.upload-shot-small.jpg"
  if command -v ffmpeg >/dev/null 2>&1; then
    local scale b64
    for scale in 720 540 400; do
      ffmpeg -y -loglevel error -i "$file" -vf "scale=${scale}:-1" -q:v 5 "$small" || continue
      b64=$(base64 -w0 "$small" 2>/dev/null || base64 "$small" | tr -d '\n')
      if [[ -n "$b64" && ${#b64} -le 60000 ]]; then
        echo "::notice::SCREENSHOT-B64 ${label} (JPEG ${scale}px, base64): ${b64}"
        return 0
      fi
    done
  fi
  echo "::notice::SCREENSHOT ${label}: upload failed :: ${err}"
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
        launch_ok=0
        # The pinned F-Droid manifest declares this exact exported MAIN/LAUNCHER
        # activity. monkey -p reported "No activities found" on API 35 even
        # though the APK installed, so launch the manifest component directly.
        LAUNCH_COMPONENT="io.anuke.mindustry/mindustry.android.AndroidLauncher"
        # Android shows a "Viewing full screen" confirmation over a fullscreen
        # game on first launch; it takes window focus and Mindustry stops. The
        # 11:29 failure frame proved it. Pre-confirm it before launching.
        # Hypothesis from the game's last_log + logcat: right before Mindustry
        # pauses and exits, Android logs "ApplicationInfo updating for
        # io.anuke.mindustry" (post-install dexopt refreshing the package),
        # and Mindustry quits on that. Finish the compile BEFORE launching.
        adb shell cmd package compile -m speed -f io.anuke.mindustry > build/test-game/localized/dexopt.txt 2>&1 || true
        sleep 5
        adb shell settings put secure immersive_mode_confirmations confirmed >/dev/null 2>&1 || true
        adb shell settings put global policy_control immersive.confirmations=confirmed >/dev/null 2>&1 || true
        for attempt in 1 2 3; do
          adb shell am force-stop io.anuke.mindustry >/dev/null 2>&1 || true
          start_output="$(adb shell am start -W -n "$LAUNCH_COMPONENT" 2>&1 || true)"
          printf '%s\n' "$start_output" > build/test-game/localized/launch-attempt-"$attempt".txt
          for wait_attempt in $(seq 1 30); do
            game_pid="$(adb shell pidof io.anuke.mindustry 2>/dev/null | tr -d '\r')"
            foreground="$(adb shell dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity' | tail -n 1 | tr -d '\r')"
            if [[ -n "$game_pid" && "$foreground" == *"io.anuke.mindustry"* ]]; then
              launch_ok=1
              printf 'pid=%s\nforeground=%s\n' "$game_pid" "$foreground" > build/test-game/localized/game-process.txt
              break
            fi
            sleep 2
          done
          [[ "$launch_ok" -eq 1 ]] && break
          sleep 3
        done
        if [[ "$launch_ok" -eq 0 ]]; then
          launch_status=1
          activity_dump="$(adb shell dumpsys package io.anuke.mindustry 2>/dev/null | grep -E -A2 -B2 'mindustry.android.AndroidLauncher|android.intent.action.MAIN|android.intent.category.LAUNCHER' | head -30 | tr '\n' ' ' | cut -c1-1200)"
          logcat_tail="$(adb logcat -d -t 700 2>/dev/null | grep -iE 'mindustry|AndroidRuntime|FATAL|libc|ActivityTaskManager' | tail -30 | tr '\n' ' ' | cut -c1-1200)"
          note_failure "Mindustry did not remain in the foreground after explicit activity launch; component=$LAUNCH_COMPONENT :: activities=$activity_dump :: logcat=$logcat_tail"
        else
          # A live process alone is insufficient: only capture evidence while
          # Android reports the game package as the resumed foreground app.
          # The first captured frame (8s) was Mindustry's asset-loading screen,
          # not the menu. Give the game time to reach its first interactive screen.
          # Keep the screen on (a screen timeout can destroy the activity; Mindustry
          # then calls System.exit(0)) and capture frames while the game is alive,
          # so a late exit still leaves genuine last-foreground evidence.
          adb shell svc power stayon true >/dev/null 2>&1 || true
          adb shell settings put system screen_off_timeout 1800000 >/dev/null 2>&1 || true
          adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
          last_ok_shot="build/test-game/localized/mindustry-last-foreground.png"
          tick_capture() {
            last_ok_tick=0
            for tick in $(seq 1 12); do
              sleep 5
              fg_now="$(adb shell dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity' | tail -n 1 | tr -d '\r')"
              if [[ "$fg_now" == *"io.anuke.mindustry"* ]]; then
                adb exec-out screencap -p > "$last_ok_shot.tmp" 2>/dev/null && [[ -s "$last_ok_shot.tmp" ]] && mv "$last_ok_shot.tmp" "$last_ok_shot" && last_ok_tick=$tick
              else
                echo "game left foreground at tick $tick (~$((tick*5))s)" > build/test-game/localized/game-exit-tick.txt
                return 1
              fi
            done
            return 0
          }
          tick_capture || true
          foreground="$(adb shell dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity' | tail -n 1 | tr -d '\r')"
          game_pid="$(adb shell pidof io.anuke.mindustry 2>/dev/null | tr -d '\r')"
          if [[ -z "$game_pid" || "$foreground" != *"io.anuke.mindustry"* ]]; then
            adb logcat -d -v time > build/test-game/localized/game-exit-logcat.txt || true
            adb logcat -d -b crash -v time >> build/test-game/localized/game-exit-logcat.txt 2>/dev/null || true
            game_files="$( (adb shell 'ls -R /sdcard/Android/data/io.anuke.mindustry/files 2>/dev/null | head -40'; for f in $(adb shell 'ls /sdcard/Android/data/io.anuke.mindustry/files/crashes/* /sdcard/Android/data/io.anuke.mindustry/files/last_log.txt /sdcard/Android/data/io.anuke.mindustry/files/logs/* 2>/dev/null' | tr -d '\r' | head -3); do echo "== $f"; adb shell "tail -n 40 $f" 2>/dev/null; done) | cut -c1-200 | tr '\n' '|' | cut -c1-4500)"
            echo "::error::MINDUSTRY-OWN-FILES ${game_files:-none}"
            game_lines="$(grep -E '/anuke\.mindustry\(|/Mindustry\(|io\.anuke\.mindustry' build/test-game/localized/game-exit-logcat.txt | grep -vE 'ApkAssets|ImeTracker|InputManager|nativeloader|ActivityTaskManager|WindowManager' | tail -45 | cut -c1-210 | tr '\n' '|' | cut -c1-4200)"
            sys_lines="$(grep -E 'FATAL EXCEPTION IN SYSTEM PROCESS|lowmemorykiller|Low on memory|am_kill|am_proc_died|ANR in|has died|Killing [0-9]+:io' build/test-game/localized/game-exit-logcat.txt | tail -8 | cut -c1-200 | tr '\n' '|')"
            logcat_tail="GAME[${game_lines}] SYSTEM[${sys_lines}]"
            if [[ -s "build/test-game/localized/mindustry-last-foreground.png" ]]; then
              upload_shot build/test-game/localized/mindustry-last-foreground.png "mindustry-last-foreground-frame-tick-${last_ok_tick:-0}"
            fi
            if [[ "$sys_lines" == *"FATAL"* || "$sys_lines" == *"lowmemorykiller"* || "$sys_lines" == *"ANR in"* || "$game_lines" == *"FATAL EXCEPTION"* ]]; then
              # A crash or system kill is a real defect signal; never retry past it.
              launch_status=1
              note_failure "Mindustry CRASHED or was killed before screenshot capture ($(cat build/test-game/localized/game-exit-tick.txt 2>/dev/null)); pid=$game_pid foreground=$foreground :: logcat: ${logcat_tail:-unavailable}"
            else
              # Documented startup race: Android refreshes package info
              # ("ApplicationInfo updating") right after launch and Mindustry
              # quits on the pause (System.exit status 0, no crash). The
              # 16:12 run proved it. Relaunch once; the refresh is done by then.
              echo "::warning::Mindustry exited during the startup frame loop without a crash signature (documented package-refresh race); relaunching once before failing :: logcat: ${logcat_tail:-unavailable}"
              adb shell am force-stop io.anuke.mindustry >/dev/null 2>&1 || true
              adb shell am start -W -n "$LAUNCH_COMPONENT" >/dev/null 2>&1 || true
              for relaunch_wait in $(seq 1 30); do
                game_pid="$(adb shell pidof io.anuke.mindustry 2>/dev/null | tr -d '\r')"
                foreground="$(adb shell dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity' | tail -n 1 | tr -d '\r')"
                [[ -n "$game_pid" && "$foreground" == *"io.anuke.mindustry"* ]] && break
                sleep 2
              done
              rm -f build/test-game/localized/game-exit-tick.txt
              tick_capture || true
              foreground="$(adb shell dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity' | tail -n 1 | tr -d '\r')"
              game_pid="$(adb shell pidof io.anuke.mindustry 2>/dev/null | tr -d '\r')"
              if [[ -z "$game_pid" || "$foreground" != *"io.anuke.mindustry"* ]]; then
                launch_status=1
                note_failure "Mindustry left the foreground ($(cat build/test-game/localized/game-exit-tick.txt 2>/dev/null)) before screenshot capture even after one relaunch; pid=$game_pid foreground=$foreground :: logcat: ${logcat_tail:-unavailable}"
              fi
            fi
          fi
          if [[ "$launch_status" -eq 0 ]]; then
            printf 'pid=%s\nforeground=%s\n' "$game_pid" "$foreground" > build/test-game/localized/game-process.txt
            echo "foreground_package=io.anuke.mindustry" > build/test-game/localized/ui-smoke-validation.txt
            echo "foreground_activity=$foreground" >> build/test-game/localized/ui-smoke-validation.txt
            echo "uiautomator_note=libGDX renders game text in OpenGL; Android accessibility text is not evidence of in-game Arabic." >> build/test-game/localized/ui-smoke-validation.txt
            echo "visual_arabic_check=required; this workflow only proves the screenshot belongs to the foreground game." >> build/test-game/localized/ui-smoke-validation.txt
            HIERARCHY_DEVICE_PATH="/data/local/tmp/arabify-mindustry-window.xml"
            HIERARCHY_HOST_PATH="build/test-game/localized/mindustry-window.xml"
            adb shell uiautomator dump --compressed "$HIERARCHY_DEVICE_PATH" >/tmp/arabify-uiautomator-dump.log 2>&1 || true
            adb pull "$HIERARCHY_DEVICE_PATH" "$HIERARCHY_HOST_PATH" >/dev/null 2>&1 || true
            adb exec-out screencap -p > build/test-game/localized/mindustry-foreground-screen.png || launch_status=$?
            if [[ ! -s build/test-game/localized/mindustry-foreground-screen.png ]]; then
              note_failure "localized Mindustry screenshot missing or empty after foreground verification"
              launch_status=1
            elif python3 - <<'PY'
from pathlib import Path
import struct

path = Path("build/test-game/localized/mindustry-foreground-screen.png")
data = path.read_bytes()
if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
    raise SystemExit("Localized Mindustry screenshot is not a valid PNG.")
width, height = struct.unpack(">II", data[16:24])
if width < 320 or height < 480:
    raise SystemExit(f"Localized Mindustry screenshot dimensions are too small: {width}x{height}.")
print(f"Foreground Mindustry screenshot verified: {width}x{height}, {len(data)} bytes.")
PY
            then
              upload_shot build/test-game/localized/mindustry-foreground-screen.png "mindustry-foreground-screen"
              final_foreground="$(adb shell dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity' | tail -n 1 | tr -d '\r')"
              final_pid="$(adb shell pidof io.anuke.mindustry 2>/dev/null | tr -d '\r')"
              if [[ -z "$final_pid" || "$final_foreground" != *"io.anuke.mindustry"* ]]; then
                launch_status=1
                note_failure "Mindustry exited or lost foreground during screenshot capture; refusing to publish non-game screenshot"
              fi
            else
              launch_status=1
              note_failure "Mindustry screenshot PNG validation failed"
            fi
            adb logcat -d -v time > build/test-game/localized/game-launch-logcat.txt || true
          fi
        fi
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
