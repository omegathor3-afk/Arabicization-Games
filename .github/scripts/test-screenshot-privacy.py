#!/usr/bin/env python3
"""Repository-wide guard: CI screenshots stay in Actions artifacts only."""
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
SCRIPT_WORKFLOWS = {
    ".github/scripts/run-real-game-localization.sh": (".github/workflows/android.yml", "build/test-game/localized/"),
    ".github/scripts/run-mindustry-slot.sh": (".github/workflows/mindustry-slot.yml", "build/test-game/localized/"),
    ".github/scripts/run-unciv-slot.sh": (".github/workflows/unciv-slot.yml", "build/test-game/localized/"),
    ".github/scripts/run-andorstrail-resources.sh": (".github/workflows/andorstrail-resources.yml", "build/test-game/localized/"),
    ".github/scripts/run-mindustry-real-translation.sh": (".github/workflows/mindustry-real-translation.yml", "build/test-game/localized/"),
    ".github/scripts/run-pixelwheels-gettext.sh": (".github/workflows/pixelwheels-gettext.yml", "build/test-game/localized/"),
    ".github/scripts/run-arabify-ui-screenshot.sh": (".github/workflows/arabify-ui.yml", "build/arabify-ui/"),
}
FORBIDDEN_HOSTS = ("0x0.st", "catbox.moe", "temp.sh", "file.io", "transfer.sh")

for relative_script, (relative_workflow, artifact_path) in SCRIPT_WORKFLOWS.items():
    script_path = ROOT / relative_script
    workflow_path = ROOT / relative_workflow
    script = script_path.read_text(encoding="utf-8")
    workflow = workflow_path.read_text(encoding="utf-8")

    for marker in (*FORBIDDEN_HOSTS, "SCREENSHOT-B64", "upload_shot", "--upload-file"):
        if marker in script:
            raise SystemExit(f"Screenshot privacy regression in {relative_script}: {marker!r}")
    if re.search(r"(?m)^\s*curl\b.*(?:\s-F\b|\s--form\b|\s--upload-file\b)", script):
        raise SystemExit(f"Screenshot privacy regression in {relative_script}: curl upload command")
    if relative_script != ".github/scripts/run-arabify-ui-screenshot.sh" and "retain_shot" not in script:
        raise SystemExit(f"{relative_script} must retain screenshots in the workflow artifact")
    if artifact_path not in workflow or "actions/upload-artifact@" not in workflow:
        raise SystemExit(f"{relative_workflow} does not archive {artifact_path}")
    if relative_script == ".github/scripts/run-unciv-slot.sh":
        unciv_lines = script.splitlines()
        failure_line = next((
            index for index, line in enumerate(unciv_lines)
            if line.strip() == 'note_failure "no Arabic text and no Arabic language entry visible on the Unciv screen; see unciv-visible-screen-text.txt in the workflow artifact"'
        ), None)
        if failure_line is None or failure_line + 1 >= len(unciv_lines) or unciv_lines[failure_line + 1].strip() != "launch_status=1":
            raise SystemExit("Unciv no-Arabic UI branch must set launch_status=1 immediately after recording the failure.")
    subprocess.run(["bash", "-n", str(script_path)], check=True)

print(f"Screenshot privacy check passed for {len(SCRIPT_WORKFLOWS)} scripts: no third-party uploads or base64 log payloads; artifact paths and shell syntax verified.")
