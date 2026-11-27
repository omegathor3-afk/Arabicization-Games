#!/usr/bin/env bash
# Stages the CI-verified Mozilla en-ar Bergamot package into the app's MAIN
# assets so the shipping APK carries first-install offline translation
# weights (Omega's decision, 2026-11-27). Expects build/moz-models/en-ar
# from audit-mozilla-arabic-models.py, which hash-verifies every download
# against the pinned Mozilla registry. Weights are staged at build time and
# never committed to git (see .gitignore). Only the production direction is
# packaged; ar-en stays test-only for the round-trip audit.
set -euo pipefail

SRC="build/moz-models/en-ar"
DST="app/src/main/assets/arabify-models/en-ar"

test -s "$SRC/config.yml"
rm -rf app/src/main/assets/arabify-models
mkdir -p "$DST"
cp -a "$SRC/." "$DST/"
# Only decompressed payloads belong in assets; a .gz next to its unpacked
# file makes AAPT2 see duplicate assets and doubles the APK cost.
find "$DST" -type f -name '*.gz' -delete

python3 - <<'PY'
import hashlib
import json
from pathlib import Path

root = Path("app/src/main/assets/arabify-models/en-ar")
config = root / "config.yml"
if not config.is_file():
    raise SystemExit(f"Missing model config: {config}")

referenced = []
active = False
for line in config.read_text(encoding="utf-8").splitlines():
    if line.strip() in {"models:", "vocabs:", "shortlist:"}:
        active = True
        continue
    if line and not line[0].isspace():
        active = False
    if active and line.lstrip().startswith("- "):
        value = line.split("-", 1)[1].strip()
        if value != "false":
            referenced.append(value)

files = []
for name in sorted(set(referenced)):
    payload = root / name
    if not payload.is_file() or payload.stat().st_size == 0:
        raise SystemExit(f"Missing/empty bundled model file: {payload}")
    digest = hashlib.sha256()
    with payload.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    files.append({"relativePath": name, "sha256": digest.hexdigest()})

manifest = {
    "packageId": "mozilla-firefox-translations-en-ar-base-memory",
    "engine": "bergamot-translator",
    "engineVersion": "9271618ebbdc5d21ac4dc4df9e72beb7ce644774",
    "engineLicense": "MPL-2.0",
    "modelVersion": "v3.0",
    "modelLicense": "MPL-2.0",
    "tokenizerLicense": "Apache-2.0",
    "sourceUrl": "https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data/db/models.json",
    "sourceLanguage": "en",
    "targetLanguage": "ar",
    "files": files,
}
(root / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
total = sum((root / entry["relativePath"]).stat().st_size for entry in files)
print(f"staged bundled en-ar model: {len(files)} hash-manifested files, {total} bytes")
PY
