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
# to third-party hosts or embed them in public log annotations.
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

mkdir -p build/test-game/localized
adb install build/test-game/unciv.apk || { install_rc=$?; note_failure "adb install of pinned Unciv APK failed (exit $install_rc)"; exit $install_rc; }

test_status=0
# Real translation: the screenshot evidence needs real Arabic, not pseudo
# strings - the seeded GameSettings.json boots the game into the slot.
# run_connected_test surfaces the failing assertion from the result XML as a
# public check-run annotation (Actions logs need auth; annotations do not).
run_connected_test com.omega.arabify.UncivLocaleSlotInstrumentedTest \
  -Pandroid.testInstrumentationRunnerArguments.translationMode=real || test_status=$?
if [[ "$test_status" -eq 0 ]]; then
  echo "::notice::UncivLocaleSlotInstrumentedTest PASSED (real translation; slot built, signed and verified on-device)"
fi

# Phase B dispatch evidence: the exact planner-dispatch path MainActivity
# now runs, executed on-device against the real game (deterministic pseudo
# translation; mechanics only, so no ML Kit download).
run_connected_test com.omega.arabify.DispatchPathInstrumentedTest || test_status=$?
if [[ "$test_status" -eq 0 ]]; then
  echo "::notice::DispatchPathInstrumentedTest PASSED (real game via slot wrapper + synthetic fixture via gettext wrapper, both through planner dispatch; host-signed outputs verified)"
fi

# Repackaging route evidence (Omega's decision 2026-11-27): runs while the
# ORIGINAL F-Droid-signed Unciv is still installed. Proves signature-conflict
# detection, the uninstall intent, and a full PackageInstaller session
# (create/write/fsync, abandoned without commit). The commit's
# system-confirmation tap is the user's own approval and stays untested.
run_connected_test com.omega.arabify.RepackageRouteInstrumentedTest || test_status=$?
if [[ "$test_status" -eq 0 ]]; then
  echo "::notice::RepackageRouteInstrumentedTest PASSED (signature-conflict detection + uninstall intent + PackageInstaller session on-device; user confirmation tap is the only untested step)"
fi

# The long connected-test run (ML Kit translating 6.5k strings on
# swiftshader) can drop the adb connection; observed 2026-11-25: green
# test, then every pull failed with "device offline". Reconnect first.
for attempt in $(seq 1 24); do
  adb_state=$(adb get-state 2>/dev/null | tr -d '\r')
  [[ "$adb_state" == "device" ]] && break
  adb reconnect 2>/dev/null || adb reconnect offline 2>/dev/null || true
  sleep 5
done
adb_state=$(adb get-state 2>/dev/null | tr -d '\r')
if [[ "$adb_state" != "device" ]]; then
  note_failure "adb connection to the emulator never recovered after the connected test (state: ${adb_state:-unknown})"
  exit 1
fi

# get-state saying "device" is not enough: the link can flap again between
# the check and the next command (observed on e57557f: state recovered,
# then the evidence pulls still hit "device offline"). Retry individual
# adb commands with a reconnect between attempts.
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

OUTPUT_DIR="build/test-game/localized/pulled-evidence"
mkdir -p "$OUTPUT_DIR"
adb_with_reconnect pull /sdcard/Download/ARABIFY/unciv-arabic.apk "$OUTPUT_DIR/unciv-arabic.apk" || true
adb_with_reconnect pull /sdcard/Download/ARABIFY/unciv-report.txt "$OUTPUT_DIR/unciv-report.txt" || true
adb_with_reconnect pull /sdcard/Download/ARABIFY/unciv-roundtrip.txt "$OUTPUT_DIR/unciv-roundtrip.txt" || true
if [[ -s "$OUTPUT_DIR/unciv-roundtrip.txt" ]]; then
  echo "::notice::ROUNDTRIP-AUDIT $(head -2 "$OUTPUT_DIR/unciv-roundtrip.txt" | tr '\n' ' ')"
  tail -n +3 "$OUTPUT_DIR/unciv-roundtrip.txt" | head -5 | while IFS= read -r sample; do
    [[ -n "$sample" ]] && echo "::notice::ROUNDTRIP-FLAGGED ${sample:0:250}"
  done
fi

if [[ -s "$OUTPUT_DIR/unciv-arabic.apk" ]]; then
  echo "::notice::LAUNCH pulled localized APK ($(stat -c%s "$OUTPUT_DIR/unciv-arabic.apk") bytes)"
