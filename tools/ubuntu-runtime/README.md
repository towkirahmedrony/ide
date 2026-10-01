# Building the native developer runtime (PRoot + loader)

Everything under `nativeLibraryDir` for the embedded Ubuntu runtime is produced here. Nothing
is committed: a binary large enough to matter should be reproducible from a pinned upstream
revision, and this directory is that recipe.

## Upstreams

| Component | Upstream | Revision | License |
| --- | --- | --- | --- |
| PRoot + loader | <https://github.com/termux/proot> | `d4d2a19081c3c07f75250e4ce2980b9fa2f5720f` (tag `v5.1.107.95`) | GPL-2.0-or-later |
| talloc | <https://talloc.samba.org/> | 2.4.x release tarball | LGPL-3.0-or-later |
| android-shmem | <https://github.com/termux/libandroid-shmem> | `example` branch tip recorded in the script output | MIT |

The notices and license files are kept in [`third_party/proot`](../../third_party/proot) and
[`third_party/ubuntu`](../../third_party/ubuntu).

## What the build must produce

Four files, named so AGP packages them as Android native libraries:

| Output | Built from |
| --- | --- |
| `libproot.so` | PRoot's `src/proot` |
| `libproot_loader.so` | PRoot's `loader/loader` |
| `libandroid-shmem.so` | libandroid-shmem |
| `libtalloc.so` | talloc |

They are placed in `ubuntu-runtime/src/main/jniLibs/arm64-v8a/` (by CI, not by hand) and end up
in `nativeLibraryDir` after install because the module and the app both set
`packaging { jniLibs { useLegacyPackaging = true } }`.

## Why these particular flags

- **`DT_RUNPATH=$ORIGIN`.** PRoot needs `libtalloc.so` and `libandroid-shmem.so`, which sit next
  to it in `nativeLibraryDir`. The build links with `-Wl,-rpath,'$ORIGIN'` so the loader finds
  them there. This is deliberate: the alternative, exporting `LD_LIBRARY_PATH`, is inherited by
  *guest* processes and can shadow the guest's own libraries. `ProotCommand.build` therefore
  leaves `LD_LIBRARY_PATH` off unless `hostLibraryPath` is passed for diagnosis.
- **Static where practical.** `libtalloc` is linked statically into PRoot in the Termux build;
  the script follows that so a missing `libtalloc.so` cannot break `proot`.
- **`--kill-on-exit`** is not used. Process teardown is the runtime's job (`AgentxExecution`
  destroys the descendant tree), so the guest is not killed behind the user's back.

## Running it

Locally (with an NDK installed) or in CI via
[`.github/workflows/build-native-runtime.yml`](../../.github/workflows/build-native-runtime.yml):

```sh
NDK=/path/to/android-ndk \
  sh tools/ubuntu-runtime/build-proot.sh arm64-v8a out/
sh tools/ubuntu-runtime/verify-native.sh out/
```

The workflow publishes `out/` as a release under a fixed tag; the app build downloads that tag
into `jniLibs`. A release whose tag is mutable is not accepted, because the digests recorded in
the manifest have to keep matching what is served.

## Status

This recipe has **not** been executed in the authoring environment (no NDK, and this
repository's rule is that Android artifacts are built only by GitHub Actions). The build script
is the iteration point: the workflow prints the toolchain, the exact commands and the SHA-256 of
every output, so a failure names its step.
