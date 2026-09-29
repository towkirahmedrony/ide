# Building the AgentX Termux bootstrap

This directory builds the four bootstrap archives that `:termux-runtime` installs into
`/data/data/com.agentx.app/files/usr`, verifies them, and publishes them to a GitHub
Release whose URL and digest can be pinned in `TermuxBootstrapCatalog`.

Nothing here ships the official Termux bootstrap. That is not a preference, it is a
requirement: every published Termux binary has the official prefix compiled into its ELF
`RUNPATH` and into its script shebangs, and the replacement path
(`com.agentx.app`) is *longer* than the original (`com.termux`), so the strings cannot be
rewritten in place even in principle. The archives must be rebuilt from source, and this
directory is how.

## Status: not built in this environment — the concrete blocker

Part 2 delivered the build system, the verification gate and the publishing workflow, but
**no archives were produced here, and `TermuxBootstrapCatalog` therefore still reports
every ABI as unavailable.** That is deliberate: the catalog may only be filled from real
built artifacts.

What is missing in the environment Part 2 was written in:

| Requirement | Status here | Why it matters |
| --- | --- | --- |
| Docker (or Podman) | **absent** (`docker`, `podman`: command not found) | `scripts/build-bootstraps.sh` runs inside the termux-packages builder image; the image supplies the Android SDK/NDK, LLVM, the autotools stack and the patched host headers. There is no documented way to build the bootstrap without it. |
| Android SDK + NDK | absent (`ANDROID_HOME`/`ANDROID_SDK_ROOT` unset) | The `Dockerfile` in termux-packages installs them; cross-compiling by hand is not supported by upstream. |
| CPU / RAM / disk | 2 vCPU, 4 GiB RAM, 27 GB free | One architecture builds ~80 packages. 2 vCPU makes this far slower than a runner, and the build tree plus the builder image exceed the comfortable margin. |
| Time | — | Upstream describes the job as taking hours. Four architectures on 2 cores is not a realistic interactive task. |
| Credentials | no Git push credential in the environment (no credential helper, `gh` not authenticated) | Even a successful local build could not be published, and the workflow could not be dispatched. |

Everything below has been written, and the parts that *can* be exercised without a
toolchain have been exercised and are reported with their real output in this README.

