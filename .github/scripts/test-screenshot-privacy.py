#!/usr/bin/env python3
"""Prevent CI screenshots from leaving the repository's Actions artifacts."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[2]
script_path = root / ".github/scripts/run-real-game-localization.sh"
workflow_path = root / ".github/workflows/android.yml"
script = script_path.read_text(encoding="utf-8")
workflow = workflow_path.read_text(encoding="utf-8")

for forbidden in (
    "0x0.st",
    "catbox.moe",
    "temp.sh",
    "file.io",
    "transfer.sh",
    "SCREENSHOT-B64",
    "--upload-file",
):
    if forbidden in script:
        raise SystemExit(f"Screenshot privacy regression: forbidden uploader marker {forbidden!r}")
if "upload_shot" in script or "retain_shot" not in script:
    raise SystemExit("Screenshot reporting must retain files locally; no upload helper is allowed.")
if "build/test-game/localized/" not in workflow or "build/maestro/" not in workflow:
    raise SystemExit("The Android Actions artifact must retain both game and ARABIFY screenshots.")

subprocess.run(["bash", "-n", str(script_path)], check=True)
print("Screenshot privacy check passed: images stay in the GitHub Actions artifact; shell syntax is valid.")
