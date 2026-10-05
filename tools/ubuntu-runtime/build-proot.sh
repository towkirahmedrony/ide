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
# The talloc tarball, tried in order across independent Samba hosts.
#
# This build used to fetch from www.samba.org alone. That origin web server has been seen
# accepting a transfer and then dropping it part-way (`curl: (56) Connection died`), which
# fails the whole APK build for a reason that has nothing to do with this project.
# download.samba.org is the CDN-backed mirror pool for the same release and serves the
# identical file.
#
# A mirror can only ever be accepted by serving the exact bytes pinned in TALLOC_SHA256
# below: the hash is checked per mirror, so adding one cannot weaken what is built.
TALLOC_MIRRORS="https://download.samba.org/pub/talloc/talloc-${TALLOC_VERSION}.tar.gz https://www.samba.org/ftp/talloc/talloc-${TALLOC_VERSION}.tar.gz"
SHMEM_REPO="https://github.com/termux/libandroid-shmem"
SHMEM_REV="7f0bd7e25dbdd146265aff7c6a890029e374622d"
SHMEM_PATCH="libandroid-shmem-proot-tmpdir.patch"
PROOT_PATCHES="termux-proot-missing-string-header.patch termux-proot-portable-loader-info-awk.patch"

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
# Resolve $OUT to an absolute path now. Several steps below run inside a subshell that cds into
# the build tree, and a relative "$OUT" would then be created there instead of next to the
# caller — which is how the shmem link line ended up writing into a directory that never existed.
OUT=$(CDPATH= cd -- "$OUT" && pwd)
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
  # Retry within a mirror before moving on, and verify the hash per mirror: a mirror that
  # answers with anything other than the pinned tarball is rejected and the next one is
  # tried, so falling back can never change what gets built.
  fetched=0
  for url in $TALLOC_MIRRORS; do
    echo "  fetching $url"
    if curl -fsSL --retry 3 --retry-all-errors --retry-delay 2 \
        --connect-timeout 20 --max-time 120 "$url" -o talloc.tar.gz; then
      actual=$(sha256sum talloc.tar.gz | awk '{print $1}')
      if [ "$actual" = "$TALLOC_SHA256" ]; then
        fetched=1
        break
      fi
      echo "  rejected $url: served $actual, expected $TALLOC_SHA256" >&2
    else
      echo "  could not fetch from $url" >&2
    fi
  done
  if [ "$fetched" -ne 1 ]; then
    echo "talloc $TALLOC_VERSION could not be fetched from any known mirror" >&2
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
#
# shmem.c uses _PATH_TMP, which Android's libc does not define in <paths.h> — it never has, from
# API 24 through 35 — so the pinned revision does not compile against the NDK as-is. (Termux
# ships the same package and gets away with it only because termux-packages patches the NDK's
# paths.h; see ndk-patches/*/paths.h.patch there.) We apply the equivalent change to the source
# instead so the NDK install stays vanilla.
#
# The patch also matters at runtime. _PATH_TMP names the directory shmget() creates its
# per-key symlink in, and shmget() retries symlink() in an unbounded loop until it succeeds,
# so a directory that can never be created would spin forever and hang the caller. /tmp does
# not exist on Android; PROOT_TMP_DIR always names a directory PRoot has already proven it can
# write, so the patch prefers it and keeps the platform default otherwise.
# ---------------------------------------------------------------------------------------------
echo "==> libandroid-shmem $SHMEM_REV (patched by $SHMEM_PATCH)"
(
  cd "$WORK"
  git clone "$SHMEM_REPO" shmem
  cd shmem
  git checkout "$SHMEM_REV"
  git apply "$SCRIPT_DIR/patches/$SHMEM_PATCH"
  "$CC" -O2 -fPIC -shared -std=c11 -Wall -Wextra \
    -Wl,--version-script=exports.txt \
    -o "$OUT/libandroid-shmem.so" \
    shmem.c -llog -landroid
)

