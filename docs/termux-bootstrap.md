# Building a Termux bootstrap for this app's own prefix

The published Termux bootstrap and every repository package are compiled for
`/data/data/com.termux/files/usr` (background in
[termux-terminal.md § Prefix constraint](termux-terminal.md#prefix-constraint)). The
embedded runtime detects that and refuses to install them anywhere else rather than
producing a shell full of `not found`.

For this app there is exactly one supported way out: **rebuild the bootstrap for
`com.agentx.app`.** The published archives cannot be adopted, adapted or rewritten — the
replacement prefix is longer than the original, so even the ELF `RUNPATH` strings cannot be
patched in place.

Everything needed is in [`tools/termux-bootstrap/`](../tools/termux-bootstrap/README.md),
which records the pinned upstream revision, how the prefix is defined and verified in
upstream's build source, the exact commands, and the verification the artifacts must pass.

## Status

No AgentX bootstrap has been built yet. `TermuxBootstrapCatalog` lists all four ABIs with
`sourceRevision = "unbuilt"` and no URL, digest, size or file count, so provisioning fails
with an explanation instead of downloading anything. The build cannot be run from the
development environment used so far (no Docker, no Android NDK, 2 vCPU, no push
credentials); the ready-to-run GitHub Actions workflow and the precise blockers are
documented in
[tools/termux-bootstrap § Status](../tools/termux-bootstrap/README.md#status-not-built-in-this-environment--the-concrete-blocker).

## How the prefix is configured upstream

`scripts/properties.sh` in termux-packages defines the whole layout from one variable:

```
TERMUX_APP__PACKAGE_NAME="com.termux"
TERMUX_APP__DATA_DIR="/data/data/$TERMUX_APP__PACKAGE_NAME"
TERMUX__ROOTFS="$TERMUX_APP__DATA_DIR/$TERMUX__ROOTFS_SUBDIR"   # files
TERMUX__PREFIX="$TERMUX__ROOTFS/$TERMUX__PREFIX_SUBDIR"          # usr
TERMUX__PREFIX_CLASSICAL="$TERMUX__PREFIX"
```

So patching that single assignment moves the entire layout to

| Variable | Value for this app |
| --- | --- |
| `TERMUX_APP_PACKAGE` | `com.agentx.app` |
| `TERMUX__ROOTFS` | `/data/data/com.agentx.app/files` |
| `TERMUX__PREFIX` | `/data/data/com.agentx.app/files/usr` |
| `TERMUX__HOME` | `/data/data/com.agentx.app/files/home` |

Two traps, both verified in the source and handled by
`tools/termux-bootstrap/apply-agentx-prefix.sh`:

- `TERMUX_APP_PACKAGE` (single underscore separator at the end) is a *deprecated alias*
  assigned from `TERMUX_APP__PACKAGE_NAME`. `scripts/build-bootstraps.sh --help` mentions
  the alias, but patching only the alias does nothing, because `properties.sh` overwrites
  it. The build patches `TERMUX_APP__PACKAGE_NAME`.
- `TERMUX__PREFIX` cannot be set directly: `properties.sh` refuses any value that is not
  equal to `TERMUX__PREFIX_CLASSICAL`.

## Build, publish, then update the catalog

The workflow is manual, because it cross-compiles the userland for four ABIs inside the
termux-packages Docker builder and takes hours; it must never be able to fail the APK
build.

1. **Build.** `Actions → Build AgentX Termux bootstrap → Run workflow`. Set `only_arch` to
   a single architecture while reading logs, and `publish: false` for a dry run. The job
   checks out termux-packages at `2fdb0c07f3fec34adf24c8af515c852fc51f4c9b`, rewrites the
   one package-name assignment, prints the prefix `properties.sh` derives (so the log
   proves which prefix was built for), runs
   `./scripts/run-docker.sh ./scripts/build-bootstraps.sh --architectures <arch> -f`,
   post-processes the archive, verifies it, and fails on any violation. `-f` is required
   after a package-name change, as upstream's own help says.
2. **Verify.** Each job uploads `bootstrap-<arch>.zip`, its `.sha256`, its
   `manifest-<arch>.json`, the verification report and the build log. Read
   `verify-<arch>.txt`: it must end in `OK`, and the prefix-evidence log must show
   `/data/data/com.agentx.app/files/usr`.
3. **Publish.** With `publish: true` and no `only_arch`, the four archives are attached to
   a GitHub Release under the fixed tag you pass. A mutable tag is rejected: `latest` would
   make the digests recorded in source control stop matching what is served.
4. **Update the catalog.** Paste the generated `TermuxBootstrapCatalog.Entry` block for each
   ABI into
   [`TermuxBootstrapCatalog.kt`](../termux-runtime/src/main/kotlin/com/agentx/app/termux/TermuxBootstrapCatalog.kt),
   replacing that ABI's `pending(...)` call, and fill in the immutable release asset URL
   (`…/releases/download/<tag>/bootstrap-<arch>.zip`). Only real values: the entry becomes
   `available` only when the URL, a 64-character lower-case hex SHA-256, a positive size, a
   positive file count and a non-`unbuilt` source revision are all present. Then:

   ```bash
   ./gradlew :termux-runtime:test
   ```

   `TermuxBootstrapCatalogTest` fails on a blank or missing URL, a missing or malformed
   digest, a missing size or file count, a `latest` URL, a revision that does not name the
   pinned commit, and on the official Termux bootstrap's digest. A checksum mismatch at
   install time deletes the archive and aborts before extraction, unchanged from Part 1.

The ABI mapping the catalog must cover: `aarch64 → arm64-v8a`, `arm → armeabi-v7a`,
`i686 → x86`, `x86_64 → x86_64`.

## Package installation is disabled until an AgentX repository exists

`pkg install` reaches an apt repository. The official repositories serve packages built for
`com.termux`, and installing those into this prefix yields binaries whose ELF `RUNPATH`
points at a directory this app does not own. The bootstrap therefore ships an
`etc/apt/sources.list` with **no active `deb` line** — the official entries are present but
commented out, together with the reason — so `pkg install <package>` fails loudly with
"Unable to locate package" instead of installing something that cannot run.

Building and hosting a repository for this prefix, then passing its URL as the
`apt_repo_url` input, is the intended way to lift that limitation. The verifier fails the
build if `sources.list` contains an active line naming `termux.dev`, so apt cannot silently
be pointed back at the official repository.

## Runtime verification status

Nothing has been executed inside an AgentX prefix: no archives exist yet, and the
environment could not build them. The scripts have been validated against the published
upstream archive for every check that does not require a toolchain (the ELF and prefix
checks correctly reject it), but the runtime smoke test — `sh`, `bash`, `printf 'hello\n'`,
`command -v pkg`, `command -v apt` — is **unverified and belongs to Part 3**, together with
the real APK acceptance run. Termux support must not be described as working until that
passes.
