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

Verified against the published archive (re-downloaded and hashed): the SHA-256 and the size
above both match, and the archive holds 3,413 entries.

## The archive contains hard links — two of them

```sh
$ sha256sum ubuntu-base-24.04.5-base-arm64.tar.gz
a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2
$ tar -tvf ubuntu-base-24.04.5-base-arm64.tar.gz | grep ' link to '
 rwxr-xr-x root/root         0 2026-08-18 21:25 usr/bin/perl5.38.2 link to usr/bin/perl
 rwxr-xr-x root/root         0 2026-07-03 11:53 usr/bin/uncompress  link to usr/bin/gunzip
```

So `usr/bin/perl` and `usr/bin/gunzip` are stored as regular files, and `usr/bin/perl5.38.2`
and `usr/bin/uncompress` as hard links to them. Both targets appear *before* their link entries
in the archive, so a streaming extractor can always create the link. The pairs are pinned in
[`UbuntuRootfsCatalog.REQUIRED_HARD_LINKS`](../../ubuntu-runtime/src/main/kotlin/com/agentx/app/ubuntu/UbuntuRootfsCatalog.kt)
and asserted by `UbuntuRootfsCatalogTest`. Everything else in the archive is a regular file, a
directory, or a symlink (194 of those).

## Why extraction cannot be a plain `tar -x` on Android

Android's SELinux policy is a `neverallow` on this: an `untrusted_app` may not create a hard
link at all.

> `# Do not allow untrusted_app to hard link to any files.`
> — `platform/system/sepolicy`, `untrusted_app.te`

So the platform tar stops at the first of the two entries above with
`tar: can't link 'usr/bin/perl5.38.2' -> 'usr/bin/perl': Permission denied`, and an app cannot
work around it with its own `link(2)` call. The runtime therefore extracts the archive *through*
its own PRoot with `-l` (link-to-symlink), exactly as Termux's `proot --link2symlink tar` does:
each link becomes a symlink to the same content, kept in `PROOT_L2S_DIR`, so both names still
lead to one file. See [developer-runtime.md](../../docs/developer-runtime.md).

The hard links are **not** removed from the archive, replaced with copies, or skipped, and the
SHA-256 check is unchanged.
