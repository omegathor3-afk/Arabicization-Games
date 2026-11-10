#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PIN="9271618ebbdc5d21ac4dc4df9e72beb7ce644774"
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
if git -C "$SOURCE" submodule status --recursive | grep -Eq '^[+-]'; then
  echo "A Bergamot submodule is not at its pinned gitlink revision" >&2
  git -C "$SOURCE" submodule status --recursive >&2
  exit 4
fi

cmake -S "$ROOT/native/bergamot" -B "$BUILD" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" \
  -DANDROID_PLATFORM=android-28 \
  -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_HAVE_LIBC_PTHREAD=ON \
  -DBUILD_ARCH="$CPU_ARCH" \
  -DCMAKE_EXPORT_COMPILE_COMMANDS=ON \
  -DBERGAMOT_SOURCE_DIR="$SOURCE"

# Verify the exact FAISS translation unit receives the x86_64 SSE header.
if [[ "$ABI" == "x86_64" ]]; then
  python3 - "$BUILD/compile_commands.json" <<'PY'
import json
import pathlib
import shlex
import sys

database = pathlib.Path(sys.argv[1])
if not database.is_file():
    raise SystemExit(f"Missing CMake compile database: {database}")
commands = json.loads(database.read_text())
matches = [entry for entry in commands if pathlib.Path(entry.get("file", "")).name == "VectorTransform.cpp"]
if not matches:
    raise SystemExit("VectorTransform.cpp is absent from the x86_64 compile database")
for entry in matches:
    arguments = entry.get("arguments") or shlex.split(entry.get("command", ""))
    if not any(arguments[index:index + 2] == ["-include", "pmmintrin.h"] for index in range(len(arguments) - 1)):
        raise SystemExit("FAISS VectorTransform.cpp is missing adjacent '-include pmmintrin.h' arguments: " + " ".join(arguments))
    print("Verified FAISS SSE header flag:", " ".join(arguments))
PY
fi

cmake --build "$BUILD" --target arabify_bergamot --parallel 2

OUT="$ROOT/app/src/main/jniLibs/$ABI"
mkdir -p "$OUT"
find "$BUILD" -type f -name 'libarabify_bergamot.so' -exec cp {} "$OUT/" \; -quit
test -s "$OUT/libarabify_bergamot.so"
cp "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/${LIBCXX_ARCH}/libc++_shared.so" "$OUT/"
sha256sum "$OUT"/*.so
