#!/usr/bin/env bash
set -euo pipefail

readonly WORK="${RUNNER_TEMP:-/tmp}/arabify-unitydatatools"
readonly FIXTURES="$WORK/fixtures"
readonly TOOL_DIR="$WORK/tool"
readonly REPORTS="$GITHUB_WORKSPACE/build/unitydatatool/reports"
readonly UPSTREAM_COMMIT="8b8e0fe65264ac6595c9d1eb37ab80ea9ab95c08"
readonly UPSTREAM_BASE="https://raw.githubusercontent.com/Unity-Technologies/UnityDataTools/$UPSTREAM_COMMIT/TestCommon/Data/AssetBundleTypeTreeVariations"
readonly TOOL_URL="https://github.com/Unity-Technologies/UnityDataTools/releases/download/v2.4.0/UnityDataTool-linux-x64.zip"
readonly TOOL_SHA256="0534036123353a2b69a93e7da9658f319e4056d9cb6ec45926d5449ab65bf22f"

mkdir -p "$FIXTURES" "$TOOL_DIR" "$REPORTS"

download_fixture() {
  local relative="$1"
  local expected_blob="$2"
  local output_name="$3"
  local output="$FIXTURES/$output_name"
  curl --fail --location --silent --show-error "$UPSTREAM_BASE/$relative" -o "$output"
  local actual_blob
  actual_blob="$(git hash-object "$output")"
  if [[ "$actual_blob" != "$expected_blob" ]]; then
    echo "Fixture integrity mismatch for $relative: expected $expected_blob, got $actual_blob" >&2
    exit 1
  fi
  echo "Verified UnityDataTools fixture: $relative ($actual_blob)" >&2
  printf '%s\n' "$output"
}

ZIP="$WORK/UnityDataTool-linux-x64.zip"
curl --fail --location --silent --show-error "$TOOL_URL" -o "$ZIP"
echo "$TOOL_SHA256  $ZIP" | sha256sum --check
unzip -q "$ZIP" -d "$TOOL_DIR"
TOOL="$(find "$TOOL_DIR" -type f -name UnityDataTool -print -quit)"
if [[ -z "$TOOL" ]]; then
  echo "UnityDataTool executable not found in pinned release archive" >&2
  exit 1
fi
chmod +x "$TOOL"
"$TOOL" --version | tee "$REPORTS/tool-version.txt"

V22="$(download_fixture v22/prefab_with_serializedreference.serializedfile 5c0f2173abe645b33b0505e3f11ec39a6464f8ee v22.serializedfile)"
V23_INLINE="$(download_fixture v23_Inline/prefab_with_serializedreference.serializedfile 620fe4d74e7fcb1063fe51b3561cd5e02b7c45f0 v23-inline.serializedfile)"
V23_EXTERNAL="$(download_fixture v23_extracted/prefab_with_serializedreference.serializedfile 81bd8f4f0cf3730d739d5bee3b253c0a85496238 v23-extracted.serializedfile)"
V23_TPK="$(download_fixture v23_extracted/AssetBundle.typetreedata 9f43178c3b8f207f7aaf5fc816fb31d78644f37a v23-extracted.typetreedata)"
V26="$(download_fixture v26/managedreferences.bundle 1e199f4fb41dc8efef0e94d14bd83f3dd22030fc v26-managedreferences.bundle)"
NO_TT="$(download_fixture AssetBundle-NoTypeTree/small.bundle 90cf841a1ed46120bede2579185c8dffbf20a0b5 no-typetree.bundle)"
NO_TT_VERSION="$(download_fixture AssetBundle-NoTypeTreeNoVersion/small.bundle 08d1016e4af7618769cf4c43ca9b96b2933b6325 no-typetree-no-version.bundle)"

run_serialized_reports() {
  local name="$1"
  local file="$2"
  "$TOOL" sf header "$file" --format json > "$REPORTS/$name-header.json"
  "$TOOL" sf metadata "$file" --format json > "$REPORTS/$name-metadata.json"
  "$TOOL" sf objectlist "$file" --format json > "$REPORTS/$name-objects.json"
  "$TOOL" sf externalrefs "$file" --format json > "$REPORTS/$name-externalrefs.json"
}

