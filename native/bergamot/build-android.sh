#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PIN="9271618ebbdc5d21ac4dc4df9e72beb7ce644774"
MARIAN_PIN="2781d735d4a10dca876d61be587afdab2726293c"
SOURCE="${BERGAMOT_SOURCE_DIR:-$ROOT/.build/bergamot-translator}"
ABI="${ANDROID_ABI:-arm64-v8a}"
case "$ABI" in
  arm64-v8a) DEFAULT_ARCH=armv8-a; LIBCXX_ARCH=aarch64-linux-android ;;
  x86_64) DEFAULT_ARCH=x86-64-v2; LIBCXX_ARCH=x86_64-linux-android ;;
  *) echo "Unsupported ANDROID_ABI: $ABI" >&2; exit 5 ;;
esac
BUILD="$ROOT/.build/bergamot-android-$ABI"
CPU_ARCH="${BUILD_ARCH:-$DEFAULT_ARCH}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" || ! -f "$NDK/build/cmake/android.toolchain.cmake" ]]; then
  echo "ANDROID_NDK_HOME must point to an installed Android NDK" >&2
  exit 2
fi
command -v git >/dev/null
command -v cmake >/dev/null
command -v ninja >/dev/null
mkdir -p "$(dirname "$SOURCE")" "$ROOT/.build"
if [[ ! -d "$SOURCE/.git" ]]; then
  git clone https://github.com/browsermt/bergamot-translator.git "$SOURCE"
fi
git -C "$SOURCE" fetch --no-tags origin "$PIN"
git -C "$SOURCE" checkout --detach "$PIN"
git -C "$SOURCE" submodule sync --recursive
git -C "$SOURCE" submodule update --init --recursive
ACTUAL="$(git -C "$SOURCE" rev-parse HEAD)"
[[ "$ACTUAL" == "$PIN" ]] || { echo "Unexpected Bergamot revision: $ACTUAL" >&2; exit 3; }
MARIAN_ACTUAL="$(git -C "$SOURCE/3rd_party/marian-dev" rev-parse HEAD)"
[[ "$MARIAN_ACTUAL" == "$MARIAN_PIN" ]] || { echo "Unexpected Marian revision: $MARIAN_ACTUAL" >&2; exit 4; }
if git -C "$SOURCE" submodule status --recursive | grep -Eq '^[+-]'; then
  echo "A Bergamot submodule is not at its pinned gitlink revision" >&2
  git -C "$SOURCE" submodule status --recursive >&2
  exit 4
fi
# Marian's upstream enables Ruy SGEMM by default only for ARM. Without an
# explicit backend, x86_64 can compile but abort inside sgemm on first inference.
cmake -S "$ROOT/native/bergamot" -B "$BUILD" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" \
  -DANDROID_PLATFORM=android-28 \
  -DANDROID_STL=c++_shared \
  -DUSE_RUY:BOOL=ON \
  -DUSE_RUY_SGEMM:BOOL=ON \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_HAVE_LIBC_PTHREAD=ON \
  -DBUILD_ARCH="$CPU_ARCH" \
  -DCMAKE_EXPORT_COMPILE_COMMANDS=ON \
  -DBERGAMOT_SOURCE_DIR="$SOURCE"
CACHE_FILE="$BUILD/CMakeCache.txt"
grep -Fqx 'USE_RUY:BOOL=ON' "$CACHE_FILE" || { echo "Ruy must be enabled for every Android ABI" >&2; exit 6; }
grep -Fqx 'USE_RUY_SGEMM:BOOL=ON' "$CACHE_FILE" || { echo "Ruy SGEMM must be enabled for every Android ABI" >&2; exit 7; }
python3 - "$BUILD/compile_commands.json" "$SOURCE" "$ABI" <<'PY'
import json
import pathlib
import shlex
import sys

database = pathlib.Path(sys.argv[1])
source_root = pathlib.Path(sys.argv[2]).resolve()
abi = sys.argv[3]
expected = (source_root / "3rd_party/marian-dev/src/3rd_party/faiss/VectorTransform.cpp").resolve(strict=True)
if not database.is_file():
    raise SystemExit(f"Missing CMake compile database: {database}")
entries = json.loads(database.read_text())
if not any(
    "-DUSE_RUY_SGEMM=1" in " ".join(
        entry.get("arguments") or shlex.split(entry.get("command", ""))
    )
    for entry in entries
):
    raise SystemExit("Compile database does not prove USE_RUY_SGEMM=1 is active")
matches = []
for entry in entries:
    source = pathlib.Path(entry.get("file", ""))
    if not source.is_absolute():
        source = pathlib.Path(entry.get("directory", ".")) / source
    if source.resolve() == expected:
        matches.append(entry)
if len(matches) != 1:
    raise SystemExit(f"Expected exactly one compile entry for {expected}, found {len(matches)}")
entry = matches[0]
arguments = entry.get("arguments") or shlex.split(entry.get("command", ""))
if not arguments:
    raise SystemExit("FAISS compile entry has no compiler arguments")
pairs = [arguments[index:index + 2] for index in range(len(arguments) - 1)]
forced_header = ["-include", "pmmintrin.h"]
targets = []
for index, arg in enumerate(arguments):
    if arg.startswith("--target="):
        targets.append(arg.split("=", 1)[1])
    elif arg in ("-target", "--target") and index + 1 < len(arguments):
        targets.append(arguments[index + 1])
cache = (database.parent / "CMakeCache.txt").read_text(errors="replace")
if abi == "x86_64":
    if forced_header not in pairs:
        raise SystemExit("Exact FAISS source command lacks adjacent '-include pmmintrin.h'")
    if not any(target.startswith("x86_64-") and "android" in target for target in targets):
        raise SystemExit(f"x86_64 Android NDK target triple missing: {targets}")
    if not any(line.startswith("ANDROID_ABI:") and line.endswith("=x86_64") for line in cache.splitlines()):
        raise SystemExit("CMakeCache does not prove ANDROID_ABI=x86_64")
    if any("aarch64" in target for target in targets):
        raise SystemExit(f"Unexpected ARM target in x86_64 compile command: {targets}")
else:
    if forced_header in pairs:
        raise SystemExit("x86_64-only pmmintrin.h option leaked into ARM64 FAISS command")
    if not any(target.startswith("aarch64-") and "android" in target for target in targets):
        raise SystemExit(f"ARM64 Android NDK target triple missing: {targets}")
    if not any(line.startswith("ANDROID_ABI:") and line.endswith("=arm64-v8a") for line in cache.splitlines()):
        raise SystemExit("CMakeCache does not prove ANDROID_ABI=arm64-v8a")
print(f"Verified exact source: {expected}")
print(f"Verified ABI: {abi}; compiler target(s): {targets}")
print("Verified forced include:", forced_header in pairs)
print("Compiler command:", " ".join(arguments))
PY
cmake --build "$BUILD" --target arabify_bergamot --parallel 2
OUT="$ROOT/app/src/main/jniLibs/$ABI"
mkdir -p "$OUT"
find "$BUILD" -type f -name 'libarabify_bergamot.so' -exec cp {} "$OUT/" \; -quit
test -s "$OUT/libarabify_bergamot.so"
cp "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/${LIBCXX_ARCH}/libc++_shared.so" "$OUT/"
sha256sum "$OUT"/*.so
