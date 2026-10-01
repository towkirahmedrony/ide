#!/bin/sh
# Build the native developer runtime (PRoot + loader + talloc + android-shmem) for one Android
# ABI, cross-compiled with the Android NDK.
#
# Usage: NDK=/path/to/ndk sh build-proot.sh <android-abi> <output-dir>
#
# Everything is pinned: the PRoot revision is a commit, not a branch. Output names are the
# Android library names AGP packages into nativeLibraryDir (`lib*.so`).
#
# PRoot itself is an ELF executable (not a JNI library). It is named libproot.so so Android
# extracts it into applicationInfo.nativeLibraryDir, which is the only location targetSdk 37
# may execve(). The loader is a static freestanding ELF, named libproot_loader.so for the
# same reason, and is selected at runtime via PROOT_LOADER.
set -eu

ABI="${1:?android abi, e.g. arm64-v8a}"
OUT="${2:?output directory}"
: "${NDK:?NDK must point at an Android NDK}"

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

PROOT_REPO="https://github.com/termux/proot"
PROOT_REV="d4d2a19081c3c07f75250e4ce2980b9fa2f5720f"
PROOT_TAG="v5.1.107.95"
TALLOC_VERSION="2.4.3"
TALLOC_SHA256="dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd"
TALLOC_URL="https://www.samba.org/ftp/talloc/talloc-${TALLOC_VERSION}.tar.gz"
SHMEM_REPO="https://github.com/termux/libandroid-shmem"
SHMEM_REV="7f0bd7e25dbdd146265aff7c6a890029e374622d"

case "$ABI" in
  arm64-v8a) TRIPLE="aarch64-linux-android"; API=26; MACHINE="AArch64" ;;
  armeabi-v7a) TRIPLE="armv7a-linux-androideabi"; API=26; MACHINE="ARM" ;;
  x86_64) TRIPLE="x86_64-linux-android"; API=26; MACHINE="X86-64" ;;
  x86) TRIPLE="i686-linux-android"; API=26; MACHINE="Intel 80386" ;;
  *) echo "Unsupported ABI: $ABI" >&2; exit 2 ;;
esac

HOST_TAG="linux-x86_64"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
CC="$TOOLCHAIN/bin/${TRIPLE}${API}-clang"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"
OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy"
OBJDUMP="$TOOLCHAIN/bin/llvm-objdump"
READELF="$TOOLCHAIN/bin/llvm-readelf"

for tool in "$CC" "$AR" "$RANLIB" "$STRIP" "$OBJCOPY" "$OBJDUMP" "$READELF"; do
  if [ ! -x "$tool" ]; then
    echo "NDK tool is missing: $tool" >&2
    exit 1
  fi
done

echo "NDK toolchain: $TOOLCHAIN"
echo "CC: $CC"
echo "PRoot: $PROOT_REPO @$PROOT_REV ($PROOT_TAG)"
echo "talloc: $TALLOC_VERSION"
echo "libandroid-shmem: $SHMEM_REV"

WORK="$(mktemp -d)"
mkdir -p "$OUT"
trap 'rm -rf "$WORK"' EXIT

# Host aliases the GNUmakefile invokes as unprefixed tool names.
mkdir -p "$WORK/host-bin"
ln -s "$READELF" "$WORK/host-bin/readelf"
ln -s "$OBJCOPY" "$WORK/host-bin/objcopy"
ln -s "$OBJDUMP" "$WORK/host-bin/objdump"
ln -s "$STRIP" "$WORK/host-bin/strip"
PATH="$WORK/host-bin:$PATH"
export PATH

# ---------------------------------------------------------------------------------------------
# talloc — PRoot links this. Built static (-fPIC) so a missing libtalloc.so cannot break
# libproot.so. A shared copy is still installed when waf produces one.
# ---------------------------------------------------------------------------------------------
echo "==> talloc $TALLOC_VERSION"
(
  cd "$WORK"
  curl -fsSL "$TALLOC_URL" -o talloc.tar.gz
  actual=$(sha256sum talloc.tar.gz | awk '{print $1}')
  if [ "$actual" != "$TALLOC_SHA256" ]; then
    echo "talloc tarball SHA-256 mismatch: expected $TALLOC_SHA256 got $actual" >&2
    exit 1
  fi
  tar -xzf talloc.tar.gz
  cd "talloc-${TALLOC_VERSION}"
  cp "$SCRIPT_DIR/talloc-cross-answers.txt" cross-answers.txt
  CC="$CC" AR="$AR" RANLIB="$RANLIB" CFLAGS="-O2 -fPIC" \
    ./configure \
      --prefix="$WORK/talloc-install" \
      --disable-python \
      --disable-rpath \
      --cross-compile \
      --cross-answers=cross-answers.txt
  make -j"$(nproc)"
  make install
  # Termux also archives the objects as libtalloc.a; do the same when waf did not.
  if [ ! -f "$WORK/talloc-install/lib/libtalloc.a" ]; then
    objs=$(find bin -name 'talloc*.o' 2>/dev/null || true)
    if [ -n "$objs" ]; then
      # shellcheck disable=SC2086
      "$AR" rcu "$WORK/talloc-install/lib/libtalloc.a" $objs
      "$RANLIB" "$WORK/talloc-install/lib/libtalloc.a"
    fi
  fi
)

if [ ! -f "$WORK/talloc-install/include/talloc.h" ]; then
  echo "talloc headers were not installed" >&2
  exit 1
fi