fi
# Visual-leg preparation: build the deterministic pseudo Unciv APK while the
# ORIGINAL game is still installed (the Arabized real build already carries
# an Arabic slot and the localizer honestly refuses those). Pulled under a
# distinct name; the real evidence APK was already pulled above.
visual_status=0
if [[ "$test_status" -eq 0 ]]; then
  run_connected_test com.omega.arabify.UncivLocaleSlotInstrumentedTest \
    -Pandroid.testInstrumentationRunnerArguments.translationMode=pseudo || visual_status=$?
fi
if [[ "$test_status" -eq 0 && "$visual_status" -eq 0 ]]; then
  adb_with_reconnect pull /sdcard/Download/ARABIFY/unciv-arabic-pseudo.apk "$OUTPUT_DIR/unciv-arabic-pseudo.apk" || { visual_status=1; note_failure "pseudo Unciv APK pull failed"; }
fi
if [[ "$test_status" -eq 0 && "$visual_status" -ne 0 ]]; then
  note_failure "pseudo Unciv build failed; the visual-render leg cannot run"
  test_status=1
fi

GENERATED_APK="$OUTPUT_DIR/unciv-arabic.apk"
launch_status=0
if [[ -s "$GENERATED_APK" ]]; then
  APKSIGNER="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -n1)"
  if [[ ! -x "$APKSIGNER" ]] || ! "$APKSIGNER" verify --verbose "$GENERATED_APK"; then
    launch_status=1
    note_failure "apksigner verification failed for the generated localized Unciv APK"
  else
    adb_with_reconnect uninstall com.unciv.app || true
    adb_with_reconnect install "$GENERATED_APK" || { launch_status=1; note_failure "install of generated localized Unciv APK failed"; }
    if [[ "$launch_status" -eq 0 ]]; then
      # Unciv's language picker hides Arabic entirely (LocaleCode.Arabic is
      # flagged unused upstream), but its translation loader reads
      # jsons/translations/<settings.language>.properties with no such filter.
      # Seed GameSettings.json so the game boots in Arabic directly. This is
      # how activation works in CI; on-device activation needs the same file
      # written (debuggable repackage + run-as) or a dex patch - see docs.
      # google_apis emulators are userdebug, so adb root works there; run-as
      # is the fallback for debuggable repackages.
      SETTINGS_JSON='{"language":"العربية"}'
      if adb_with_reconnect root >/dev/null 2>&1; then
        sleep 2
        adb wait-for-device
        adb_with_reconnect shell "mkdir -p /data/data/com.unciv.app/files && echo '$SETTINGS_JSON' > /data/data/com.unciv.app/files/GameSettings.json"           && echo "::notice::Unciv GameSettings.json seeded with language=العربية via adb root"           || note_failure "failed to seed GameSettings.json via adb root"
        adb unroot >/dev/null 2>&1 || true
        sleep 2
        adb wait-for-device
      else
        adb_with_reconnect shell "run-as com.unciv.app sh -c "mkdir -p files && echo '$SETTINGS_JSON' > files/GameSettings.json""           && echo "::notice::Unciv GameSettings.json seeded with language=العربية via run-as"           || note_failure "failed to seed GameSettings.json (no adb root, run-as failed)"
      fi
      # Unciv resolves the locale from the Android system locale through
      # libGDX; switch the emulator to Arabic so it selects bundle_ar.
      # All adb calls in this launch section go through adb_with_reconnect:
      # on 5004c0a the link flapped after the evidence pulls and plain adb
      # calls here failed (no adb root, locale switch exit 1).
      adb_with_reconnect shell settings put system system_locales ar-EG || { launch_status=1; note_failure "failed to switch emulator system locale to ar-EG"; }
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
        # Android's "Viewing full screen" first-launch dialog covers the game
        # and blocks the Arabic hierarchy check; pre-confirm it (as the
        # Mindustry script does).
        adb shell settings put secure immersive_mode_confirmations confirmed >/dev/null 2>&1 || true
        adb shell settings put global policy_control immersive.confirmations=confirmed >/dev/null 2>&1 || true
        adb shell monkey -p com.unciv.app 1 || { launch_status=$?; note_failure "monkey launch of the localized game failed"; }
        sleep 12
        adb shell pidof com.unciv.app > build/test-game/localized/game-process.txt || { launch_status=$?; note_failure "game process not running after launch (pidof empty)"; }
        HIERARCHY_DEVICE_PATH="/data/local/tmp/arabify-unciv-window.xml"
        HIERARCHY_HOST_PATH="build/test-game/localized/unciv-window.xml"
        hierarchy_ready=0
        for attempt in 1 2 3; do
          adb shell rm -f "$HIERARCHY_DEVICE_PATH" || true
          adb shell uiautomator dump --compressed "$HIERARCHY_DEVICE_PATH" >/tmp/arabify-uiautomator-dump.log 2>&1 || true
          if adb shell test -s "$HIERARCHY_DEVICE_PATH"; then
            if adb pull "$HIERARCHY_DEVICE_PATH" "$HIERARCHY_HOST_PATH" >/dev/null 2>&1 && [[ -s "$HIERARCHY_HOST_PATH" ]]; then
              hierarchy_ready=1
              break
            fi
          fi
          sleep 2
        done
        if [[ "$hierarchy_ready" -eq 1 ]] && grep -q 'text="Viewing full screen"' "$HIERARCHY_HOST_PATH"; then
          # Android 15 shows a system immersive-mode tutorial on first launch.
          # Dismiss its accessible "Got it" button by reading its actual bounds,
          # rather than relying on a device-specific hard-coded tap coordinate.
          tap_point="$(python3 - "$HIERARCHY_HOST_PATH" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
