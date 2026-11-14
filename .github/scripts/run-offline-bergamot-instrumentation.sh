set -uo pipefail
cd "$GITHUB_WORKSPACE"
mkdir -p "$GITHUB_WORKSPACE/build/bergamot-instrumented"
# Google SDK Setup can re-enable Wi-Fi shortly after the API 35 emulator
# boots, even while airplane mode is on. Disable that one-shot setup package
# in this disposable CI emulator before enforcing the offline state.
adb shell pm disable-user --user 0 com.google.android.googlesdksetup || true
adb shell am force-stop com.google.android.googlesdksetup || true
adb shell settings put global airplane_mode_on 1
adb shell cmd connectivity airplane-mode enable || true
adb shell settings put global mobile_data 0
adb shell svc data disable
wifi_state=""
for attempt in $(seq 1 30); do
  adb shell am force-stop com.google.android.googlesdksetup || true
  adb shell svc wifi disable || true
  adb shell cmd wifi set-wifi-enabled disabled || true
  wifi_state="$(adb shell settings get global wifi_on | tr -d '\r')"
  if [[ "$wifi_state" == "0" ]]; then
    sleep 1
    wifi_state="$(adb shell settings get global wifi_on | tr -d '\r')"
    [[ "$wifi_state" == "0" ]] && break
  fi
  sleep 1
done
if [[ "$wifi_state" != "0" ]]; then
  echo "Wi-Fi did not remain disabled after stopping Google SDK Setup (wifi_on=$wifi_state)." >&2
  adb shell dumpsys wifi | tail -120 >&2 || true
  exit 1
fi
adb shell settings get global airplane_mode_on > "$GITHUB_WORKSPACE/build/bergamot-instrumented/airplane-mode-before.txt" || true
adb shell settings get global wifi_on > "$GITHUB_WORKSPACE/build/bergamot-instrumented/wifi-setting-before.txt" || true
adb shell settings get global mobile_data > "$GITHUB_WORKSPACE/build/bergamot-instrumented/mobile-data-before.txt" || true
adb shell dumpsys connectivity > "$GITHUB_WORKSPACE/build/bergamot-instrumented/connectivity-before.txt" || true
adb shell dumpsys wifi > "$GITHUB_WORKSPACE/build/bergamot-instrumented/wifi-before.txt" || true
adb shell getprop ro.build.version.sdk > "$GITHUB_WORKSPACE/build/bergamot-instrumented/api-level.txt" || true
adb shell getprop ro.build.fingerprint > "$GITHUB_WORKSPACE/build/bergamot-instrumented/build-fingerprint.txt" || true
if gradle connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.omega.arabify.BergamotOfflineInstrumentedTest --stacktrace; then
  test_status=0
else
  test_status=$?
fi
# Keep both focused phase markers and the complete device/crash buffers.
adb logcat -d -v threadtime -s BergamotOfflineTest:I > "$GITHUB_WORKSPACE/build/bergamot-instrumented/instrumentation.log" || true
adb logcat -b all -d -v threadtime > "$GITHUB_WORKSPACE/build/bergamot-instrumented/device-logcat-all.txt" || true
adb logcat -b crash -d -v threadtime > "$GITHUB_WORKSPACE/build/bergamot-instrumented/crash-logcat.txt" || true
adb shell dumpsys meminfo com.omega.arabify > "$GITHUB_WORKSPACE/build/bergamot-instrumented/app-meminfo-after.txt" || true
adb shell settings get global airplane_mode_on > "$GITHUB_WORKSPACE/build/bergamot-instrumented/airplane-mode-after.txt" || true
adb shell settings get global wifi_on > "$GITHUB_WORKSPACE/build/bergamot-instrumented/wifi-setting-after.txt" || true
adb shell settings get global mobile_data > "$GITHUB_WORKSPACE/build/bergamot-instrumented/mobile-data-after.txt" || true
adb shell dumpsys connectivity > "$GITHUB_WORKSPACE/build/bergamot-instrumented/connectivity-after.txt" || true
adb shell dumpsys wifi > "$GITHUB_WORKSPACE/build/bergamot-instrumented/wifi-after.txt" || true
printf '%s\n' "$test_status" > "$GITHUB_WORKSPACE/build/bergamot-instrumented/test-exit-code.txt"
python3 - <<'PY'
import json
import os
from pathlib import Path

root = Path("build/bergamot-instrumented")
log_path = root / "instrumentation.log"
records = []
if log_path.is_file():
    for line in log_path.read_text(errors="replace").splitlines():
        marker = "METRIC_JSON:"
        if marker not in line:
            continue
        payload = line.split(marker, 1)[1].strip()
        try:
            records.append(json.loads(payload))
        except json.JSONDecodeError as exc:
            raise SystemExit(f"Invalid instrumentation metric JSON: {exc}: {payload}")

def read(name):
    path = root / name
    return path.read_text(errors="replace").strip() if path.is_file() else None

try:
    exit_code = int(read("test-exit-code.txt"))
except (TypeError, ValueError):
    exit_code = None
result = {
    "schemaVersion": 1,
    "commitSha": os.environ.get("GITHUB_SHA"),
    "ndkVersion": "27.2.12479018",
    "emulator": {
        "apiLevel": read("api-level.txt"),
        "buildFingerprint": read("build-fingerprint.txt"),
        "requestedApiLevel": 35,
        "requestedAbi": "x86_64"
    },
    "networkState": {
        "airplaneModeBefore": read("airplane-mode-before.txt"),
        "airplaneModeAfter": read("airplane-mode-after.txt"),
        "wifiSettingBefore": read("wifi-setting-before.txt"),
        "wifiSettingAfter": read("wifi-setting-after.txt"),
        "mobileDataBefore": read("mobile-data-before.txt"),
        "mobileDataAfter": read("mobile-data-after.txt"),
        "connectivityDump": "connectivity-before.txt and connectivity-after.txt",
        "wifiDump": "wifi-before.txt and wifi-after.txt"
    },
    "instrumentationRecords": records,
    "testExitCode": exit_code,
    "offlineVerified": exit_code == 0 and len(records) == 2 and all(
        record.get("networkAssertionsPassed") is True for record in records
    )
}
(root / "metrics.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
print(json.dumps(result, ensure_ascii=False, indent=2))
PY
exit "$test_status"
