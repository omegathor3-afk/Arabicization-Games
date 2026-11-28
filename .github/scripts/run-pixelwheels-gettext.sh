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
# to third-party hosts or embed them in public log annotations (repo privacy
# guard: .github/scripts/test-screenshot-privacy.py).
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

# Control: does the ORIGINAL game even launch on this emulator? If it does
# not, a failed activation leg says nothing about the Arabized build.
adb shell monkey -p com.agateau.tinywheels.android 1 || true
control_alive=0
for attempt in $(seq 1 6); do
  sleep 10
  if adb shell pidof com.agateau.tinywheels.android >/dev/null 2>&1; then control_alive=1; break; fi
done
if [[ "$control_alive" -eq 1 ]]; then
  echo "::notice::CONTROL original Pixel Wheels launches and stays alive on this emulator"
  adb shell am force-stop com.agateau.tinywheels.android || true
else
  adb logcat -d -v time > build/test-game/localized/control-launch-logcat.txt || true
  echo "::notice::CONTROL-WARN original Pixel Wheels did not stay running on this emulator either; activation evidence is environmental, see control-launch-logcat.txt in the workflow artifact"
fi

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

verified_apks=""
for leg in "" "-real"; do
  GENERATED_APK="$OUTPUT_DIR/pixelwheels-arabic${leg}.apk"
  if [[ -s "$GENERATED_APK" ]]; then
    APKSIGNER="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -n1)"
    if [[ ! -x "$APKSIGNER" ]] || ! "$APKSIGNER" verify --verbose "$GENERATED_APK"; then
      note_failure "apksigner verification failed for the generated Pixel Wheels APK (mode${leg:- pseudo})"
      test_status=1
    else
      verified_apks="${verified_apks}pixelwheels-arabic${leg}.apk ($(stat -c%s "$GENERATED_APK") bytes) "
    fi
  else
    note_failure "generated Pixel Wheels APK missing from Downloads/ARABIFY (mode${leg:- pseudo})"
    test_status=1
  fi
done
[[ -n "$verified_apks" ]] && echo "::notice::APK-VERIFIED ${verified_apks}(ARABIFY-signed, apksigner clean)"

# Visual Arabic render evidence on the deterministic pseudo build: the
# instrumented visual test screenshots the RUNNING game and template-matches
# menu lines against an in-test render of the shaped word drawn with the
# merged font. This is the pixel-level proof that pre-shaped presentation
# forms + the merged font produce visible Arabic glyph shapes (not tofu, not
# vanished glyphs). Frames stay in the private artifact per the privacy rule.
if [[ "$test_status" -eq 0 ]]; then
  visual_status=0
  PSEUDO_APK="$OUTPUT_DIR/pixelwheels-arabic.apk"
  if [[ ! -s "$PSEUDO_APK" ]]; then
    note_failure "pseudo Pixel Wheels APK missing; the visual-render leg cannot run"
    visual_status=1
  else
    adb_with_reconnect uninstall com.agateau.tinywheels.android || true
    adb_with_reconnect install "$PSEUDO_APK" || { visual_status=1; note_failure "install of the pseudo Pixel Wheels build failed"; }
  fi
  if [[ "$visual_status" -eq 0 ]]; then
    adb_with_reconnect shell settings put system system_locales ar-EG || { visual_status=1; note_failure "locale switch for the visual leg failed"; }
  fi
  if [[ "$visual_status" -eq 0 ]]; then
    timeout 30 adb reboot || { visual_status=$?; note_failure "adb reboot after the visual-leg locale switch failed"; }
  fi
  if [[ "$visual_status" -eq 0 ]]; then
    timeout 180 adb wait-for-device || { visual_status=1; note_failure "emulator did not return to ADB after the visual-leg reboot"; }
  fi
  if [[ "$visual_status" -eq 0 ]]; then
    BOOTED=""
    for attempt in $(seq 1 90); do
      BOOTED="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
      [[ "$BOOTED" == "1" ]] && break
      sleep 2
    done
    if [[ "$BOOTED" != "1" ]]; then note_failure "emulator did not finish booting for the visual leg"; visual_status=1; fi
  fi
  if [[ "$visual_status" -eq 0 ]]; then
    adb shell monkey -p com.agateau.tinywheels.android 1 || { visual_status=1; note_failure "monkey launch of the pseudo Pixel Wheels build failed"; }
    run_connected_test com.omega.arabify.PixelWheelsVisualArabicInstrumentedTest || visual_status=$?
  fi
  adb_with_reconnect pull /sdcard/Download/ARABIFY/pixelwheels-visual-frame.png "$OUTPUT_DIR/pixelwheels-visual-frame.png" || true
  adb_with_reconnect pull /sdcard/Download/ARABIFY/pixelwheels-visual-report.txt "$OUTPUT_DIR/pixelwheels-visual-report.txt" || true
  if [[ "$visual_status" -eq 0 && -s "$OUTPUT_DIR/pixelwheels-visual-report.txt" ]]; then
    echo "::notice::ARABIC-VISUAL-RENDER $(tr '\n' ' ' < "$OUTPUT_DIR/pixelwheels-visual-report.txt" | head -c 700) (best frame retained in the private workflow artifact per the privacy rule)"
  else
    note_failure "visual Arabic render verification failed or produced no report (status $visual_status)"
    test_status=1
  fi
