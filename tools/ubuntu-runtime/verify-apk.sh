#!/bin/sh
# Verify that an AgentX APK contains the ARM64 PRoot native libraries under lib/arm64-v8a/.
#
# These files must be packaged as Android JNI libs so the installer extracts them into
# applicationInfo.nativeLibraryDir. A source-tree copy is not enough.
#
# Usage: sh verify-apk.sh <apk-path>
set -eu

APK="${1:?apk path}"
ABI="${2:-arm64-v8a}"

fail() { echo "FAIL: $1" >&2; exit 1; }

[ -f "$APK" ] || fail "APK not found: $APK"

REQUIRED="libproot.so libproot_loader.so libandroid-shmem.so"

LISTING=$(mktemp)
trap 'rm -f "$LISTING"' EXIT

if command -v unzip >/dev/null 2>&1; then
  unzip -l "$APK" > "$LISTING"
else
  fail "unzip is required to inspect the APK"
fi

echo "==> native libraries in $APK"
grep -E "lib/[^/]+/lib.*\.so$" "$LISTING" || true

for name in $REQUIRED; do
  grep -q "lib/${ABI}/${name}$" "$LISTING" || fail "$name is missing from lib/${ABI}/ in $APK"
  # Reject a zero-byte placeholder: unzip -l prints size in the first column.
  size=$(awk -v needle="lib/${ABI}/${name}" '$0 ~ needle {print $1; exit}' "$LISTING")
  [ -n "$size" ] || fail "could not read size of lib/${ABI}/${name}"
  [ "$size" -gt 1024 ] || fail "lib/${ABI}/${name} is implausibly small ($size bytes)"
done

if grep -q "lib/x86_64/libproot.so$" "$LISTING"; then
  echo "WARN: x86_64 libproot.so is also packaged; ARM64 runtime must still use lib/arm64-v8a/"
fi

echo "OK: $APK contains required $ABI PRoot native libraries"
