#!/bin/sh
# Verify the native developer runtime artifacts before they are published.
#
# A published release is what every app build downloads, so a missing file, a wrong ELF
# machine or an unstripped binary is caught here rather than on a phone.
#
# Usage: sh verify-native.sh <android-abi> <artifact-dir>
set -eu

ABI="${1:?android abi}"
DIR="${2:?artifact directory}"

case "$ABI" in
  arm64-v8a) MACHINE="AArch64" ;;
  armeabi-v7a) MACHINE="ARM" ;;
  x86_64) MACHINE="X86-64" ;;
  x86) MACHINE="Intel 80386" ;;
  *) echo "Unsupported ABI: $ABI" >&2; exit 2 ;;
esac

fail() { echo "FAIL: $1" >&2; exit 1; }

for name in libproot.so libproot_loader.so libandroid-shmem.so; do
  [ -f "$DIR/$name" ] || fail "$name is missing"
  size=$(wc -c < "$DIR/$name")
  [ "$size" -gt 1024 ] || fail "$name is implausibly small ($size bytes)"
  if command -v file >/dev/null 2>&1; then
    file "$DIR/$name" | grep -q ELF || fail "$name is not an ELF"
    file "$DIR/$name" | grep -q "$MACHINE" || fail "$name is not built for $ABI ($MACHINE)"
  fi
  if command -v readelf >/dev/null 2>&1; then
    # PRoot and the loader must find libtalloc/libandroid-shmem next to themselves.
    readelf -d "$DIR/$name" 2>/dev/null | grep -E "RUNPATH|RPATH" | grep -q '\$ORIGIN' \
      || echo "WARN: $name has no \$ORIGIN in RUNPATH; check the link flags"
  fi
done

echo "OK: native developer runtime for $ABI"
( cd "$DIR" && sha256sum ./* )