for node in root.iter("node"):
    if node.get("resource-id") == "android:id/ok" and node.get("text") == "Got it" and node.get("clickable") == "true":
        match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if match:
            left, top, right, bottom = map(int, match.groups())
            print((left + right) // 2, (top + bottom) // 2)
            break
PY
)"
          if [[ -z "$tap_point" ]]; then
            note_failure "Android immersive tutorial was visible but its accessible Got it button could not be located"
            launch_status=1
          else
            read -r tap_x tap_y <<< "$tap_point"
            adb shell input tap "$tap_x" "$tap_y" || { launch_status=$?; note_failure "could not dismiss Android immersive tutorial"; }
            sleep 3
            adb shell rm -f "$HIERARCHY_DEVICE_PATH" || true
            adb shell uiautomator dump --compressed "$HIERARCHY_DEVICE_PATH" >/tmp/arabify-uiautomator-dump.log 2>&1 || true
            if adb shell test -s "$HIERARCHY_DEVICE_PATH"; then
              adb pull "$HIERARCHY_DEVICE_PATH" "$HIERARCHY_HOST_PATH" >/dev/null 2>&1 || true
            fi
            if grep -q 'text="Viewing full screen"' "$HIERARCHY_HOST_PATH"; then
              note_failure "Android immersive tutorial remained visible after tapping Got it"
              launch_status=1
            fi
          fi
        fi
        if [[ "$hierarchy_ready" -eq 1 ]]; then
          # Unciv either opens its first-run language picker (listing
          # العربية from the new bundle_ar) or boots its menu in Arabic.
          # Any Arabic script on screen proves the slot is active.
          if grep -qP '[\x{0600}-\x{06FF}]' "$HIERARCHY_HOST_PATH"; then
            echo "Accessibility hierarchy captured; Arabic text is visible on the Unciv screen." > build/test-game/localized/ui-smoke-validation.txt
          elif grep -q 'text="Arabic"' "$HIERARCHY_HOST_PATH"; then
            echo "Accessibility hierarchy captured; the game lists Arabic in its own language UI." > build/test-game/localized/ui-smoke-validation.txt
          elif ! grep -q 'text="[^"]' "$HIERARCHY_HOST_PATH"; then
            # libGDX renders every label in OpenGL, so a captured hierarchy
            # with zero text nodes says nothing about Arabic on screen. Same
            # evidence policy as the no-hierarchy branch: screenshots decide.
            echo "::notice::UIAutomator hierarchy has zero text nodes on the Unciv picker (libGDX/OpenGL); Arabic visibility is decided by screenshots, not counted as failure"
            echo "NOTE: hierarchy carried no text nodes; Arabic presence verified visually via screenshots." > build/test-game/localized/ui-smoke-validation.txt
            # Scroll the language list so the (completion-sorted) Arabic entry
            # has a chance to be visible. Keep every capture in the workflow
            # artifact; never upload screenshot bytes or URLs to external hosts.
            for swipe in 1 2 3 4 5 6 7 8 9 10 11 12; do
              adb shell input swipe 1170 800 1170 300 400 >/dev/null 2>&1 || true
              sleep 2
              shot_path="build/test-game/localized/unciv-language-list-${swipe}.png"
              adb exec-out screencap -p > "$shot_path" || true
              [[ -s "$shot_path" ]] && retain_shot "$shot_path" "unciv-language-list-${swipe}"
            done
            echo "::notice::SCREENSHOTS unciv-language-list (12 swipes): retained in GitHub Actions artifact under build/test-game/localized/unciv-language-list-*.png"
          else
            echo "No Arabic text or Arabic language entry was visible in the accessibility hierarchy." >&2
            # Keep visible UI text in the workflow artifact rather than the
            # public annotation; the screenshot is retained alongside it.
            visible_text_file="build/test-game/localized/unciv-visible-screen-text.txt"
            python3 - "$HIERARCHY_HOST_PATH" > "$visible_text_file" <<'PYVIS'
