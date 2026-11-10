set -uo pipefail
cd "$GITHUB_WORKSPACE"
mkdir -p "$GITHUB_WORKSPACE/build/bergamot-instrumented"
adb shell settings put global airplane_mode_on 1
adb shell svc wifi disable
adb shell settings put global mobile_data 0
adb shell svc data disable
adb shell cmd wifi set-wifi-enabled disabled || true
adb shell cmd connectivity airplane-mode enable || true
adb shell settings get global airplane_mode_on > "$GITHUB_WORKSPACE/build/bergamot-instrumented/airplane-mode-before.txt" || true
adb shell settings get global wifi_on > "$GITHUB_WORKSPACE/build/bergamot-instrumented/wifi-setting-before.txt" || true
adb shell settings get global mobile_data > "$GITHUB_WORKSPACE/build/bergamot-instrumented/mobile-data-before.txt" || true
adb shell dumpsys connectivity > "$GITHUB_WORKSPACE/build/bergamot-instrumented/connectivity-before.txt" || true
adb shell dumpsys wifi > "$GITHUB_WORKSPACE/build/bergamot-instrumented/wifi-before.txt" || true
adb shell getprop ro.build.version.sdk > "$GITHUB_WORKSPACE/build/bergamot-instrumented/api-level.txt" || true
adb shell getprop ro.build.fingerprint > "$GITHUB_WORKSPACE/build/bergamot-instrumented/build-fingerprint.txt" || true
if gradle connectedDebugAndroidTest --stacktrace; then
  test_status=0
else
  test_status=$?
fi
adb logcat -d -v threadtime -s BergamotOfflineTest:I > "$GITHUB_WORKSPACE/build/bergamot-instrumented/instrumentation.log"
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