The ready-to-run workflow is
[`.github/workflows/build-termux-bootstrap.yml`](../../.github/workflows/build-termux-bootstrap.yml).
The steps to run it are in [Running the workflow](#running-the-workflow).

## Pinned upstream

| | |
| --- | --- |
| Repository | `https://github.com/termux/termux-packages` |
| Commit | `2fdb0c07f3fec34adf24c8af515c852fc51f4c9b` |
| Commit date | 2026-09-29T03:40:09Z |
| Commit subject | `bump(x11/qbittorrent): 5.2.4` |

The revision lives in [`agentx-prefix.env`](agentx-prefix.env) as
`TERMUX_PACKAGES_COMMIT`; the workflow takes it as an input defaulting to that value, and
the verification step records it in each `manifest.json` and in the catalog's
`sourceRevision`.

## How the prefix is defined, verified in the upstream source

The following was read from `scripts/properties.sh` at the pinned commit. Line numbers
refer to that file.

```
467: TERMUX_APP__PACKAGE_NAME="com.termux"
468: TERMUX_APP_PACKAGE="$TERMUX_APP__PACKAGE_NAME"          # deprecated alias
488: TERMUX_APP__DATA_DIR="/data/data/$TERMUX_APP__PACKAGE_NAME"
746: TERMUX__ROOTFS_SUBDIR="files"
786: TERMUX__ROOTFS="$TERMUX_APP__DATA_DIR/$TERMUX__ROOTFS_SUBDIR"
863: TERMUX__PREFIX_SUBDIR="usr"
952: TERMUX__PREFIX="$TERMUX__ROOTFS/$TERMUX__PREFIX_SUBDIR"
968: TERMUX__PREFIX_CLASSICAL="$TERMUX__PREFIX"
```

Four consequences, each of which shaped this build system:

1. **`TERMUX_APP__PACKAGE_NAME` is the only variable that has to change.** `DATA_DIR`,
   `ROOTFS`, `PREFIX` and `PREFIX_CLASSICAL` all derive from it, so patching that one
   assignment moves the whole layout to `com.agentx.app`.
2. **`TERMUX_APP_PACKAGE` is a trap.** It is a *deprecated alias* assigned *from* the
   authoritative variable. `scripts/build-bootstraps.sh --help` names the alias
   ("defined by `TERMUX_APP_PACKAGE` in `scrips/properties.sh`"), and the alias is also
   what many packages read at configure time — but patching the alias alone changes
   nothing, because the authoritative assignment overwrites it. This build patches the
   authoritative variable, and `apply-agentx-prefix.sh` then re-sources `properties.sh`
   and asserts that the derived `DATA_DIR`/`ROOTFS`/`PREFIX`/`PREFIX_CLASSICAL`/`HOME` are
   the AgentX ones before anything is compiled.
3. **`TERMUX__PREFIX` cannot be set directly.** The file's own validator ends with
   (line 1035) a hard failure unless `TERMUX__PREFIX == TERMUX_PREFIX_CLASSICAL`, and the
   AgentX build never touches either.
4. **Path length limits are not a problem.**
   `TERMUX_APP__DATA_DIR___MAX_LEN=69`, `TERMUX__ROOTFS_DIR___MAX_LEN=86`,
   `TERMUX__PREFIX_DIR___MAX_LEN=90`. The AgentX values are 24, 30 and 34 characters.

### The repository-compatibility variable, and why it is left alone

`scripts/properties.sh` (line 2183) declares the values the packages in the official
repositories were compiled for:

```
TERMUX_REPO_APP__PACKAGE_NAME="com.termux"
```

`build-package.sh` (line 633) reads:

```
if [[ "$TERMUX_REPO_APP__PACKAGE_NAME" != "$TERMUX_APP_PACKAGE" ]]; then
	echo "Ignoring -i option to download dependencies since repo package name (...) does not equal app package name (...)"
```

Because this build changes the app package name and **not** this variable, upstream's own
build system refuses to download official `.deb` dependencies and compiles them locally
instead. That is exactly the behaviour required: a downloaded `com.termux` deb would drag
the official prefix into the build. `apply-agentx-prefix.sh` asserts the line is still
present and still `com.termux`, and prints the reason, so the decision cannot be
"cleaned up" by accident.

### The app package name is templated into packages

`scripts/build/termux_step_patch_package.sh` (line 23) runs, for every package:

```
-e "s%\@TERMUX_APP_PACKAGE\@%${TERMUX_APP_PACKAGE}%g"
```

So the user-facing tools — `bin/am`, `bin/termux-open`, `bin/termux-wake-lock`,
`bin/termux-setup-storage`, `bin/termux-reload-settings`, `bin/termux-info` and the `pkg`
version banner — are compiled against whatever `TERMUX_APP_PACKAGE` holds, which after
the patch is `com.agentx.app`. The verifier enforces this: any `bin/` file still naming
`com.termux` fails the build (see [Verification](#verification)).

### Which packages are rebuilt

`build-bootstraps.sh` builds its package list and their dependencies from source, so the
question is not "which packages need rebuilding" but "which revision of the whole tree".
After a package-name change, upstream's own help says to force the rebuild:

> If package name is changed, make sure to run `./scripts/run-docker.sh ./clean.sh` or pass
> `-f` to force rebuild of packages.

The workflow passes `-f`. This matters because everything that embeds the prefix is
affected: `bash`, `dash`, `coreutils`, `gawk`, `grep`, `sed`, `tar`, `gzip`, `apt`,
`dpkg`, `termux-tools`, `termux-core`, `termux-exec`, `termux-am`, `termux-am-socket`,
`termux-keyring`, and the shared libraries they link (`libc++`, `libandroid-support`,
`libiconv`, …). The verified numbers from a real archive are 83 installed packages,
338 ELF files and 113 shebang scripts, **all** of which carry the prefix.

### Minimal viable package set

The build uses upstream's default set unchanged. Changing it would be a deviation with no
benefit, and upstream's list already covers every item task 3 requires.

```
apt bash bzip2 command-not-found coreutils dash diffutils findutils gawk grep gzip
less procps psmisc sed tar termux-core termux-exec termux-keyring termux-tools
util-linux ed debianutils dos2unix inetutils lsof nano net-tools patch unzip
```

Where the required tools come from, verified against a real archive's
`var/lib/dpkg/info/*.list`:

| Required tool | Provided by | In the archive as |
| --- | --- | --- |
| `sh` | `dash` | symlink `bin/sh -> dash` |
| `bash`, `login`, `pkg` | `bash`, `termux-tools` | regular files |
| `env printf cat ls pwd mkdir rm cp mv` | `coreutils` | symlinks to the multicall binary `bin/coreutils` |
| `awk` | `gawk` | symlink `bin/awk -> gawk` |
| `tar gzip sed grep` | same-named packages | regular files |
| `apt apt-get` | `apt` | regular files |
| `dpkg` | `dpkg`, a dependency of `apt` | regular file |

`dpkg` is not listed explicitly because `build-package.sh` builds dependencies and
`extract_debs` extracts every `.deb` it finds, so the dependency is included in the
archive automatically. The profile, mirror-list and env files come from `termux-tools` (`etc/profile`,
`etc/profile.d/`, `etc/termux/mirrors/`) and `termux-core` (`etc/termux/termux-bootstrap/`).

## ABI mapping

| Android ABI | Termux arch | ELF class / machine (enforced) | Linker (enforced) |
| --- | --- | --- | --- |
| `arm64-v8a` | `aarch64` | ELF64 / AArch64 | `/system/bin/linker64` |
| `armeabi-v7a` | `arm` | ELF32 / ARM | `/system/bin/linker` |
| `x86` | `i686` | ELF32 / Intel 80386 | `/system/bin/linker` |
| `x86_64` | `x86_64` | ELF64 / Advanced Micro Devices X86-64 | `/system/bin/linker64` |

Note on the linker: the program interpreter is the **Android platform linker**, not a
file under the prefix. That is correct and unavoidable — Android's loader is what loads
the program. The prefix requirement therefore applies to the ELF `RUNPATH`
(`$PREFIX/lib`) and to script shebangs (`#!$PREFIX/bin/…`). The verifier enforces all
three and distinguishes them explicitly; see [Verification](#verification).

## Building from a checkout

One command per ABI. These need Docker and a checkout of termux-packages at the pinned
commit; they are the same commands the workflow runs.

```bash
# 0. once: get the sources
git init termux-packages
cd termux-packages
git remote add origin https://github.com/termux/termux-packages.git
git fetch --depth 1 origin 2fdb0c07f3fec34adf24c8af515c852fc51f4c9b
git checkout FETCH_HEAD
cd ..

# 1. once: point upstream at the AgentX package name and prove the derived prefix
tools/termux-bootstrap/apply-agentx-prefix.sh --termux-packages-dir termux-packages

# 2. build (repeat per architecture; -f is required after the package-name change)
cd termux-packages
./scripts/run-docker.sh ./scripts/build-bootstraps.sh --architectures aarch64 -f
./scripts/run-docker.sh ./scripts/build-bootstraps.sh --architectures arm     -f
./scripts/run-docker.sh ./scripts/build-bootstraps.sh --architectures i686    -f
./scripts/run-docker.sh ./scripts/build-bootstraps.sh --architectures x86_64  -f
cd ..

# 3. add the install marker and the apt configuration, then verify
for arch in aarch64 arm i686 x86_64; do
  tools/termux-bootstrap/postprocess-bootstrap.sh \
    --zip "termux-packages/bootstrap-${arch}.zip" --arch "$arch" --out-dir dist
  tools/termux-bootstrap/verify-bootstrap.sh \
    --zip "dist/bootstrap-${arch}.zip" --arch "$arch" \
    --manifest-out "dist/manifest-${arch}.json" --emit-kotlin
  sha256sum "dist/bootstrap-${arch}.zip" > "dist/bootstrap-${arch}.zip.sha256"
done
```

`verify-bootstrap.sh` exits non-zero on any violation. Do not publish an archive that has
not passed it.

### The scripts

| Script | What it does |
| --- | --- |
| [`agentx-prefix.env`](agentx-prefix.env) | Single source of truth: package name, derived paths, pinned revision, ABI mapping, asset names. |
| [`apply-agentx-prefix.sh`](apply-agentx-prefix.sh) | Patches the one assignment, asserts upstream still looks the way it was written against, then re-sources `properties.sh` to prove the derived prefix. Changes nothing on failure. |
| [`postprocess-bootstrap.sh`](postprocess-bootstrap.sh) | Unpacks the upstream archive, validates the symlink manifest and the absence of leftover symlinks, writes `etc/termux/agentx-bootstrap.ok`, rewrites `etc/apt/sources.list`, repacks with upstream's entry naming. |
| [`verify-bootstrap.sh`](verify-bootstrap.sh) / [`verify_bootstrap.py`](verify_bootstrap.py) | The gate. See [Verification](#verification). |

## The artifact contract

Everything the installer already expects, restated so the build cannot drift from it:

| Contract | Value / behaviour |
| --- | --- |
| Archive | zip, entries relative to the prefix (`bin/…`, `etc/…`, `SYMLINKS.txt`), produced by upstream's `zip -r9` from inside `$PREFIX`. |
| Symlink manifest | `SYMLINKS.txt` at the archive root, `<target>\u2190<linkPath>` per line. |
| Install marker | `etc/termux/agentx-bootstrap.ok`, written by `postprocess-bootstrap.sh`. |
| Executables | `bin/`, `libexec`, `lib/apt/apt-helper`, `lib/apt/methods` are `chmod 0700` by the installer (`TermuxBootstrapArchive.EXECUTABLE_PREFIXES`). |
| Install layout | extracted to `$ROOTFS/usr-staging`, links applied, then renamed to `$PREFIX`, then the marker written. |

### What Part 2 had to change in Part 1, and why

Part 1's symlink validation was written before a real archive was available, and it would
have made a genuine bootstrap **impossible to install**:

```
Rejected: isSafeSymlinkTarget(target) returned false whenever
          target started with "/" or contained a ".." segment.
Measured on the published bootstrap-aarch64.zip (1213 links):
          20 links use absolute targets into the prefix, e.g.
             /data/data/com.termux/files/usr/share/termux-keyring/mradityaalok.gpg <- ./share/pacman/keyrings/mradityaalok.gpg
          79 links use relative targets containing "..", e.g.
             ../ncurses.h <- ./include/ncursesw/term.h
          => 99 of 1213 links were classified "malformed", and the installer throws
             TermuxBootstrapException(SYMLINK, "Malformed SYMLINKS.txt: …") on the first
             of them, so no real archive could ever be installed.
```

The minimal, correct fix: decide safety by **where the target resolves to**, not by how it
is spelled.

- a bare name resolves against the link's own directory (`bin/ls -> coreutils` ⇒
  `bin/coreutils`, which really exists — 1111 links);
- a relative target resolves against the link's directory and is rejected only if it
  climbs above the prefix root;
- an absolute target is accepted when it is inside the agentx prefix in either its staged
  (`usr-staging`) or final (`usr`) form — those links are deliberately dangling while
  staging and resolve after the rename, which is what upstream's own installer relies on.

This preserves the *intent* of every Part 1 assertion (the official Termux prefix is still
refused; `../../escape` from `bin/` is still refused; a `bin/…` link path with `..` is
still refused) while accepting what a real bootstrap contains. Tests were updated to the
measured shapes rather than left asserting the wrong thing.

## Verification

`verify-bootstrap.sh --zip … --arch …` runs, for the archive in front of it:

- **Archive**: zip CRC, entry count, presence of `SYMLINKS.txt`.
- **Contents**: `etc/profile`, `etc/profile.d/*`, `etc/termux/`, `etc/apt/sources.list`,
  `var/lib/dpkg/status`, `var/lib/dpkg/info/`, `lib/libc++_shared.so`,
  `lib/libandroid-support.so`, and all 21 required executables — resolved **through** the
  symlink manifest, so `bin/sh -> dash` counts only if `bin/dash` exists.
- **Native files**: `file`-level classification and, per ELF, `readelf -h` (class and
  machine must match the ABI), `readelf -l` (the launchable programs in `bin/` must name
  the platform linker), and `readelf -d` (`RUNPATH` must be exactly `$PREFIX/lib`).
  `NEEDED` libraries that resolve neither to `lib/` nor to an Android system library are
  reported, not enforced — see the calibration note below.
- **Scripts**: every `#!` line pointing into an absolute path must point into the prefix
  (113 scripts in a real archive).
- **Prefix scan**: every file is read as bytes — native binaries, scripts and package
  metadata alike — and `com.termux` is bucketed by meaning:
  - `data/data/com.termux` (either spelling: absolute paths *and* the relative form used
    by `var/lib/dpkg/info/*.md5sums`) ⇒ **hard failure**. This is task 4's requirement,
    strengthened so the dpkg metadata cannot slip through.
  - `com.termux` in a `bin/` file ⇒ **hard failure**: those tools address the app and must
    name `com.agentx.app`.
  - `com.termux` anywhere else ⇒ reported with the file list; upstream ships the string in
    headers, libraries and `libexec/installed-tests/` fixtures that do not affect the
    prefix.
- **Symlink manifest**: exactly one U+2190 separator per non-empty line; link paths
  relative and non-escaping; every target resolves inside the prefix (or is a
  prefix-absolute target).
- **Install marker**: present, and records the expected prefix and source revision.

Outputs, next to the zip: `manifest-<arch>.json` with the executable list, the symlink
list, archive size, file count, SHA-256, the ELF interpreter and RUNPATH, the three scan
counts and every check's result — plus, with `--emit-kotlin`, a ready-to-paste
`TermuxBootstrapCatalog.Entry`.

### Calibration evidence (real, executed)

The checker was validated against the **published upstream archive** rather than trusted,
using the `--profile official` calibration mode (which relaxes the prefix expectations and
reports instead of failing where the official prefix is expected):

```
$ python3 tools/termux-bootstrap/verify_bootstrap.py \
      --zip bootstrap-aarch64.zip --arch aarch64 --profile official
OK: bootstrap-aarch64.zip verified for /data/data/com.termux/files/usr
  files 3478, directories 295, symlinks 1213, executables 312, ELF 338
  sha256 9ddc32921187c85b04556bf56c6cce94e00b813ecd9299959a2d9b7c33386994
  size   32845839 bytes
  elf-machine  pass (338 ELF files)      elf-runpath    pass (.../usr/lib)
  elf-needed   pass (reported below)     script-shebang pass (113 scripts)
  symlink-manifest-format pass (1213)    symlink-targets pass
  required-executables pass (21 tools)   required-files  pass
```

Zero false positives on real upstream output. The two libraries reported as unresolved are
upstream's own (`bin/lsns -> libmount.so`, and the sanitizer test binaries under
`libexec/installed-tests/`), which is why that check reports rather than fails.

The same archive under the **agentx** profile fails, which is the non-negotiable constraint
demonstrated rather than stated:

```
$ python3 tools/termux-bootstrap/verify_bootstrap.py \
      --zip bootstrap-aarch64.zip --arch aarch64 --profile agentx
FAILED: 7 violation(s)
  ! 392 file(s): 'lib/libacl.so: RUNPATH /data/data/com.termux/files/usr/lib != /data/data/com.agentx.app/files/usr/lib'
  ! 113 script(s): 'libexec/dpkg/dpkg-db-backup: #!/data/data/com.termux/files/usr/bin/sh'
  ! 697 file(s) still contain the official prefix path "data/data/com.termux"
  ! the prefix /data/data/com.agentx.app/files/usr does not appear anywhere
  ! etc/termux/agentx-bootstrap.ok is missing
  ! apt is pointed at an official Termux repository: deb https://packages-cf.termux.dev/apt/termux-main/ stable main
```

Running the post-processor over that same official archive exercises the rest of the
pipeline on a real file (1213 symlink lines validated, marker written, sources disabled,
archive repacked from 32845839 to 32846884 bytes) and the remaining failures are exactly
the ones that only a rebuild can fix — which is the point:

```
$ postprocess-bootstrap.sh --zip bootstrap-aarch64.zip --arch aarch64 --out-dir dist
  symlink manifest: 1213 entries, all with exactly one U+2190
wrote etc/termux/agentx-bootstrap.ok
apt sources: disabled (no AgentX repository configured)

$ verify-bootstrap.sh --zip dist/bootstrap-aarch64.zip --arch aarch64
  install-marker             pass      <- lifted by post-processing
  apt-sources                pass      <- lifted by post-processing
  elf-runpath                FAIL      <- needs a real rebuild
  script-shebang             FAIL      <- needs a real rebuild
  prefix-scan-paths          FAIL      <- needs a real rebuild
```

### Executed evidence for the Kotlin side

`:termux-runtime` is an Android library module: its unit tests need the Android Gradle Plugin
and an SDK, neither of which exists in the environment Part 2 was written in, so
`./gradlew test` could not be run here. That is a real gap in the evidence, and it is why
[`jvm-logic-check/Check.kt`](jvm-logic-check/Check.kt) exists: it re-runs the assertions of
`TermuxBootstrapArchiveTest` and `TermuxBootstrapCatalogTest` against the **real** sources on
a plain JVM, which needs nothing but a JDK and `kotlinc`. It sits outside every Gradle source
set, so it cannot affect the build; the Gradle tests remain the source of truth for CI.

```
$ kotlinc -d /tmp/logic-check.jar \
    termux-runtime/src/main/kotlin/com/agentx/app/termux/TermuxPaths.kt \
    termux-runtime/src/main/kotlin/com/agentx/app/termux/TermuxBootstrapArchive.kt \
    termux-runtime/src/main/kotlin/com/agentx/app/termux/TermuxBootstrapCatalog.kt \
    tools/termux-bootstrap/jvm-logic-check/Check.kt
$ java -cp "$KOTLIN_HOME/lib/kotlin-stdlib.jar:/tmp/logic-check.jar" CheckKt
…
checks: 47, failures: 0
```

Run with `kotlin-compiler-2.4.20` and `openjdk 21`. It covers all three symlink target shapes
a real archive ships, the escape cases that must stay refused, a mixed manifest parsing without
dropping a link, and the catalog contract — including that a blank URL, a missing or malformed
digest, a missing size, a missing file count and an `unbuilt` revision each make an entry
unavailable on their own.

What it does **not** cover: `TermuxBootstrapInstaller.kt` and everything else that links against
`android.*`, the vendored terminal modules or `kotlinx.coroutines`. The installer's two changed
call sites were checked against the new signatures (the named arguments match one-to-one), but
the module has not been compiled — **the first real compile is CI's**, and it has not run.

## Package repository strategy

`pkg install` fetches `.deb` files from an apt repository. The official repositories
(`packages-cf.termux.dev`, `packages.termux.dev`) serve packages compiled for
`/data/data/com.termux/files/usr`. Installing one into the AgentX prefix produces a binary
whose `RUNPATH` points at a directory this app does not own; it cannot start. The upstream
default `etc/apt/sources.list`, shipped by the `apt` package, is:

```
deb https://packages-cf.termux.dev/apt/termux-main/ stable main
```

**Decision: option (b) — a repository is not hosted by this project, so apt is pointed at
nothing rather than at a repository known to be incompatible.** `postprocess-bootstrap.sh`
replaces that file with an explicitly empty configuration: the official lines are present
but commented out, with the reason stated in the file itself, and no active `deb` line
exists unless a repository URL was passed to the build.

Consequences, stated plainly:

- `pkg install <package>` fails with `Unable to locate package`. It fails loudly and
  immediately; it does not install something that cannot run.
- `pkg upgrade` and `apt update` have no sources to refresh.
- The base bootstrap's packages still work: `sh`, `bash`, all of `coreutils`, `tar`,
  `gzip`, `sed`, `grep`, `gawk`, `apt`, `dpkg` and `pkg` are present and runnable. What is
  missing is everything not in the bootstrap set — `git`, `nodejs`, `python`, `clang`.
- The verifier refuses to let apt reach an official repository: it fails the build if
  `sources.list` has an active `deb` line naming `termux.dev`, and `postprocess` refuses an
  `--apt-repo-url` pointing there.

To offer package installation later, build the packages from the same pinned revision with
the same prefix patch, publish the resulting repository, and pass its URL — either as the
`apt_repo_url` workflow input, or by adding a line to `etc/apt/sources.list`. The
`publish` job supports this without any further code change. Until then, the honest
statement is: **the bootstrap works, package installation is unavailable.**

## Running the workflow

1. Push this repository (or just this branch) so the workflow exists on GitHub.
2. **Actions → Build AgentX Termux bootstrap → Run workflow.**
   - `termux_packages_commit`: keep the pinned default unless you intend to re-verify.
   - `release_tag`: a fixed tag, e.g. `agentx-bootstrap-2026.09.29-r1`. `latest` is
     rejected by the workflow: it is mutable, so published digests would stop matching.
   - `only_arch`: set `aarch64` to build one architecture while you are still reading logs.
   - `apt_repo_url`: leave empty for the disabled-sources behaviour above.
   - `publish`: false for a dry run, true to create the release.
3. Read the job summary and the uploaded `verify-<arch>.txt`. Confirm the printed prefix
   evidence says `/data/data/com.agentx.app/files/usr`.
4. With `publish: true` and no `only_arch`, the release is created or updated with the four
   zips, their `.sha256` files and their `manifest.json` files.
5. Copy the `TermuxBootstrapCatalog.Entry` block from each `verify-<arch>.txt` (or
   `--emit-kotlin` output) into
   `termux-runtime/src/main/kotlin/com/agentx/app/termux/TermuxBootstrapCatalog.kt`,
   replacing the `pending(...)` call for that ABI and filling in the immutable release
   asset URL. Then run `./gradlew :termux-runtime:test`, which enforces the published-entry
   contract.

Runtime limits, honestly: a single architecture builds ~80 packages inside the
termux-packages builder container and takes hours. The job timeout is 330 minutes, just
under GitHub's 6 hour ceiling. Disk is the likelier problem than time — the workflow frees
the unused toolchains first, but a single-arch job still needs roughly 15–25 GB. If a job
fails on either, build one architecture per run with `only_arch`, or use a larger runner.

## What remains unverified

- **No archive has been built, and no bootstrap has been executed.** Nothing in this
  repository has run `sh`, `bash`, `printf 'hello\n'`, `command -v pkg` or
  `command -v apt` inside the AgentX prefix, because no AgentX archive exists yet and this
  environment cannot build one. There is no Android emulator and no QEMU user-mode
  emulation available here either, so the runtime smoke test is **unverified and left for
  Part 3**, which is also where the APK-level acceptance run belongs.
- The build scripts have been exercised against a real archive for the parts that do not
  need a toolchain (see the calibration evidence above), and `apply-agentx-prefix.sh`
  asserts its upstream assumptions at run time — but the first real build is still a first
  real build.
- The app-package substitution in `bin/*` is enforced by the verifier but has not been
  observed end to end: until an archive exists, whether every one of those tools lands on
  `com.agentx.app` is a prediction from `termux_step_patch_package.sh`, not an observation.
- Part 3's real APK smoke test is what turns any of this into "Termux support works". Until
  it passes, none of these artifacts should be described as working.