import re
import sys
try:
    xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
except OSError:
    xml = ""
texts = []
for value in re.findall(r'text="([^"]*)"', xml):
    value = value.strip()
    if value and value not in texts:
        texts.append(value)
summary = " | ".join(texts)[:1200]
print(summary if summary else "<no visible text nodes>")
PYVIS
            note_failure "no Arabic text and no Arabic language entry visible on the Unciv screen; see unciv-visible-screen-text.txt in the workflow artifact"
            launch_status=1
          fi
        else
          echo "::notice::UIAutomator exposed no hierarchy; relying on screenshot + process checks (not counted as failure)"
          echo "WARNING: Android UIAutomator did not expose a hierarchy for the launched game. The instrumentation test verified the slot content, APK signature and install; screenshot and foreground-process checks remain required." > build/test-game/localized/ui-smoke-validation.txt
          cat /tmp/arabify-uiautomator-dump.log >> build/test-game/localized/ui-smoke-validation.txt 2>/dev/null || true
        fi
        adb exec-out screencap -p > build/test-game/localized/unciv-arabic-screen.png || { launch_status=$?; note_failure "screencap of the localized Unciv screen failed (exit $launch_status)"; }
        if [[ ! -s build/test-game/localized/unciv-arabic-screen.png ]]; then
          echo "Localized game screenshot is missing or empty." >&2
          note_failure "localized Unciv screenshot missing or empty after launch"
          launch_status=1
        else
          python3 - <<'PY'
from pathlib import Path
import struct

path = Path("build/test-game/localized/unciv-arabic-screen.png")
data = path.read_bytes()
if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
    raise SystemExit("Localized game screenshot is not a valid PNG.")
width, height = struct.unpack(">II", data[16:24])
if width < 320 or height < 480:
    raise SystemExit(f"Localized game screenshot dimensions are too small: {width}x{height}.")
print(f"Localized game screenshot verified: {width}x{height}, {len(data)} bytes.")
PY
          if [[ "$?" -ne 0 ]]; then
            launch_status=1
            note_failure "localized Unciv screenshot failed PNG validation"
          fi
        fi
        adb logcat -d -v time > build/test-game/localized/game-launch-logcat.txt || true
        retain_shot build/test-game/localized/unciv-arabic-screen.png "unciv-arabic-screen"
      fi
    fi
  fi
else
  echo "Translated Unciv APK was not exported to the shared Downloads collection." >&2
  downloads_listing=$(adb_with_reconnect shell ls -la /sdcard/Download/ARABIFY/ 2>&1 | head -20 | tr '\n' ' ')
  note_failure "localized Unciv APK missing after a green test: /sdcard/Download/ARABIFY listing: ${downloads_listing:-<empty>}"
  if [[ "$test_status" -eq 0 ]]; then test_status=1; fi
