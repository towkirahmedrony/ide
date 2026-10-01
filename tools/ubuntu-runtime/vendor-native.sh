#!/bin/sh
# Copy built PRoot native artifacts into the Android jniLibs source set so AGP packages
# them into the APK at lib/<abi>/*.so (extracted to nativeLibraryDir on install).
#
# Usage: sh vendor-native.sh <android-abi> <artifact-dir> [jniLibs-root]
set -eu

ABI="${1:?android abi}"
DIR="${2:?artifact directory}"
DEST="${3:-ubuntu-runtime/src/main/jniLibs}"

fail() { echo "FAIL: $1" >&2; exit 1; }

[ -d "$DIR" ] || fail "artifact directory $DIR does not exist"

TARGET="$DEST/$ABI"
mkdir -p "$TARGET"

REQUIRED="libproot.so libproot_loader.so libandroid-shmem.so"
for name in $REQUIRED; do
  [ -f "$DIR/$name" ] || fail "$name is missing from $DIR; cannot vendor into jniLibs"
  cp "$DIR/$name" "$TARGET/$name"
done

# Optional extras, copied when the native build produced them.
for name in libtalloc.so libproot_loader32.so; do
  if [ -f "$DIR/$name" ]; then
    cp "$DIR/$name" "$TARGET/$name"
  fi
done

echo "Vendored into $TARGET:"
ls -l "$TARGET"