TALLOC_CFLAGS="-I$WORK/talloc-install/include"
if [ -f "$WORK/talloc-install/lib/libtalloc.a" ]; then
  TALLOC_LIBS="$WORK/talloc-install/lib/libtalloc.a"
else
  TALLOC_LIBS="-L$WORK/talloc-install/lib -ltalloc"
fi

if [ -e "$WORK/talloc-install/lib/libtalloc.so" ]; then
  cp -L "$WORK/talloc-install/lib/libtalloc.so" "$OUT/libtalloc.so"
elif ls "$WORK/talloc-install/lib"/libtalloc.so.* >/dev/null 2>&1; then
  src=$(ls "$WORK/talloc-install/lib"/libtalloc.so.* | head -n 1)
  cp -L "$src" "$OUT/libtalloc.so"
fi

# ---------------------------------------------------------------------------------------------
# libandroid-shmem — System V shared memory for guest processes on Android.
# ---------------------------------------------------------------------------------------------
echo "==> libandroid-shmem $SHMEM_REV"
(
  cd "$WORK"
  git clone "$SHMEM_REPO" shmem
  cd shmem
  git checkout "$SHMEM_REV"
  "$CC" -O2 -fPIC -shared -std=c11 -Wall -Wextra \
    -Wl,--version-script=exports.txt \
    -o "$OUT/libandroid-shmem.so" \
    shmem.c -llog -landroid
)

# ---------------------------------------------------------------------------------------------
# PRoot + unbundled loader
#
# The loader MUST live as a real file under nativeLibraryDir. The bundled-loader path extracts
# it to PROOT_TMP_DIR (app-private storage) and Android will not execve() that. Compile with
# PROOT_UNBUNDLE_LOADER so get_loader_path() uses getenv("PROOT_LOADER").
#
# src/GNUmakefile builds both `proot` and `loader/loader`. There is no top-level `loader/`
# makefile. aarch64 defines HAS_LOADER_32BIT; building loader-m32 with `-m32` fails on the
# NDK and Ubuntu ARM64 does not start 32-bit guests, so HAS_LOADER_32BIT is disabled.
# ---------------------------------------------------------------------------------------------
echo "==> PRoot $PROOT_REV"
(
  cd "$WORK"
  git clone "$PROOT_REPO" proot
  cd proot
  git checkout "$PROOT_REV"
  git rev-parse HEAD > "$OUT/proot-source.rev"

  # Dummy fallback directory: the runtime always sets PROOT_LOADER to nativeLibraryDir.
  # aarch64 arch.h sets HAS_LOADER_32BIT, which builds loader-m32 with -m32. The Android
  # NDK aarch64 toolchain cannot do that. Ubuntu ARM64 does not start 32-bit guests, so
  # the 32-bit loader is disabled for this ABI. Do not pass HAS_LOADER_32BIT= on the make
  # command line: GNU make treats an empty command-line variable as still defined.
  if [ "$ABI" = "arm64-v8a" ]; then
    sed -i 's/#define HAS_LOADER_32BIT true/\/* HAS_LOADER_32BIT disabled: Android NDK aarch64 *\//' src/arch.h
  fi

  make -C src \
    V=1 \
    CC="$CC" \
    LD="$CC" \
    AR="$AR" \
    STRIP="$STRIP" \
    OBJCOPY="$OBJCOPY" \
    OBJDUMP="$OBJDUMP" \
    PROOT_UNBUNDLE_LOADER=/nonexistent/agentx-proot-loader \
    PROOT_WITH_LIBANDROID_SHMEM=true \
    CPPFLAGS="-DARG_MAX=131072 -DVERSION=\\\"${PROOT_TAG}\\\" -DANDROID" \
    CFLAGS="-O2 -fPIC ${TALLOC_CFLAGS} -DANDROID" \
    LDFLAGS="-Wl,-z,noexecstack -Wl,-rpath,\$\$ORIGIN -Wl,--enable-new-dtags -L${OUT} -landroid-shmem -llog ${TALLOC_LIBS}"

  if [ ! -f src/proot ]; then
    echo "PRoot binary was not produced (src/proot)" >&2
    exit 1
  fi
  if [ ! -f src/loader/loader ]; then
    echo "PRoot loader was not produced (src/loader/loader)" >&2
    exit 1
  fi

  cp src/proot "$OUT/libproot.so"
  cp src/loader/loader "$OUT/libproot_loader.so"
)

"$STRIP" --strip-unneeded "$OUT/libproot.so" || true
"$STRIP" --strip-unneeded "$OUT/libproot_loader.so" || true
[ -f "$OUT/libproot_loader32.so" ] && "$STRIP" --strip-unneeded "$OUT/libproot_loader32.so" || true
"$STRIP" --strip-unneeded "$OUT/libandroid-shmem.so" || true
[ -f "$OUT/libtalloc.so" ] && "$STRIP" --strip-unneeded "$OUT/libtalloc.so" || true

{
  echo "abi=$ABI"
  echo "machine=$MACHINE"
  echo "proot_repo=$PROOT_REPO"
  echo "proot_rev=$PROOT_REV"
  echo "proot_tag=$PROOT_TAG"
  echo "talloc_version=$TALLOC_VERSION"
  echo "talloc_sha256=$TALLOC_SHA256"
  echo "libandroid_shmem_rev=$SHMEM_REV"
  echo "ndk=$NDK"
  echo "triple=$TRIPLE"
  echo "api=$API"
} > "$OUT/BUILDINFO"

echo "==> outputs"
ls -l "$OUT"
( cd "$OUT" && sha256sum ./* > SHA256SUMS && cat SHA256SUMS )
