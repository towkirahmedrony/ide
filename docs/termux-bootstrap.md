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

The `aarch64`/`arm64-v8a` AgentX bootstrap has been built, verified and published at the
fixed release tag `agentx-bootstrap-2026.09.30-r1`; its catalog entry contains the immutable
URL, digest, size and file count. The other three ABIs remain `unbuilt` and provisioning on
those devices fails with an explanation instead of downloading anything. The build workflow
and the remaining publication steps are documented in
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

## Installing and upgrading AgentX

The application id is `com.agentx.app` in every build type. That is what makes the whole
prefix scheme work, and it is what keeps the official Termux app installable next to this
one:

| | AgentX | Official Termux |
| --- | --- | --- |
| Package | `com.agentx.app` | `com.termux` |
| Prefix | `/data/data/com.agentx.app/files/usr` | `/data/data/com.termux/files/usr` |
| Provisioned by | this repository's own bootstrap | the Termux release archives |

They do not share a data directory, a prefix, a binary or a package name, so both can be
installed at once and uninstalling either leaves the other untouched. Nothing in this app
writes to `com.termux`, and nothing in AgentX reads from it.

Installing or upgrading AgentX:

1. Build and install the APK (`./gradlew assembleDebug` for a local build).
2. Open the Terminal tab. Until a bootstrap is installed the shell is Android's
   `/system/bin/sh`: a genuine pty shell, but **not** Termux support, and the header says so.
3. Tap **Install**. The bootstrap is downloaded, checksum-verified against the digest in
   `TermuxBootstrapCatalog`, unpacked into `usr-staging`, symlinked, made executable and moved
   into place. On success the shell is restarted automatically, replacing the system shell with
   the AgentX Termux shell.
4. Upgrading: install the newer APK over the old one. The prefix is left alone. To pick up a
   newer bootstrap, publish one, update the catalog and tap Install again; a checksum mismatch
   deletes the archive and aborts rather than extracting it.

## Troubleshooting, by install stage

Failures name the stage they failed at, so the message points at one thing to fix.

| Stage | What it means | What to do |
| --- | --- | --- |
| `download` | The archive could not be fetched, or no artifact is catalogued for this ABI. | Check the connection. If the message says no artifact exists, the catalog is still `unbuilt`: build and publish the bootstrap first. |
| `checksum` | The downloaded archive does not match the published SHA-256. | Nothing is installed: the archive is deleted. Retry; if it repeats, re-publish the release and re-check the catalog digest. |
| `extraction` | The zip could not be unpacked. | A corrupt download or a wrong asset. Verify the release asset is a bootstrap zip. |
| `symlink` | `SYMLINKS.txt` is malformed, or a target leaves the prefix. | The archive was not produced by `postprocess-bootstrap.sh`. Rebuild it with `tools/termux-bootstrap/scripts`. |
| `permissions` | Files under `bin/` and `libexec/` could not be made executable. | Check the app still owns `/data/data/com.agentx.app`. Clear app data and reinstall if the directory was tampered with. |
| `prefix` | The archives are not built for this app's prefix. | Almost always the official Termux bootstrap, or a build made without the `TERMUX_APP__PACKAGE_NAME` patch. Rebuild for `com.agentx.app`. |
| `runtime` | The prefix installed but no shell would run from it. | The userland is incomplete. Reinstall; if it repeats, check the archive with `verify-bootstrap.sh`. |

Other situations the screen reports rather than hiding:

- **"No AgentX bootstrap is available for this ABI/build yet"** — the catalog entry for the
  device ABI is `unbuilt`, so the Install button is withheld and the system shell stays usable.
- **Shell exits immediately** — the exit strip shows the status in words ("exited with status 1
  (a startup failure…)") and a **Restart terminal** action. The process's own stderr is in the
  terminal buffer directly above it.
- **Workspace cannot be entered** — a `content://` tree has no POSIX path, so either it is
  mirrored into `/data/data/com.agentx.app/files/workspaces/<name>-<hash>` (commands then run
  against a **copy**, and write-back is not implemented) or the shell falls back to `$HOME`.
  Either way the terminal keeps running.
- **`pkg install` says "Unable to locate package"** — expected: see below.

## What is verified, and what is not

Verified on this machine, by executing it:

- `tools/termux-bootstrap/verify_bootstrap.py` against the real published upstream archive:
  clean in the calibration profile, seven violations in the AgentX profile.
- The `termux-runtime` workspace-binding, mirror and catalog logic, and the
  `ui` presentation rules, on the JVM (see the harnesses in `tools/termux-bootstrap`).

Verified in CI on the pushed commit: `./gradlew test` on the whole version catalogue, plus the
APK package-name, signature, alignment and native-library checks in
[`.github/workflows/termux-smoke.yml`](../.github/workflows/termux-smoke.yml).

**Not verified, and it must not be presented as working:**

- The arm64 bootstrap has been built and archive-verified, but it has not yet been executed on a
  real arm64 phone. No device run has confirmed `sh`, `bash`, `printf`, `pkg` or `apt` there.
- The emulator smoke test still needs the x86_64 bootstrap; the current published artifact covers
  arm64 only, so x86_64 emulator coverage remains blocked by the catalog gate.
- Therefore **full Termux support is unverified**. The acceptance checklist in
  [termux-terminal.md](termux-terminal.md) records item by item what is verified and what is
  not, and every on-device item is still open.

## Runtime verification status

No AgentX prefix has been executed on a device yet. The arm64 archive exists and passed the
ELF, prefix and content verifier; the runtime smoke test — `sh`, `bash`, `printf 'hello\n'`,
`command -v pkg`, `command -v apt` — is **unverified and belongs to Part 3**, together with
the real APK acceptance run. Termux support must not be described as working until that
passes.
