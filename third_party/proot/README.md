# PRoot (native developer runtime)

The embedded developer runtime executes guest binaries through **PRoot**, an unprivileged
`chroot`/`mount --bind`/`binfmt_misc` emulator that works with the `ptrace` system call and
requires no Android root, no Magisk and no system modification.

## Provenance

| | |
| --- | --- |
| Upstream | <https://github.com/termux/proot> |
| Revision | `d4d2a19081c3c07f75250e4ce2980b9fa2f5720f` (tag `v5.1.107.95`) |
| Original project | <https://github.com/proot-me/proot> (STMicroelectronics) |
| License | GPL-2.0-or-later — full text in [`licenses/LICENSE-GPL-2.0.txt`](licenses/LICENSE-GPL-2.0.txt) |

The revision is pinned in `tools/ubuntu-runtime/build-proot.sh` and recorded again in
`upstream-revision.txt`. The binaries are **built from that source** by
`.github/workflows/android-build.yml` with the Android NDK and packaged into the APK as
`lib/arm64-v8a/*.so`. No prebuilt binary from an unknown origin is committed or downloaded.

## What is used

| Artifact (in `nativeLibraryDir`) | Built from | Purpose |
| --- | --- | --- |
| `libproot.so` | `termux/proot` `src/proot` | The runtime that sets up the guest root and starts the guest program. |
| `libproot_loader.so` | `termux/proot` `src/loader/loader` | The ELF interposer every guest binary is run through; referenced by `PROOT_LOADER`. |
| `libandroid-shmem.so` | `termux/libandroid-shmem` | Shared-memory emulation PRoot needs on Android. |

`libtalloc.so` (talloc, LGPL-3.0-or-later, https://talloc.samba.org/) is optional: talloc is
linked statically into `libproot.so` when possible. All three dependencies are produced by
`tools/ubuntu-runtime/build-proot.sh`.

## GPL obligations

PRoot is GPL-2.0-or-later. AgentX does not modify PRoot's source; it builds the pinned upstream
revision unmodified and links it as a separate program that is executed as a process, not
linked into the app. The corresponding source is the pinned upstream revision above, and the
build recipe that reproduces the binaries is `tools/ubuntu-runtime/build-proot.sh` in this
repository. The license text is included here.

Kern and j-code-android were consulted as architectural references only. No source from either
project is copied into AgentX.