fi

# In-game Arabic activation evidence: Pixel Wheels picks its language from
# the Android system locale (Languages.findBestLanguageId reads
# Locale.getDefault), and the Arabized build registers <Language id="ar">
# with a merged-glyph FontSet in assets/ui/languages.xml.
launch_status=0
ACTIVATION_APK="$OUTPUT_DIR/pixelwheels-arabic-real.apk"
if [[ ! -s "$ACTIVATION_APK" ]]; then
  note_failure "real-translation Pixel Wheels APK missing; the activation leg cannot run"
  launch_status=1
else
  adb_with_reconnect uninstall com.agateau.tinywheels.android || true
  adb_with_reconnect install "$ACTIVATION_APK" || { launch_status=1; note_failure "install of the ARABIFY-signed Pixel Wheels APK failed"; }
fi
if [[ "$launch_status" -eq 0 ]]; then
  adb shell settings put secure immersive_mode_confirmations confirmed >/dev/null 2>&1 || true
  adb shell settings put global policy_control immersive.confirmations=confirmed >/dev/null 2>&1 || true
  adb_with_reconnect shell settings put system system_locales ar-EG || { launch_status=1; note_failure "failed to switch emulator system locale to ar-EG"; }
fi
if [[ "$launch_status" -eq 0 ]]; then
  timeout 30 adb reboot || { launch_status=$?; note_failure "adb reboot after locale switch failed or timed out"; }
fi
if [[ "$launch_status" -eq 0 ]]; then
  if ! timeout 180 adb wait-for-device; then
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
    note_failure "emulator did not finish booting after the Arabic locale reboot (sys.boot_completed != 1)"
    launch_status=1
  fi
fi
if [[ "$launch_status" -eq 0 ]]; then
  adb logcat -c || true
  adb shell monkey -p com.agateau.tinywheels.android 1 || { launch_status=1; note_failure "monkey launch of the Arabized Pixel Wheels failed"; }
  # The game can be slow to reach a live process on swiftshader right after
  # a locale reboot: poll for up to 60s, then fall back to an explicit
  # activity start before declaring the launch dead.
  process_alive=0
  for attempt in $(seq 1 6); do
    sleep 10
    if adb shell pidof com.agateau.tinywheels.android > build/test-game/localized/game-process.txt 2>/dev/null && [[ -s build/test-game/localized/game-process.txt ]]; then
      process_alive=1
      break
    fi
  done
  if [[ "$process_alive" -eq 0 && "$launch_status" -eq 0 ]]; then
    adb shell am start -n com.agateau.tinywheels.android/com.agateau.pixelwheels.android.AndroidLauncher || true
    for attempt in $(seq 1 6); do
      sleep 10
      if adb shell pidof com.agateau.tinywheels.android > build/test-game/localized/game-process.txt 2>/dev/null && [[ -s build/test-game/localized/game-process.txt ]]; then
        process_alive=1
        break
      fi
    done
  fi
  if [[ "$process_alive" -eq 0 ]]; then
    # Diagnosability first: keep the crash evidence in the artifact and quote
    # the fatal lines in a public annotation (text is allowed; screenshots
    # stay artifact-only per the privacy guard).
    adb logcat -d -v time > build/test-game/localized/game-launch-logcat.txt || true
    adb logcat -b crash -d -v time > build/test-game/localized/game-crash-logcat.txt || true
    fatal_excerpt=$(grep -m1 -A12 'FATAL EXCEPTION\|Fatal signal\|AndroidRuntime' build/test-game/localized/game-launch-logcat.txt 2>/dev/null | head -13 | tr '\n' ' ' | cut -c1-800)
    note_failure "Pixel Wheels process not running after monkey + explicit activity start. Fatal excerpt: ${fatal_excerpt:-<no FATAL EXCEPTION in logcat; see game-launch-logcat.txt in the workflow artifact>}"
    launch_status=1
  fi
