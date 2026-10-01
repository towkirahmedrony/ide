#!/bin/sh
# Build the native developer runtime (PRoot + loader + talloc + android-shmem) for one Android
# ABI, cross-compiled with the NDK.
#
# Usage: NDK=/path/to/ndk sh build-proot.sh <android-abi> <output-dir>
#
# Everything is pinned: the PRoot revision is a commit, not a branch, so the produced binaries
# are traceable to an upstream source and a license. The output names are the Android library
# names AGP packages into nativeLibraryDir.
set -eu

ABI="${1:?android abi, e.g. arm64-v8a}"
OUT="${2:?output directory}"
: "${NDK:?NDK must point at an Android NDK}"

PROOT_REPO="https://github.com/termux/proot"
PROOT_REV="d4d2a19081c3c07f75250e4ce2980b9fa2f5720f"
TALLOC_VERSION="2.4.2"
SHMEM_REPO="https://github.com/termux/libandroid-shmem"

case "$ABI" in
  arm64-v8a) TRIPLE="aarch64-linux-android"; API=26 ;;
  armeabi-v7a) TRIPLE="armv7a-linux-androideabi"; API=26 ;;
  x86_64) TRIPLE="x86_64-linux-android"; API=26 ;;
  x86) TRIPLE="i686-linux-android"; API=26 ;;
  *) echo "Unsupported ABI: $ABI" >&2; exit 2 ;;
esac

HOST_TAG="linux-x86_64"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
CC="$TOOLCHAIN/bin/${TRIPLE}${API}-clang"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"
SYSROOT="$TOOLCHAIN/sysroot"

echo "NDK toolchain: $TOOLCHAIN"
echo "CC: $CC"

WORK="$(mktemp -d)"
mkdir -p "$OUT"
trap 'rm -rf "$WORK"' EXIT

# ---------------------------------------------------------------------------------------------
# talloc — PRoot uses it for memory trees. Built static so PRoot and the loader carry it.
# ---------------------------------------------------------------------------------------------
echo "==> talloc $TALLOC_VERSION"
(
  cd "$WORK"
  curl -fsSL "https://download.samba.org/pub/talloc/talloc-${TALLOC_VERSION}.tar.gz" -o talloc.tar.gz
  tar -xzf talloc.tar.gz
  cd "talloc-${TALLOC_VERSION}"
  ./configure \
    --host="$TRIPLE" \
    --prefix="$WORK/talloc-install" \
    --disable-python \
    --disable-rpath \
    CC="$CC" \
    AR="$AR" \
    RANLIB="$RANLIB" \
    CFLAGS="-O2 -fPIC"
  make -j"$(nproc)"
  make install
)

# ---------------------------------------------------------------------------------------------
# libandroid-shmem — System V shared memory for guest processes on Android.
# ---------------------------------------------------------------------------------------------
echo "==> libandroid-shmem"
(
  cd "$WORK"
  git clone --depth 1 "$SHMEM_REPO" shmem
  cd shmem
  "$CC" -O2 -fPIC -shared -o "$OUT/libandroid-shmem.so" shmem.c -llog
)

# ---------------------------------------------------------------------------------------------
# PRoot + loader
# ---------------------------------------------------------------------------------------------
echo "==> PRoot $PROOT_REV"
(
  cd "$WORK"
  git clone "$PROOT_REPO" proot
  cd proot
  git checkout "$PROOT_REV"

  TALLOC_CFLAGS="-I$WORK/talloc-install/include"
  TALLOC_LIBS="-L$WORK/talloc-install/lib -ltalloc"

  # `src/` is PRoot itself; `loader/` is the ELF interposer the guest binaries run through.
  make -C src \
    CC="$CC" AR="$AR" \
    LDFLAGS="-Wl,-rpath,'\$\$ORIGIN' $TALLOC_LIBS -llog -landroid-shmem -L$OUT" \
    CFLAGS="-O2 -fPIC $TALLOC_CFLAGS -DANDROID"
  make -C loader \
    CC="$CC" AR="$AR" \
    CFLAGS="-O2 -fPIC -DANDROID"

  cp src/proot "$OUT/libproot.so"
  cp loader/loader "$OUT/libproot_loader.so"
)

# Rename rather than strip in place, then strip: AGP will not package an unstripped .so twice.
"$STRIP" --strip-unneeded "$OUT/libproot.so" || true
"$STRIP" --strip-unneeded "$OUT/libproot_loader.so" || true
"$STRIP" --strip-unneeded "$OUT/libandroid-shmem.so" || true

echo "==> outputs"
ls -l "$OUT"
( cd "$OUT" && sha256sum ./* > SHA256SUMS && cat SHA256SUMS )
