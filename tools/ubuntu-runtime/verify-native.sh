#!/bin/sh
# Verify the native developer runtime artifacts before they are packaged into the APK.
#
# A missing file, a wrong ELF machine or a tiny placeholder is caught here rather than on a phone.
#
# Usage: sh verify-native.sh <android-abi> <artifact-dir>
set -eu

ABI="${1:?android abi}"
DIR="${2:?artifact directory}"

# `file` and `readelf` spell the same machine differently — "ARM aarch64" against "AArch64",
# "x86-64" against "X86-64" — so each tool gets the spelling it actually prints. The readelf
# machine is the precise check; the `file` one is a sanity net for when only `file` is present.
case "$ABI" in
  arm64-v8a) FILE_MACHINE="aarch64"; ELF_MACHINE="AArch64" ;;
  armeabi-v7a) FILE_MACHINE="ARM"; ELF_MACHINE="ARM" ;;
  x86_64) FILE_MACHINE="x86-64"; ELF_MACHINE="X86-64" ;;
  x86) FILE_MACHINE="Intel 80386"; ELF_MACHINE="Intel 80386" ;;
  *) echo "Unsupported ABI: $ABI" >&2; exit 2 ;;
esac

fail() { echo "FAIL: $1" >&2; exit 1; }

[ -d "$DIR" ] || fail "artifact directory $DIR does not exist"

# Required at runtime in nativeLibraryDir. libtalloc.so is optional because talloc is linked
# statically into libproot.so. libproot_loader32.so is optional (32-bit guests only).
REQUIRED="libproot.so libproot_loader.so libandroid-shmem.so"

for name in $REQUIRED; do
  path="$DIR/$name"
  [ -f "$path" ] || fail "$name is missing from $DIR"
  size=$(wc -c < "$path")
  [ "$size" -gt 1024 ] || fail "$name is implausibly small ($size bytes)"
  if command -v file >/dev/null 2>&1; then
    info=$(file "$path")
    echo "$info" | grep -q ELF || fail "$name is not an ELF: $info"
    echo "$info" | grep -q "$FILE_MACHINE" || fail "$name is not built for $ABI ($FILE_MACHINE): $info"
  fi
  if command -v readelf >/dev/null 2>&1; then
    header=$(readelf -h "$path" 2>/dev/null || true)
    echo "$header" | grep -q "Machine:" || fail "$name has no ELF machine header"
    echo "$header" | grep -q "$ELF_MACHINE" || fail "$name ELF machine is not $ABI: $header"
  elif command -v llvm-readelf >/dev/null 2>&1; then
    header=$(llvm-readelf -h "$path" 2>/dev/null || true)
    echo "$header" | grep -q "$ELF_MACHINE" || echo "WARN: llvm-readelf could not confirm $name machine"
  fi
done

if [ -f "$DIR/libtalloc.so" ]; then
  echo "OK: optional libtalloc.so present"
else
  echo "NOTE: libtalloc.so not produced (talloc is expected to be static inside libproot.so)"
fi

if [ -f "$DIR/libproot_loader32.so" ]; then
  echo "OK: optional libproot_loader32.so present"
fi

echo "OK: native developer runtime for $ABI"
( cd "$DIR" && sha256sum ./* )