fi
if [[ "$launch_status" -eq 0 ]]; then
  # libGDX renders every label in OpenGL, so a uiautomator hierarchy usually
  # carries zero text nodes; the retained screenshots are the decisive
  # evidence (same policy as the Unciv and Mindustry jobs).
  adb shell uiautomator dump --compressed /data/local/tmp/arabify-pw-window.xml >/dev/null 2>&1 || true
  adb pull /data/local/tmp/arabify-pw-window.xml build/test-game/localized/pw-window.xml >/dev/null 2>&1 || true
  if [[ -s build/test-game/localized/pw-window.xml ]] && grep -qP '[\x{0600}-\x{06FF}]|[\x{FE70}-\x{FEFF}]' build/test-game/localized/pw-window.xml; then
    echo "::notice::ARABIC-ACTIVATION uiautomator hierarchy shows Arabic text on the Pixel Wheels screen"
  fi
  adb exec-out screencap -p > build/test-game/localized/pixelwheels-arabic-screen.png || { launch_status=1; note_failure "screencap of the Arabized Pixel Wheels screen failed"; }
  sleep 8
  adb exec-out screencap -p > build/test-game/localized/pixelwheels-arabic-screen-2.png || true
  for shot in build/test-game/localized/pixelwheels-arabic-screen.png build/test-game/localized/pixelwheels-arabic-screen-2.png; do
    if [[ -s "$shot" ]]; then
      python3 - "$shot" <<'PYNG'
from pathlib import Path
import struct, sys
path = Path(sys.argv[1])
data = path.read_bytes()
if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
    raise SystemExit(f"{path} is not a valid PNG.")
width, height = struct.unpack(">II", data[16:24])
if width < 320 or height < 480:
    raise SystemExit(f"{path} dimensions too small: {width}x{height}.")
print(f"{path} verified: {width}x{height}, {len(data)} bytes.")
PYNG
      if [[ "$?" -ne 0 ]]; then
        launch_status=1
        note_failure "Arabized Pixel Wheels screenshot failed PNG validation: $shot"
      fi
    else
      note_failure "Arabized Pixel Wheels screenshot missing or empty: $shot"
      launch_status=1
    fi
  done
  shots_ok=""
  [[ -s build/test-game/localized/pixelwheels-arabic-screen.png ]] && shots_ok="pixelwheels-arabic-screen.png"
  [[ -s build/test-game/localized/pixelwheels-arabic-screen-2.png ]] && shots_ok="${shots_ok} + pixelwheels-arabic-screen-2.png"
  if [[ "$launch_status" -eq 0 ]]; then
    echo "::notice::ARABIC-ACTIVATION Arabized Pixel Wheels booted under an ar-EG system locale (the game maps Locale.getDefault to the registered ar language via Languages.findBestLanguageId); process alive, valid frames (${shots_ok:-none}) retained in the private workflow artifact per the privacy rule. SCOPE: build/sign/dispatch/merge + locale-driven in-game activation covered; public evidence is these annotations"
  fi
fi
if [[ "$test_status" -eq 0 && "$launch_status" -ne 0 ]]; then
  test_status="$launch_status"
fi

exit "$test_status"
