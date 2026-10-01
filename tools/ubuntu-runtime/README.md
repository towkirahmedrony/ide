# Building the native developer runtime (PRoot + loader)

Everything under `nativeLibraryDir` for the embedded Ubuntu runtime is produced here. Nothing
is committed: a binary large enough to matter should be reproducible from a pinned upstream
revision, and this directory is that recipe.

## Upstreams

| Component | Upstream | Revision | License |
| --- | --- | --- | --- |
| PRoot + loader | https://github.com/termux/proot | `d4d2a19081c3c07f75250e4ce2980b9fa2f5720f` (tag `v5.1.107.95`) | GPL-2.0-or-later |
| talloc | https://talloc.samba.org/ | 2.4.3 (`dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd`) | LGPL-3.0-or-later |
| android-shmem | https://github.com/termux/libandroid-shmem | `7f0bd7e25dbdd146265aff7c6a890029e374622d` | MIT |

The notices and license files are kept in `third_party/proot` and `third_party/ubuntu`.

## What the build must produce

Files named so AGP packages them as Android native libraries into `nativeLibraryDir`:

| Output | Built from | Required |
| --- | --- | --- |
| `libproot.so` | PRoot's `src/proot` (ELF executable, not a JNI library) | yes |
| `libproot_loader.so` | PRoot's `src/loader/loader` (static freestanding ELF) | yes |
| `libandroid-shmem.so` | libandroid-shmem | yes |
| `libtalloc.so` | talloc shared object, if waf produced one | no (talloc is linked statically) |
| `libproot_loader32.so` | 32-bit loader | no (Ubuntu ARM64 does not start 32-bit guests) |

They are placed in `ubuntu-runtime/src/main/jniLibs/arm64-v8a/` by CI (`vendor-native.sh`) and
end up in `context.applicationInfo.nativeLibraryDir` after install because the module and the
app both set `packaging { jniLibs { useLegacyPackaging = true } }`.

Do not copy these into `assets/` or `filesDir` and expect `nativeLibraryDir` to contain them.

## Why these particular flags

- **`PROOT_UNBUNDLE_LOADER`.** PRoot's default path extracts a bundled loader into
  `PROOT_TMP_DIR` (app-private storage). Android will not `execve()` that on targetSdk 37.
  Unbundling makes `get_loader_path()` use `PROOT_LOADER`, which the runtime sets to
  `nativeLibraryDir/libproot_loader.so`.
- **`DT_RUNPATH=$ORIGIN`.** PRoot needs `libandroid-shmem.so` (and `libtalloc.so` if not
  static) next to itself. `LD_LIBRARY_PATH` is not set by default because it is inherited by
  guest processes.
- **Static talloc.** Linked into `libproot.so` so a missing `libtalloc.so` cannot break PRoot.
- No top-level `loader/` makefile: `make -C src` builds both `proot` and `loader/loader`.

## Running it

```sh
NDK=/path/to/android-ndk \
  sh tools/ubuntu-runtime/build-proot.sh arm64-v8a out/artifacts
sh tools/ubuntu-runtime/verify-native.sh arm64-v8a out/artifacts
sh tools/ubuntu-runtime/vendor-native.sh arm64-v8a out/artifacts ubuntu-runtime/src/main/jniLibs
sh tools/ubuntu-runtime/verify-apk.sh app/build/outputs/apk/release/app-release.apk arm64-v8a
```

The APK workflow builds these sources, vendors them into `jniLibs`, builds the APK, and fails
if `lib/arm64-v8a/libproot.so` or `libproot_loader.so` is missing from the APK.
