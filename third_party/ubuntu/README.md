# Ubuntu Base rootfs

The guest userland is the official **Ubuntu Base** image for arm64. It is a real Ubuntu
filesystem — `bash`, `sh`, `coreutils`, `apt` and `dpkg` are the distribution's own — so the
runtime never depends on Termux's package manager.

## Provenance

| | |
| --- | --- |
| Source | <https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/> |
| Release | Ubuntu Base 24.04.5 LTS (noble) arm64 |
| Asset | `ubuntu-base-24.04.5-base-arm64.tar.gz` |
| Size | 29,936,675 bytes |
| SHA-256 | `a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2` |
| License | Ubuntu is distributed under the Ubuntu and component licenses; the base image contains GPL/LGPL and other free-software components. |

Nothing is bundled in the APK. The archive is downloaded on first run, **verified against the
SHA-256 above before anything is extracted**, and then unpacked into app-private storage. There
is no "skip verification" path.

## Re-deriving the pin

```sh
curl -fsSL https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS \
  | grep arm64
curl -fsSI https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz \
  | grep -i content-length
```

The digest and size are recorded in
[`UbuntuRootfsCatalog`](../../ubuntu-runtime/src/main/kotlin/com/agentx/app/ubuntu/UbuntuRootfsCatalog.kt)
and asserted by `UbuntuRootfsCatalogTest`.
