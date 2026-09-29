# Building a Termux bootstrap for this app's own prefix

The published Termux bootstrap and every repository package are hard-wired to
`/data/data/com.termux/files/usr` (background in
[termux-terminal.md § Prefix constraint](termux-terminal.md#prefix-constraint)). The embedded
runtime detects that and refuses to install them anywhere else rather than producing a shell full
of `not found`.

There are two supported ways out. The first needs no package rebuild; the second does.

## Option 1 — build the APK with the official prefix (no rebuild)

```bash
./gradlew assembleRelease -Pagentx.termux.officialPrefix=true
```

`app/build.gradle.kts` then sets `applicationId = "com.termux"`, so `Context.getFilesDir()`
resolves to `/data/data/com.termux/files` — exactly what the artifacts expect.
`TermuxPrefixPolicy` sees the official prefix, `TermuxBootstrapInstaller` downloads the published
bootstrap, verifies its pinned SHA-256, unpacks it, and `pkg`/`apt` reach the official
repositories with no further work.

Caveats, both real:

- the APK cannot be installed alongside a real Termux (same package name, different signature);
- it takes over Termux's package identity, which is only appropriate for a fork that intends to be
  the user's Termux.

## Option 2 — build a bootstrap for `com.agentx.app`

This is the route upstream provides for this exact case. `scripts/build-bootstraps.sh` in
termux-packages documents itself as:

> build-bootstraps.sh is a script to build bootstrap archives for the termux-app from local package
> sources instead of debs published in apt repo like done by generate-bootstrap.sh. **It allows
> bootstrap archives to be easily built for (forked) termux apps without having to publish an apt
> repo first.**

and, for the prefix:

> The package name/prefix that the bootstrap is built for is defined by `TERMUX_APP_PACKAGE` in
> `scripts/properties.sh`. It defaults to `com.termux`. If package name is changed, make sure to
> run `./scripts/run-docker.sh ./clean.sh` or pass `-f` to force rebuild of packages.

`properties.sh` narrows this down further. It states that the following are safe to modify when
forking, and that **no other variable may be touched** unless it is a full fork:

```
- `TERMUX__NAME`, `TERMUX__LNAME` and `TERMUX__UNAME`.
- `TERMUX__REPOS_HOST_ORG_NAME` and `TERMUX__REPOS_HOST_ORG_URL`.
- `TERMUX_*__REPO_NAME` and `TERMUX_*__REPO_URL`.
- `TERMUX_APP__PACKAGE_NAME`.
- `TERMUX_APP__DATA_DIR`.
- `TERMUX__PROJECT_SUBDIR`.
- `TERMUX__ROOTFS_SUBDIR`.
- `TERMUX__ROOTFS` and alternates.
- `TERMUX__PREFIX` and alternates.
- ...
```

Because `TERMUX_APP__DATA_DIR` is derived (`"/data/data/$TERMUX_APP__PACKAGE_NAME"`), and
`TERMUX__ROOTFS`/`TERMUX__PREFIX` derive from it, **changing `TERMUX_APP__PACKAGE_NAME` alone
moves the whole prefix**. Setting `TERMUX__PREFIX` directly is rejected by the file's own
validator, which requires it to equal `TERMUX_PREFIX_CLASSICAL`.

### The workflow

[`.github/workflows/termux-bootstrap.yml`](../.github/workflows/termux-bootstrap.yml) does this as
a **manual** (`workflow_dispatch`) job. It is deliberately not wired into the normal build: the
job cross-compiles the userland for four ABIs inside the termux-packages Docker builder, which
takes hours, and it must never be able to fail the APK build.

What it does:

1. checks out `termux/termux-packages` at the revision you pass (default
   `2fdb0c07f3fec34adf24c8af515c852fc51f4c9b`);
2. rewrites the single `TERMUX_APP__PACKAGE_NAME="com.termux"` assignment, and **fails loudly** if
   upstream no longer has exactly that one line, instead of silently building for the wrong
   prefix;
3. prints the derived `TERMUX_APP__DATA_DIR`, `TERMUX__ROOTFS` and `TERMUX__PREFIX` so the log
   proves which prefix it built for;
4. runs `./scripts/run-docker.sh ./scripts/build-bootstraps.sh --architectures <abis> -f`
   (`-f` is required after a package-name change, as upstream's own help says);
5. computes each archive's SHA-256, writes `bootstrap-digests.txt`, and puts a
   ready-to-paste `TermuxBootstrapCatalog` snippet in the run summary;
6. uploads the four `bootstrap-*.zip` files and the digests as artifacts.

### After the build

1. Host the archives somewhere the app can reach and replace `RELEASE`/`VARIANT` (or the whole
   `RELEASE_BASE_URL`) plus the four `sha256` values in
   [`TermuxBootstrapCatalog`](../termux-runtime/src/main/kotlin/com/agentx/app/termux/TermuxBootstrapCatalog.kt).
   `TermuxBootstrapCatalogTest` checks that every ABI stays covered and that the digests keep the
   right shape.
2. Map each Termux architecture to an Android ABI (`aarch64`→`arm64-v8a`, `arm`→`armeabi-v7a`,
   `i686`→`x86`, `x86_64`→`x86_64`).
3. **For `pkg install` to keep working**, publish your own `packages` repository built from the
   same tree and point the fork at it through the fork-safe `TERMUX_*__REPO_NAME` /
   `TERMUX_*__REPO_URL` variables. Otherwise the bootstrap's apt sources still reach
   `packages.termux.dev`, which ships debs built for `com.termux`'s prefix — `pkg install` would
   then install packages that cannot run. The workflow takes a `publish_repo_urls` input for this
   and currently **stops with an error** rather than producing archives that quietly depend on the
   wrong repository.

### Status

The workflow has not been executed, because it cannot run from this environment (no Docker, no
push access at the time of writing) and it is intentionally manual. Treat it as the plan the
repository is set up for, not as a verified pipeline: the first run needs a human to read the
build log, confirm the printed prefix, and then complete steps 1–3 above.
