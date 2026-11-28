#!/usr/bin/env bash
set -euo pipefail
cd "$GITHUB_WORKSPACE"

EVIDENCE_DIR="$GITHUB_WORKSPACE/build/bundled-first-install-offline"
mkdir -p "$EVIDENCE_DIR"

# The API 35 emulator can re-enable Wi-Fi during Google SDK setup. Disable
# that setup package before enforcing an actually offline first-install test.
adb get-state >/dev/null
adb shell pm disable-user --user 0 com.google.android.googlesdksetup >/dev/null 2>&1 || true
adb shell am force-stop com.google.android.googlesdksetup >/dev/null 2>&1 || true
adb shell settings put global airplane_mode_on 1
adb shell cmd connectivity airplane-mode enable || true
adb shell settings put global mobile_data 0
adb shell svc data disable || true

wifi_state=""
for attempt in $(seq 1 30); do
  adb shell am force-stop com.google.android.googlesdksetup >/dev/null 2>&1 || true
  adb shell svc wifi disable >/dev/null 2>&1 || true
  adb shell cmd wifi set-wifi-enabled disabled >/dev/null 2>&1 || true
  wifi_state="$(adb shell settings get global wifi_on | tr -d '\r')"
  if [[ "$wifi_state" == "0" ]]; then
    sleep 1
    wifi_state="$(adb shell settings get global wifi_on | tr -d '\r')"
    [[ "$wifi_state" == "0" ]] && break
  fi
  sleep 1
done
if [[ "$wifi_state" != "0" ]]; then
  echo "Wi-Fi did not remain disabled before the bundled first-install test (wifi_on=$wifi_state)." >&2
  adb shell dumpsys wifi | tail -120 >&2 || true
  exit 1
fi

adb shell settings get global airplane_mode_on > "$EVIDENCE_DIR/airplane-mode-before.txt"
adb shell settings get global wifi_on > "$EVIDENCE_DIR/wifi-setting-before.txt"
adb shell settings get global mobile_data > "$EVIDENCE_DIR/mobile-data-before.txt"
adb shell dumpsys connectivity > "$EVIDENCE_DIR/connectivity-before.txt" || true
adb shell dumpsys wifi > "$EVIDENCE_DIR/wifi-before.txt" || true
adb shell getprop ro.build.version.sdk > "$EVIDENCE_DIR/api-level.txt"
adb shell getprop ro.build.fingerprint > "$EVIDENCE_DIR/build-fingerprint.txt"

# This test installs the CI-built app/test APKs into a clean x86_64 emulator,
# proves the ML Kit model is absent, stages the hash-verified bundled en-ar
# files, and translates through OfflineArabicTranslator with no internet.
gradle connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.omega.arabify.BundledFirstInstallOfflineInstrumentedTest \
  --stacktrace

adb logcat -d -v threadtime -s BundledFirstInstall:I > "$EVIDENCE_DIR/instrumentation.log" || true
adb logcat -b crash -d -v threadtime > "$EVIDENCE_DIR/crash-logcat.txt" || true
adb shell dumpsys meminfo com.omega.arabify > "$EVIDENCE_DIR/app-meminfo-after.txt" || true
adb shell settings get global airplane_mode_on > "$EVIDENCE_DIR/airplane-mode-after.txt" || true
adb shell settings get global wifi_on > "$EVIDENCE_DIR/wifi-setting-after.txt" || true
adb shell settings get global mobile_data > "$EVIDENCE_DIR/mobile-data-after.txt" || true
adb shell dumpsys connectivity > "$EVIDENCE_DIR/connectivity-after.txt" || true
adb shell dumpsys wifi > "$EVIDENCE_DIR/wifi-after.txt" || true

grep -q 'bundled-package:present' "$EVIDENCE_DIR/instrumentation.log"
grep -q 'mlkit-arabic-model-downloaded=false' "$EVIDENCE_DIR/instrumentation.log"
grep -q 'engine=bundled-bergamot' "$EVIDENCE_DIR/instrumentation.log"
[[ "$(cat "$EVIDENCE_DIR/airplane-mode-before.txt")" == "1" ]]
[[ "$(cat "$EVIDENCE_DIR/wifi-setting-before.txt")" == "0" ]]
[[ "$(cat "$EVIDENCE_DIR/mobile-data-before.txt")" == "0" ]]
[[ "$(cat "$EVIDENCE_DIR/airplane-mode-after.txt")" == "1" ]]
[[ "$(cat "$EVIDENCE_DIR/wifi-setting-after.txt")" == "0" ]]
[[ "$(cat "$EVIDENCE_DIR/mobile-data-after.txt")" == "0" ]]

echo "::notice::BundledFirstInstallOfflineInstrumentedTest PASSED: clean x86_64 install translated through bundled Bergamot with airplane mode enabled, Wi-Fi/mobile data disabled, and zero internet-capable networks (asserted in the instrumented test)."