# libandroid-shmem's header is part of its contract, not an optional extra: upstream's Makefile
# installs it as <prefix>/include/sys/shm.h precisely so that it shadows the NDK's own
# <sys/shm.h> in every consumer. PRoot depends on that. The header is what declares
# libandroid_shmat_fd()/libandroid_shmdt_fd(), and its #defines are what redirect
# shmget()/shmat()/shmdt()/shmctl() to the libandroid_* implementations this library exports.
# Without the shadowing include path PRoot compiles against bionic's sys/shm.h and stops on the
# undeclared libandroid_* helpers — and, if it were only the declarations, it would silently
# link the guest's shm calls against the platform's absent shmget instead.
#
# PRoot 5.1.107.95 has exactly one <sys/shm.h> consumer (extension/sysvipc/sysvipc_shm.c, under
# WITH_LIBANDROID_SHMEM), so shadowing it cannot disturb anything else.
SHMEM_INCLUDE="$WORK/shmem-include"
mkdir -p "$SHMEM_INCLUDE/sys"
cp "$WORK/shmem/shm.h" "$SHMEM_INCLUDE/sys/shm.h"

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

  # Two upstream defects that only surface when this code is built for Android with a modern
  # toolchain. Neither is fixable by a flag we would actually want to set:
  #   * extension/ashmem_memfd/ashmem_memfd.c calls strcmp()/memset() without including
  #     <string.h>. It is Android-only, so no upstream build compiles that translation unit and
  #     nobody has noticed; clang 16+ rejects the implicit declarations. Patch in the missing
  #     header rather than passing -Wno-implicit-function-declaration, which would also silence
  #     every real one across all of PRoot.
  #   * src/loader/loader-info.awk relies on gawk (strtonum, \y). Ubuntu's awk is mawk, which
  #     errors on strtonum and — worse — silently matches nothing for \y, which would emit an
  #     empty offset instead of failing.
  for patch in $PROOT_PATCHES; do
    git apply "$SCRIPT_DIR/patches/$patch"
  done

  # Dummy fallback directory: the runtime always sets PROOT_LOADER to nativeLibraryDir.
  # aarch64 arch.h sets HAS_LOADER_32BIT, which builds loader-m32 with -m32. The Android
  # NDK aarch64 toolchain cannot do that. Ubuntu ARM64 does not start 32-bit guests, so
  # the 32-bit loader is disabled for this ABI. Do not pass HAS_LOADER_32BIT= on the make
  # command line: GNU make treats an empty command-line variable as still defined.
  if [ "$ABI" = "arm64-v8a" ]; then
    sed -i 's|#define HAS_LOADER_32BIT true|/* HAS_LOADER_32BIT disabled: Android NDK aarch64 */|' src/arch.h
    # src/GNUmakefile reads HAS_LOADER_32BIT back out of arch.h to decide whether to build
    # loader-m32. If this substitution ever stops matching, the -m32 build comes back and the
    # whole build fails much further downstream, so check it here instead.
    if grep -q 'define HAS_LOADER_32BIT' src/arch.h; then
      echo "arch.h still defines HAS_LOADER_32BIT for arm64-v8a" >&2
      exit 1
    fi
  fi

  # CPPFLAGS/CFLAGS/LDFLAGS travel in the environment, not on the make command line. GNU make
  # lets a command-line value win outright and ignores every ordinary assignment for it,
  # including `+=`, so passing them as arguments would silently discard the three `+=` blocks
  # src/GNUmakefile depends on: `-D_GNU_SOURCE -I. -I$(VPATH)` (nothing would find tracee/,
  # cli/, …), `-DWITH_LIBANDROID_SHMEM` (PRoot would never call libandroid-shmem at all) and
  # `-DPROOT_UNBUNDLE_LOADER` (the loader would be re-bundled, which is exactly what
  # PROOT_LOADER exists to avoid). Environment values are appended to by `+=`.
  #
  # The rpath needs the single quotes: make rewrites `$$` to `$` before the shell sees the
  # recipe, so an unquoted `$$ORIGIN` reaches the shell as `$ORIGIN`, expands to nothing and
  # links with an empty -rpath.
  #
  # --as-needed discards the bare `-ltalloc` that src/GNUmakefile appends unconditionally.
  # TALLOC_LIBS is already the static archive at that point, so talloc is resolved before
  # `-ltalloc` is reached — but without --as-needed the linker still records
  # `DT_NEEDED libtalloc.so.2`, which is talloc's SONAME rather than a file name. Android only
  # extracts `lib*.so` from an APK into nativeLibraryDir, so nothing packaged could ever satisfy
  # that name and libproot.so would fail to load outright.
  CPPFLAGS="-DARG_MAX=131072 -DVERSION=\\\"${PROOT_TAG}\\\" -DANDROID -I${SHMEM_INCLUDE}" \
  CFLAGS="-O2 -fPIC ${TALLOC_CFLAGS} -DANDROID" \
  LDFLAGS="-Wl,--as-needed -Wl,-z,noexecstack -Wl,-rpath,'\$\$ORIGIN' -Wl,--enable-new-dtags -L${OUT} -landroid-shmem -llog ${TALLOC_LIBS}" \
  make -C src \
    V=1 \
    CC="$CC" \
    LD="$CC" \
    AR="$AR" \
    STRIP="$STRIP" \
    OBJCOPY="$OBJCOPY" \
    OBJDUMP="$OBJDUMP" \
    PROOT_UNBUNDLE_LOADER=/nonexistent/agentx-proot-loader \
    PROOT_WITH_LIBANDROID_SHMEM=true

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