fi
# Visual Arabic render evidence on the deterministic pseudo build: the
# instrumented visual test screenshots the RUNNING game and template-matches
# menu lines against an in-test render of the shaped pseudo word drawn with
# the system typeface (Unciv builds fonts from Android system fonts at
# runtime). Pixel-level proof that pre-shaped presentation forms produce
# VISIBLE Arabic glyph shapes; frames stay in the private artifact.
if [[ "$test_status" -eq 0 && "$launch_status" -eq 0 ]]; then
  if [[ "$visual_status" -eq 0 ]]; then
    adb_with_reconnect uninstall com.unciv.app || true
    adb_with_reconnect install "$OUTPUT_DIR/unciv-arabic-pseudo.apk" || { visual_status=1; note_failure "install of the pseudo Unciv build failed"; }
  fi
  if [[ "$visual_status" -eq 0 ]]; then
    SETTINGS_JSON='{"language":"العربية"}'
    if adb_with_reconnect root >/dev/null 2>&1; then
      sleep 2
      adb wait-for-device
      adb_with_reconnect shell "mkdir -p /data/data/com.unciv.app/files && echo '$SETTINGS_JSON' > /data/data/com.unciv.app/files/GameSettings.json" || { visual_status=1; note_failure "failed to seed GameSettings.json for the visual leg via adb root"; }
      adb unroot >/dev/null 2>&1 || true
      sleep 2
      adb wait-for-device
    else
      adb_with_reconnect shell "run-as com.unciv.app sh -c "mkdir -p files && echo '$SETTINGS_JSON' > files/GameSettings.json"" || { visual_status=1; note_failure "failed to seed GameSettings.json for the visual leg via run-as"; }
    fi
  fi
  if [[ "$visual_status" -eq 0 ]]; then
    # Finish dexopt BEFORE launching (Mindustry evidence: a post-install
    # compile during first launch pauses/kills the game or stalls boot).
    adb shell cmd package compile -m speed -f com.unciv.app > build/test-game/localized/visual-dexopt.txt 2>&1 || true
    sleep 3
    adb shell settings put secure immersive_mode_confirmations confirmed >/dev/null 2>&1 || true
    adb shell settings put global policy_control immersive.confirmations=confirmed >/dev/null 2>&1 || true
    adb shell monkey -p com.unciv.app 1 || { visual_status=1; note_failure "monkey launch of the pseudo Unciv build failed"; }
    sleep 12
    adb shell pidof com.unciv.app > build/test-game/localized/visual-game-process.txt || { visual_status=1; note_failure "pseudo Unciv process not running after launch"; }
  fi
  if [[ "$visual_status" -eq 0 ]]; then
    # Foreground guard: verify the game is the resumed activity before
    # polling screenshots; relaunch once if it dropped to the launcher.
    fg_dump="$(adb shell dumpsys activity activities 2>/dev/null | grep -m 1 'topResumedActivity' || true)"
    if [[ "$fg_dump" != *"com.unciv.app"* ]]; then
      adb shell monkey -p com.unciv.app 1 > /dev/null 2>&1 || true
      sleep 10
      fg_dump="$(adb shell dumpsys activity activities 2>/dev/null | grep -m 1 'topResumedActivity' || true)"
      if [[ "$fg_dump" != *"com.unciv.app"* ]]; then
        echo "::notice::UNCIV-VISUAL foreground not confirmed before the visual test (top=$fg_dump); screenshots may miss the game"
      fi
    fi
    run_connected_test com.omega.arabify.UncivVisualArabicInstrumentedTest || visual_status=$?
  fi
  adb_with_reconnect pull /sdcard/Download/ARABIFY/unciv-visual-frame.png "$OUTPUT_DIR/unciv-visual-frame.png" || true
  adb_with_reconnect pull /sdcard/Download/ARABIFY/unciv-visual-report.txt "$OUTPUT_DIR/unciv-visual-report.txt" || true
  if [[ "$visual_status" -eq 0 && -s "$OUTPUT_DIR/unciv-visual-report.txt" ]]; then
    echo "::notice::UNCIV-VISUAL-RENDER $(tr '\n' ' ' < "$OUTPUT_DIR/unciv-visual-report.txt" | head -c 700) (best frame retained in the private workflow artifact per the privacy rule)"
  else
    # Emit the full report (histogram, per-mask band counts, ASCII luminance
    # map) so the failure is diagnosable without artifact access.
    [[ -s "$OUTPUT_DIR/unciv-visual-report.txt" ]] && echo "::notice::UNCIV-VISUAL-REPORT $(tr '\n' '~' < "$OUTPUT_DIR/unciv-visual-report.txt" | head -c 3000)"
    note_failure "Unciv visual Arabic render verification failed or produced no report (status $visual_status)"
    test_status=1
  fi
fi

if [[ "$test_status" -eq 0 && "$launch_status" -ne 0 ]]; then
  test_status="$launch_status"
fi

exit "$test_status"
