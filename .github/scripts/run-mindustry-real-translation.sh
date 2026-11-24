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

mkdir -p build/test-game/localized/real

# Standalone-capable: install the pinned game APK (no-op if already installed).
adb install build/test-game/mindustry.apk || { note_failure "adb install of pinned Mindustry APK failed"; exit 1; }

# Clear stale published evidence: on a reused AVD the MediaStore renames a
# duplicate display name on disk, so an unprefixed adb pull would fetch the
# PREVIOUS run's report (observed: identical stale skipReasons across runs).
adb shell rm -rf /sdcard/Download/ARABIFY >/dev/null 2>&1 || true

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
  note_failure "real-translation Mindustry test failed (exit $test_status): ML Kit model download or translation unavailable on this emulator :: ${details}"
  exit "$test_status"
fi

adb pull /sdcard/Download/ARABIFY/mindustry-arabic-real.apk build/test-game/localized/real/mindustry-arabic-real.apk || true
adb pull /sdcard/Download/ARABIFY/mindustry-report-real.txt build/test-game/localized/real/mindustry-report-real.txt || true

GENERATED_APK="build/test-game/localized/real/mindustry-arabic-real.apk"
if [[ ! -s "$GENERATED_APK" ]]; then
  note_failure "real-translation Mindustry APK was not exported"
  [[ -s "$last_ok_shot" ]] && upload_shot "$last_ok_shot" "mindustry-real-last-foreground-frame-tick-${last_ok_tick}"
  exit 1
fi

# Surface the translated menu labels as ONE public annotation so translation
# quality is reviewable without artifact access. GitHub stores at most 10
# notice annotations per check run, so per-label notices would silently drop
# the labels and the screenshot URL.
if [[ -s build/test-game/localized/real/mindustry-report-real.txt ]]; then
  python3 - <<'PYANN'
from pathlib import Path

lines = Path("build/test-game/localized/real/mindustry-report-real.txt").read_text(encoding="utf-8").splitlines()
counts = [l for l in lines if l.startswith(("translatedStrings=", "skippedStrings=", "skipReasons="))]
try:
    start = lines.index("menuTranslations:") + 1
except ValueError:
    start = len(lines)
labels = [l for l in lines[start:] if l.strip()][:20]
encoded = "%0A".join(l.replace("%", "%25") for l in counts + ["---"] + labels)
print(f"::notice::TRANSLATIONS%0A{encoded}")
PYANN
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

# In-game screen tour: the main menu alone does not prove in-game Arabic
# (Omega, 2026-11-24: "all of this only translates the main menu?"). Visit
# real screens and capture each one for human review. Tap coordinates are
# measured on the fixed pixel_5 (1080x2340) menu layout.
tour_status=0
capture_screen() {
  local name="$1" wait_s="$2"
  sleep "$wait_s"
  local fg
  fg="$(adb shell dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity' | tail -n 1 | tr -d '\r')"
  if [[ "$fg" != *"io.anuke.mindustry"* ]]; then
    # Capture what IS on screen (crash dialog? launcher?) plus a logcat tail
    # so a tour failure says whether the game crashed or the tap missed.
    adb exec-out screencap -p > "build/test-game/localized/real/mindustry-tour-${name}-FAILSTATE.png" 2>/dev/null || true
    [[ -s "build/test-game/localized/real/mindustry-tour-${name}-FAILSTATE.png" ]] && upload_shot "build/test-game/localized/real/mindustry-tour-${name}-FAILSTATE.png" "mindustry-tour-${name}-FAILSTATE"
    local crash_tail
    crash_tail=$(adb logcat -d -b crash -v time 2>/dev/null | tail -12 | cut -c1-180 | tr '\n' '|' | cut -c1-1500)
    note_failure "Mindustry left the foreground during the in-game tour before ${name} (foreground=${fg}) :: crash log: ${crash_tail:-none}"
    tour_status=1
    return 1
  fi
  adb exec-out screencap -p > "build/test-game/localized/real/mindustry-tour-${name}.png" || true
  if [[ ! -s "build/test-game/localized/real/mindustry-tour-${name}.png" ]]; then
    note_failure "in-game tour screenshot missing: ${name}"
    tour_status=1
    return 1
  fi
  upload_shot "build/test-game/localized/real/mindustry-tour-${name}.png" "mindustry-tour-${name}"
  return 0
}

# Settings screen: right column, third tile row on the pixel_5 menu grid.
adb shell input tap 670 1364 || true
capture_screen settings 6 || true
adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
sleep 3
# Campaign planet/sector screen: left column, first tile row. The 3D planet
# needs render time on swiftshader.
adb shell input tap 411 845 || true
capture_screen campaign-map 20 || true
adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
sleep 3
# Custom game map list: left column, second tile row.
adb shell input tap 411 1105 || true
capture_screen custom-game-maps 8 || true

[[ "$tour_status" -ne 0 ]] && exit "$tour_status"
exit 0
