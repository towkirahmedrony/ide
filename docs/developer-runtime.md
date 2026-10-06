# AgentX embedded developer runtime (Ubuntu ARM64)

The primary execution backend is a real Ubuntu ARM64 userland run through PRoot. The legacy
Termux bootstrap is kept intact as a fallback **and is not part of this runtime**: nothing below
downloads, installs, initialises or reads it, and the Ubuntu path does not fall back to it.

## Execution chain

```
AgentX
  ↓  context.applicationInfo.nativeLibraryDir       (resolved at runtime, never hardcoded)
nativeLibraryDir/libproot.so
  ↓  PROOT_LOADER = nativeLibraryDir/libproot_loader.so
PRoot
  ↓  Ubuntu ARM64 rootfs (app-private storage)
real Linux shell/tools: bash, apt, dpkg, git, gh, python3, node, npm, curl, wget, ssh, rg
```

Only `libproot.so` (and the vendored pty's `libtermux.so`) are executed directly by Android.
Guest binaries are never `execve`d from `filesDir`; they are addressed through the PRoot loader.
This is what allows `targetSdk = 37` without a downgrade or a compatibility workaround.

It is deliberately **not** `AgentX → Termux bootstrap → Ubuntu`. The legacy bootstrap sits
outside the chain entirely.

## Module

`:ubuntu-runtime` (`com.agentx.app.ubuntu`) is an Android library deliberately isolated from the
legacy bootstrap. It depends on `:termux-runtime` **only** for layers that are not bootstrap
code: the vendored `TerminalSession`/PTY, `TermuxShellSpec`, the terminal host/client ports and
the reusable SAF copy engine (`TermuxWorkspaceMirror`, `MirrorSource`/`MirrorSink`). It never
references `TermuxBootstrapCatalog`, `TermuxBootstrapInstaller`, `TermuxPrefixPolicy`,
`TermuxShellResolver` or `TermuxBootstrapArchive`.

| File | Responsibility |
| --- | --- |
| `UbuntuRuntimeState.kt` | `AgentxRuntimeState` (NOT_INSTALLED … VALIDATING … ERROR) and `RuntimeStatus`, the single source of truth for the UI. |
| `NativeRuntime.kt` | `NativeRuntimeLayout` (nativeLibraryDir + app-private runtime storage, the verification marker and the workspaces directory) and the native library probe. |
| `ProotCommand.kt` | The `proot -0 -l -r <rootfs> -b … -w … <cmd>` builder, the extraction invocation, bind mounts and `PROOT_LOADER`/`PROOT_L2S_DIR`/`PROOT_TMP_DIR`. |
| `UbuntuRootfsCatalog.kt` | The pinned Ubuntu Base arm64 entry (URL, SHA-256, size), the required guest files, the required **hard links**, the toolchain package list, the required toolchain executables and the platform tar path. |
| `UbuntuRootfsInstaller.kt` | Download → SHA-256 → PRoot extraction into `rootfs.installing` → validation (files, hard-link relationships **and link-store location**) → apt/resolv configuration. Also `isConfiguredRootfs()` (the one authoritative answer to "is this a rootfs?"), `promote()`, `repairIncompleteInstallation()`, `discardRootfs()` and the marker writers. |
| `UbuntuRuntimeVerifier.kt` | Runs the installed rootfs *through PRoot* and only then lets it become READY — including the `perl`/`uncompress` hard-link probes. |
| `UbuntuToolchain.kt` | The `apt`/`dpkg` sanity check, `apt-get update`, the package install, and the check that runs every required executable. It decides whether the tree can be retried or must be recreated. |
| `UbuntuEnvironment.kt` | The guest `HOME`/`USER`/`PATH`/`TERM`/`TMPDIR`/`AGENTX_RUNTIME` environment, with credential filtering. |
| `UbuntuWorkspaceBinding.kt` | Resolves a workspace handle into a bind: a real host path as-is, a SAF tree URI as the phone-storage path it names, both exposed at `/workspace` — or why it is not bound. |
| `UbuntuWorkspaceMaterializer.kt` | Optional: copies a SAF `content://` project into app storage for a caller that explicitly wants a private copy. The terminal does not use it — it binds the original folder. |
| `AgentxExecution.kt` | The backend-agnostic `execute(command, workingDirectory)` seam for future agents. |
| `LocalUbuntuRuntime.kt` | The runtime: status flow, provisioning + verification, project preparation, terminal spec, toolchain install, process execution. |
| `AgentxStorageAudit.kt` | Read-only, labelled, bounded measurement of where app-private storage went, with a `StorageClass` per location and a per-project breakdown of the project copies. Backs the **Logs → Storage** action. |

## Rootfs extraction: the hard-link problem, and its fix

Ubuntu Base stores two entries as hard links (verified against the published archive; see
[`third_party/ubuntu/README.md`](../third_party/ubuntu/README.md)):

```text
usr/bin/perl5.38.2 link to usr/bin/perl
usr/bin/uncompress link to usr/bin/gunzip
```

Android's SELinux policy forbids an `untrusted_app` from creating a hard link at all — a
`neverallow` rule in `platform/system/sepolicy` — so running the platform `tar` directly stops at
the first of them:

```text
tar: can't link 'usr/bin/perl5.38.2' -> 'usr/bin/perl': Permission denied
tar: can't link 'usr/bin/uncompress' -> 'usr/bin/gunzip': Permission denied
tar: had errors
```

and leaves a tree that is missing both entries. The fix is not to touch the archive: the
extraction is executed by the runtime's own **PRoot** with `-l` (link-to-symlink), the same
mechanism Termux's `proot --link2symlink tar` uses. Each `link(2)`/`linkat(2)` becomes a symlink
to the same content, which PRoot keeps in `PROOT_L2S_DIR` — **inside the rootfs**, at
`<rootfs>/.l2s`; both names still lead to one file, and nothing is copied, dropped or duplicated.
The archive's SHA-256 is checked immediately before the extraction and is not weakened by any of
this. The store's location is not a detail: see
[Why the link store must be inside the rootfs](#why-the-link-store-must-be-inside-the-rootfs).

 `ProotCommand.extraction` builds exactly:

```
libproot.so -l -w / /system/bin/tar -xzf <archive> -C <rootfs>
```

with `PROOT_LOADER`, `PROOT_TMP_DIR` and `PROOT_L2S_DIR` set. No `-r` is passed: PRoot is acting
as the link interposer for this one step, not as the guest root. If the native PRoot/loader pair
is missing from `nativeLibraryDir`, provisioning fails with a `runtime`-stage error that names the
missing libraries instead of extracting something that could never run.

### Extraction order

```
download
  ↓
SHA-256 verify
  ↓
extraction into rootfs.installing, link store created first
  ↓
rootfs validation (required files, hard-link relationships, link store location)
  ↓
apt / resolv configuration
  ↓
guest verification through PRoot
  ↓
apt/dpkg sanity check → apt-get update → install the developer packages
  ↓
every required executable runs
  ↓
install marker + verification marker + toolchain marker
  ↓
READY
```

The tree is unpacked into `runtimeDir/rootfs.installing` and the link store is created **inside
that tree**. Nothing is ever unpacked into `runtimeDir/rootfs`: extraction, package installation
and toolchain validation all happen in the installing tree, and only a tree that has passed every
gate is moved into place. If any step fails the installing tree is deleted and whatever was already
installed is left untouched, so there is never a half-built rootfs where the runtime would find it.

The move is done by `UbuntuRootfsInstaller.promote()`, and it is not a bare `rename`. `-l` records
the store's absolute path inside every emulated hard link, so the links are rewritten from the
installing path to the final one first (`UbuntuFiles.rewriteSymlinkTargets`, the same fix upstream
`proot-distro` applies after moving a rootfs); the old rootfs is moved aside rather than deleted, so
a failed move can be undone. See
[Why the link store must be inside the rootfs](#why-the-link-store-must-be-inside-the-rootfs).

The markers are written after the move, in this order: promotion, then the verification marker,
then the install marker, then the toolchain marker. A marker can therefore never describe a tree
that is still under construction.

A failure removes the incomplete tree, clears every marker, leaves the runtime in `ERROR`, and a
retry starts again — the verified archive is kept, so retrying costs no download. A tree the guest
declares damaged (a `dpkg` database in a mess, a half-applied unpack, a toolchain that installs but
does not verify) is discarded and rebuilt from that archive, once.

### Hard-link validation

Checking that `usr/bin/perl` and `usr/bin/perl5.38.2` merely exist is not enough: an extractor
that wrote each entry as an independent copy would pass that test while silently doubling a
64-bit `perl` binary and breaking `dpkg`. Validation therefore checks the **relationship**: both
paths must resolve (symlinks followed) to the same file — which is true for a real hard link and
for PRoot's emulation, and false for two copies, for a dangling link, or for a missing entry.

That relationship check is necessary but **not sufficient**, and relying on it alone is what let
the original defect ship: it follows the links with the *host's* `stat`, and the wrong link store
resolves perfectly on the host while being unopenable in the guest. So validation also checks the
**prefix**: every symlink standing in for a hard link must name a path inside the tree that is
about to become `/`.

## Why the link store must be inside the rootfs

`-l` does not create a hard link. It moves the file into the store and leaves a symlink whose
target is the store's **absolute path, spelled as the host sees it**
(`extension/link2symlink/link2symlink.c`). When a guest opens one of those symlinks, PRoot's
`canonicalize()` dereferences it and runs the target through `detranslate_path()`, which strips the
guest root prefix **only when the target lies under the root**; anything else is returned unchanged
and then re-canonicalized as a *guest* path. So a store at `<runtimeDir>/l2s` produces links that
read `/data/user/0/…/l2s/…` and are looked up as `<rootfs>/data/user/0/…/l2s/…` — nothing there,
`ENOENT`, for every hard-linked file.

That is the reported failure. `/usr/bin/perl` and `/usr/bin/perl5.38.2` are one of Ubuntu Base's
two hard-link pairs, so they were unopenable in the guest from the moment the tree was extracted;
the moment `dpkg` created a hard link of its own while unpacking `perl-base`, the next `chown()`
on that path died and took the `dpkg-deb` pipe with it:

```
dpkg: error processing archive /var/cache/apt/archives/perl-base_5.38.2-3.2ubuntu0.6_arm64.deb (--unpack):
 error setting ownership of '/usr/bin/perl5.38.2.dpkg-new': No such file or directory
```

Keeping the store at `<rootfs>/.l2s` — which is also what upstream `proot-distro` pins
(`proot_distro/l2s.py`) — makes the prefix strip yield `/.l2s/…`, which resolves. The guest
verifier probes `perl` and `uncompress` by *running* them, because that is the only check that
reads the link the way the guest does.

## PRoot + loader

`PROOT_LOADER` is always set to `nativeLibraryDir/libproot_loader.so` — the actual interposer, not
a copy, and never inside `filesDir`. `PROOT_L2S_DIR` is `<rootfs>/.l2s`: inside the guest rootfs,
as explained above, created by the extraction before `tar` runs. `PROOT_LOADER32` is only set when
a 32-bit guest process is started, which this runtime does not do.

## Rootfs

Not bundled. On first run the official Ubuntu Base 24.04.5 arm64 archive is downloaded (≈ 30 MB),
checked against the SHA-256 pinned in `UbuntuRootfsCatalog`, extracted **through PRoot** as
described above, validated for `bash`, `sh`, `dash`, `apt-get`, `dpkg`, `env`, `ls` and the two
hard-link pairs, configured for PRoot/apt/DNS, and only then moved into place and marked
installed. The extraction is roughly 110 MB on disk. A checksum mismatch deletes the archive and
aborts; there is no insecure skip.

`apt` is configured for `ports.ubuntu.com/ubuntu-ports` (`noble`, `-updates`, `-security`), with
`APT::Sandbox::User "root"` and `Install-Recommends "false"`.

## Verification before READY

Having the files on disk is not the same as having a working guest, so the terminal is not started
until the installed rootfs has been **run through PRoot** and answered:

```text
/bin/sh -c 'echo AgentX Ubuntu OK'
/bin/bash --version
/usr/bin/id            → uid=0(root), PRoot's fake root
/usr/bin/pwd           → /root
/usr/bin/apt-get --version
/usr/bin/dpkg --version
```

The marker `<runtimeDir>/rootfs-verified.ok` is written only after every probe passes, and
`isReady()` requires it. A failure leaves the runtime in `ERROR` naming the probe that failed, and
the next attempt re-verifies the existing rootfs rather than downloading it again. A rootfs that
extracted but cannot execute a shell is therefore never presented as a working terminal.

## Terminal

The existing vendored terminal emulator and PTY are reused unchanged. `LocalUbuntuRuntime.specFor`
returns an ordinary `TermuxShellSpec` whose executable is `libproot.so` and whose arguments are
the guest command; the session manager, foreground keep-alive service, key handling (Ctrl+C,
Ctrl+D, arrows, tab), resize and ANSI rendering are all the ones that already existed. There is
no second foreground service. The primary shell is `/bin/bash --login` inside the guest.

While the rootfs is not yet installed/verified, the terminal still gets a real pty on Android's
own `/system/bin/sh` — but it deliberately does **not** start `$PREFIX/bin/login`. Android refuses
to execute an app-private binary on a modern `targetSdk`, which is what produced the reported
`exec("/data/data/com.agentx.app/files/usr/bin/login"): Permission denied`; the legacy prefix is
bypassed (`TermuxRuntime.specFor(..., forceTemporarySystemShell = true)`) whenever the Ubuntu
runtime owns the terminal.

## First run

When the rootfs is missing the Terminal tab shows the **AgentX Developer Runtime** card —
"Ubuntu ARM64 · Required for Terminal, Git, Python, Node and local development · [Install
Runtime]" — followed by download, verification, extraction, setup and guest-verification
progress. On success the shell is replaced automatically; no bootstrap command is typed.

## Workspaces

Regardless of where a project comes from, the opened project is exposed to the guest at exactly
`/workspace`. The real host directory — whether it is a repository AgentX cloned into its managed
storage or an existing folder picked from phone storage — is bind-mounted there, so a file created
in the terminal is the same file the IDE's Files and editor see, the shell starts with `pwd` =
`/workspace`, and nothing is ever copied into the rootfs.

A project opened through Android's Storage Access Framework keeps its files in their original
location. The persisted tree URI (`primary:Projects/app` style document id) is resolved back to
the phone-storage path it names (`/storage/emulated/0/Projects/app`) and that directory is what
gets bound — no copy is made. A `content://` URI is **never** passed to PRoot. When the folder has
no derivable filesystem path (a cloud provider, say), or the app cannot read the path ("All files
access" not granted), no bind is made: the shell runs in the guest home with the reason, and the
terminal stays usable.

With no project selected the terminal starts in `/root` (the guest home) and mounts nothing at
`/workspace`: no convenient directory is chosen instead, and the shell says in the note above it that
no project is open. A terminal that mounted an arbitrary folder under `/workspace` would look like a
working project shell while being about something the user never opened.

### The terminal follows the active project

A terminal belongs to the project it was opened for, for the life of the session:

```
Active project  →  /workspace  →  terminal
```

- **A new session starts in `/workspace`.** The project's directory is bind-mounted there and the
  guest login shell is started with `-w /workspace`, so `pwd` answers `/workspace` and no command
  needs the project's real Android path. The environment is the stable Ubuntu one
  (`HOME=/root`, `SHELL=/bin/bash`, `PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`,
  `TERM=xterm-256color`, `TMPDIR=/tmp`), with `AGENTX_PROJECT=/workspace` naming the mount.
- **Switching projects closes the shells of the project that was left**, before the new project's
  shell is opened. Sessions are keyed by the project they belong to
  (`TerminalProjectKeys`), so the switch is a membership test: the active project keeps all of its
  terminals and the previous project keeps none, and its `/workspace` entry goes with it. A shell
  left over from project A would be a shell still operating on a `/workspace` that is no longer the
  active project.
- **A restart rebuilds the spec from the project that is open now** — working directory, bind and
  environment alike — so restarting a terminal cannot resurrect an older mapping, and the open
  project does not have to be reopened.
- **The extra terminal is a second terminal for the same project.** It exposes the same active
  project at `/workspace` exactly as the first one does, so a second shell can never become a way to
  end up working somewhere other than the project the user has open.
- **At most one `/workspace` bind is generated** (`ProotCommand.withProject` appends one only when
  none is present), so a session cannot end up with a duplicate `-b project:/workspace`.

Nothing about this touches PRoot, the PTY, the emulator or the rootfs. The lifecycle decides *what*
command is run and *where* it is rooted, and hands it to the existing terminal implementation
unchanged.

### The one place a project is copied

`UbuntuWorkspaceMaterializer` is the exception to "nothing is copied", and it is opt-in: the
terminal does not use it. It exists for a caller that needs a project at a **real** path when the
project is a SAF tree with no derivable filesystem path, and it writes that copy to

```
<filesDir>/developer-runtime/workspaces/<mirrorSegment(handle)>
```

with a `.agentx-mirror.ok` marker and a per-process cache, plus `forget(handle)` for the caller. A
directory there is duplicated project data: AgentX owns it, nothing else needs it, and it is
therefore in scope for the cleanup below. The legacy Termux backend has the same idea at
`<filesDir>/workspaces/<safeSegment(id)>`; both roots are cleaned.

## Storage

Two numbers are reported for this app and they are different things: the APK on disk (about 86 MB
with the PRoot native libraries) and Android's app-info size, which adds the app's private data. The
second one grows to roughly 1 GB once the developer runtime is installed. That growth is
**intentional persistent runtime data**, and it is reported rather than removed:

| Location | Class | Roughly | Why it is there |
| --- | --- | --- | --- |
| `developer-runtime/rootfs` | persistent runtime | ~110 MB extracted, more after the toolchain installs | The Ubuntu userland and the `apt`-installed toolchain. One runtime serves every project. |
| `developer-runtime/downloads` | re-downloadable cache | ~30 MB | The Ubuntu Base archive, kept so a retry costs no download. |
| `developer-runtime/tmp` | temporary | small | `PROOT_TMP_DIR`. |
| `developer-runtime/rootfs.installing`, `rootfs-staging` | temporary | small | Staging that is promoted into `rootfs`, or left over from an older build. |
| `developer-runtime/workspaces`, `workspaces` | project copies | project-sized | The materialiser copies above and the legacy mirrors: app-owned copies of a project that lives somewhere else. Each is removed with its own project. |
| `projects` | app files | project-sized | Projects AgentX created itself, one directory per project. Unlike a copy, the project *is* this directory — it is not a duplicate of anything, and it is removed with its own project. |
| `diagnostics/terminal.log`, `terminal-diagnostics.log` | diagnostics | ≤ 5 MB + tail | The developer log and the terminal recorder, both rotated. |
| `skills`, `agent-sessions` | app data | small | Imported skills and persisted agent sessions. |
| `nativeLibraryDir` | APK | ~ tens of MB | `libproot.so`, the loader, `libtalloc`, `libandroid-shmem` — reinstalled with the app. |

Opening a project does not move any of this: the project's own folder is bind-mounted at
`/workspace` in place (a SAF tree resolved to the path it names). The runtime is installed once, per
device, not per project.

To check that on a device, **Settings → Logs → Storage** walks the tree and writes a `STORAGE` entry
to the developer log: a total, a per-class summary, every measured location, and a per-project line
for the project copies. It is read-only and runs off the main thread. A reading that hits
`AgentxStorageAudit.MAX_ENTRIES` says so and is labelled a lower bound rather than being presented
as a measurement.

### What deleting a project removes

Deleting a project from Home is the caller of `WorkspaceManager.delete(id)`, and the confirmation it
requires is the only entry point. What it removes:

- the project's record, so it stops appearing in the list;
- the project's own directory, when AgentX created the project itself: `<filesDir>/projects/<name>`
  from the Create New Project flow. The project *is* that directory, and once the record is gone
  nothing could reach it again;
- the AgentX-owned directories that belong to it — `<root>/<name>` under the two project-copy roots
  above, where `<name>` comes from the same `TermuxWorkspaceBindings` functions that created them.

What it deliberately does **not** remove:

- the folder the user opened, wherever it lives. A SAF tree is never physically deleted by a
  managed-project cleanup, and no delete decision is taken about the user's own files;
- the shared runtime: `rootfs`, `downloads`, `tmp`, `rootfs.installing`, `rootfs-staging`, the
  native libraries;
- terminal sessions or any other runtime infrastructure;
- any other project — including a project whose *name* matches one being deleted, and including a
  managed project whose name matches another project's copy directory;
- the storage roots themselves.

`OwnedWorkspaceProjectStorage` enforces that by construction, with the two kinds of root kept
apart:

- a **copy root** (`developer-runtime/workspaces`, `workspaces`) is searched for
  `<root>/<name>`, where `<name>` is derived from *this* record's handle and id;
- a **managed root** (`projects`) is only ever reached through the record's own handle: the delete
  removes `handle` only when `handle` is a path whose parent is exactly the managed root. A SAF
  record's handle is a `content://` tree URI, which never matches, and a project in shared storage
  called `MyApp` cannot delete `<projects>/MyApp` — its parent is not the managed root.

A name in either case has to be a single safe path segment: empty, `.`, `..`, a separator or a NUL
is refused rather than sanitised. The record is removed *after* the cleanup, so a cleanup that could
not finish leaves the project visible and retryable instead of orphaning data the user can no longer
reach — and a delete is never reported as complete when a directory could not be removed.

## Networking

PRoot and Android share the network namespace, so a guest `python3 -m http.server 8080` is
reachable from Android at `http://127.0.0.1:8080` with no proxy or tunnel. Guest DNS is provided
by a generated `resolv.conf`, built from the active network's DNS servers and bind-mounted as
`/etc/resolv.conf`.

## Developer toolchain

The base image already ships `bash`, `apt`/`apt-get`, `dpkg`, `coreutils`, `tar` and `gzip`. The
rest of the toolchain is installed by the guest's **own** `apt-get`. `UbuntuToolchain` runs the
whole sequence in order, and every step is a gate rather than a log line:

```
dpkg --audit / apt-get check / dpkg-query        (the apt/dpkg sanity check)
  ↓
apt-get update
  ↓
apt-get install -y --no-install-recommends \
  bash apt apt-utils dpkg debconf git gh python3 python3-pip nodejs npm \
  curl wget ca-certificates openssh-client ripgrep
  ↓
run every required executable and check what it printed
```

from `ports.ubuntu.com/ubuntu-ports`. There is no Termux package repository, no Termux package and
no second package ecosystem: these are the Ubuntu packages the Ubuntu userland expects.

This is **part of READY**, and it runs inside `provision()` before READY is published, so the
terminal is opened against a runtime whose tools actually answer. Only the last step can promote a
runtime: a package that unpacked is not a binary that runs, and the failure this area was fixed for
left a `dpkg` database that answered `install ok installed` for every package — with an empty
`dpkg --audit` and a clean `apt-get check` — while `/usr/bin/perl` could not be executed at all.
Nothing short of running the tools detects that, so nothing short of running the tools is accepted.

A failed install clears every marker, which keeps `isReady()` false and stops any terminal from
claiming otherwise; there is no stale READY after a partial installation. `debconf` is in the list
because it provides `/usr/sbin/dpkg-preconfigure`, which the base image's `70debconf` apt hook calls
and the base image does not ship.

`LocalUbuntuRuntime.installToolchain()` exposes the same sequence for a caller that wants to retry
just the package step against a rootfs that has already passed its PRoot probes.

## Runtime states

`NOT_INSTALLED → DOWNLOADING → VERIFYING → EXTRACTING → INSTALLING → VALIDATING → READY →
STARTING → RUNNING`, with `ERROR` reachable from any step and carrying the stage that failed
(`download`, `checksum`, `extraction`, `validation`, `configuration`, `activation`, `runtime`).
`READY` is only entered after the complete rootfs has been extracted, validated, verified through
PRoot, and then had its developer toolchain installed and run.

## Retry

Because a device may hold a partially extracted or partially installed rootfs from an earlier
attempt, `UbuntuRootfsInstaller.repairIncompleteInstallation()` runs at the start of every
provisioning: it deletes `rootfs-staging` (scratch left by an older build), deletes a `rootfs` that
is missing required guest files, and deletes a `rootfs` that was marked for recreation
(`discardRootfs`). A complete
extracted tree without the install marker is kept and re-verified through PRoot (the marker is
written only after `/bin/sh` and `/bin/bash` answer). The downloaded archive and the
link-to-symlink store are preserved. The user never has to delete app-internal files by hand.

## Legacy bootstrap

`:termux-runtime` and the Termux bootstrap stay in the tree and keep building. They are now the
fallback for a build with no developer runtime wired in (previews/tests); when the Ubuntu runtime
is present it owns the terminal and the legacy prefix's login shell is not started. No bootstrap
asset, catalog entry or release was regenerated or changed, and nothing in the Ubuntu runtime
references any of it. It is slated for removal in a later cleanup phase, after the developer
runtime passes a real-device run.

## Verification status

**Not verified on a device.** This change was authored in an environment with no JDK, Android
SDK, NDK or ARM64 device, and this repository's rule is that Android artifacts are built only by
GitHub Actions.

Verified here, by executing it:

- the pinned Ubuntu Base archive re-downloaded and hashed: the SHA-256 and the size in
  `UbuntuRootfsCatalog` both match (`a91d5a93…14f2`, 29,936,675 bytes);
- the archive's contents listed: 3,413 entries, 194 symlinks and **exactly two** hard links —
  `usr/bin/perl` ↔ `usr/bin/perl5.38.2` and `usr/bin/gunzip` ↔ `usr/bin/uncompress` — both with
  the target before the link entry, which is what `REQUIRED_HARD_LINKS` pins;
- the PRoot revision that provides `-l` and `PROOT_L2S_DIR` read from source
  (`termux/proot` at the pinned revision): the extension handles both `link` and `linkat`, which is
  what the platform tar uses.

The Kotlin is unit tested in CI (`:ubuntu-runtime:test` — catalog, PRoot command line including the
extraction invocation and `proot -V`, environment, bindings, native layout, PRoot self-test and
the guest-probe verifier). The APK workflow builds PRoot from pinned upstream with the NDK,
vendors `jniLibs/arm64-v8a/`, assembles the APK, and fails if `libproot.so` /
`libproot_loader.so` / `libandroid-shmem.so` are missing from `lib/arm64-v8a/`.

**Still open, and it must not be described as working until it passes** — the on-device checklist:

1. the archive downloads, verifies and extracts without the `can't link … Permission denied`
   failure, and both hard-link pairs are present afterwards;
2. the six guest probes pass and the runtime reaches READY;
3. `/bin/bash --login` starts interactively with a working PTY (typing, Ctrl+C, Ctrl+D, resize);
4. `apt-get` and `dpkg` work, and `installToolchain()` installs git, gh, python3, node and npm;
5. a project is visible at `/workspace` and `touch` there appears in the IDE's file tree;
6. `python3 -m http.server 8080` stays reachable at `http://127.0.0.1:8080` and Ctrl+C stops it.