run_serialized_reports v22 "$V22"
run_serialized_reports v23-inline "$V23_INLINE"
run_serialized_reports v23-extracted "$V23_EXTERNAL"
"$TOOL" dump "$V22" --stdout --type MonoBehaviour > "$REPORTS/v22-monobehaviour.txt"
"$TOOL" dump "$V23_INLINE" --stdout --type MonoBehaviour > "$REPORTS/v23-inline-monobehaviour.txt"
"$TOOL" dump "$V23_EXTERNAL" --stdout --type MonoBehaviour --typetree-data "$V23_TPK" > "$REPORTS/v23-extracted-monobehaviour.txt"

"$TOOL" archive info "$V26" --format json > "$REPORTS/v26-archive-info.json"
"$TOOL" archive list "$V26" --format json > "$REPORTS/v26-archive-list.json"
"$TOOL" sf metadata "$V26" --format json > "$REPORTS/v26-metadata.json"
"$TOOL" sf objectlist "$V26" --format json > "$REPORTS/v26-objects.json"
"$TOOL" dump "$V26" --stdout --type MonoBehaviour > "$REPORTS/v26-monobehaviour.txt"

"$TOOL" archive info "$NO_TT" --format json > "$REPORTS/no-typetree-archive-info.json"
"$TOOL" sf metadata "$NO_TT" --format json > "$REPORTS/no-typetree-metadata.json"
"$TOOL" archive info "$NO_TT_VERSION" --format json > "$REPORTS/no-typetree-no-version-archive-info.json"
"$TOOL" sf metadata "$NO_TT_VERSION" --format json > "$REPORTS/no-typetree-no-version-metadata.json"

if "$TOOL" dump "$NO_TT" --stdout > /dev/null 2> "$REPORTS/no-typetree-dump-error.txt"; then
  echo "UnityDataTool unexpectedly dumped a bundle whose TypeTrees are disabled" >&2
  exit 1
fi

python3 - "$REPORTS" <<'PY'
import json, pathlib, sys
root = pathlib.Path(sys.argv[1])

def read(name):
    return json.loads((root / name).read_text())

checks = []
for label, expected_version in (("v22", 22), ("v23-inline", 23), ("v23-extracted", 23)):
    header = read(f"{label}-header.json")
    metadata = read(f"{label}-metadata.json")
    objects = read(f"{label}-objects.json")
    assert header["version"] == expected_version, (label, header)
    assert metadata["enableTypeTree"] is True, (label, metadata)
    assert len(objects) > 0, (label, objects)
    checks.append({
        "fixture": label,
        "formatVersion": header["version"],
        "typeTreeCount": metadata["typeTreeCount"],
        "serializedReferenceTypeTreeCount": metadata["serializedReferenceTypeTreeCount"],
        "objectCount": len(objects),
    })

v23_inline = read("v23-inline-metadata.json")
v23_external = read("v23-extracted-metadata.json")
assert all(x.get("inlineTypeTree", False) for x in v23_inline["typeTrees"]), v23_inline
assert any(not x.get("inlineTypeTree", True) for x in v23_external["typeTrees"]), v23_external
assert "ManagedReferencesRegistry" in (root / "v22-monobehaviour.txt").read_text()
assert "ManagedReferencesRegistry" in (root / "v23-inline-monobehaviour.txt").read_text()
assert "ManagedReferencesRegistry" in (root / "v23-extracted-monobehaviour.txt").read_text()

v26 = read("v26-metadata.json")
assert v26["sharedSubtreeCount"] > 0, v26
assert any(x.get("typeTreeFormatVersion") == 32 for x in v26["typeTrees"]), v26
assert "ManagedReferencesRegistry" in (root / "v26-monobehaviour.txt").read_text()
checks.append({
    "fixture": "v26",
    "sharedSubtreeCount": v26["sharedSubtreeCount"],
    "typeTreeFormatVersions": sorted({x.get("typeTreeFormatVersion") for x in v26["typeTrees"]}),
})

for label in ("no-typetree", "no-typetree-no-version"):
    metadata = read(f"{label}-metadata.json")
    assert metadata["enableTypeTree"] is False, (label, metadata)
    checks.append({"fixture": label, "enableTypeTree": False})

(root / "summary.json").write_text(json.dumps({
    "unityDataToolsVersion": "2.4.0",
    "upstreamCommit": "8b8e0fe65264ac6595c9d1eb37ab80ea9ab95c08",
    "checks": checks,
}, indent=2) + "\n")
print(json.dumps(checks, indent=2))
PY
