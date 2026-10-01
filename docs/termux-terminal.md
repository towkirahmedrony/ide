> **Legacy / fallback.** The primary terminal backend is now the embedded Ubuntu ARM64
> developer runtime (`:ubuntu-runtime`), documented in [developer-runtime.md](developer-runtime.md).
> This Termux path is kept intact and still builds, and the Terminal tab falls back to it when
> the developer runtime is absent or not installed. No bootstrap asset, catalog entry or release
> was regenerated. It is slated for removal in a later cleanup phase, after the developer runtime
> passes a real-device run.

# Embedded Termux terminal

The Terminal tab runs a real Termux environment: Termux's own terminal emulator rendering
through Termux's own view, on a kernel pseudoterminal, against Termux's own package manager.

Nothing in this path is a re-implementation. The previous custom terminal — a Compose
`TextField` feeding a line-splitting pipe reader — has been removed.

## What was integrated

| Component | Source | Module here | License |
| --- | --- | --- | --- |
| Terminal emulation, session/pty plumbing, `libtermux.so` (JNI `createSubprocess`/`setPtyWindowSize`/`waitFor`/`close`) | `termux-app/terminal-emulator` | `:termux-terminal-emulator` | Apache-2.0 |
| `TerminalView`: rendering, keyboard, selection, copy/paste, pinch zoom, cursor blinking | `termux-app/terminal-view` | `:termux-terminal-view` | Apache-2.0 |
| Environment assembly, bootstrap install, session lifecycle, IDE wiring | written for this project | `:termux-runtime` | project license |

`termux-shared` is **not** vendored: it is GPL-3.0-only and `TerminalSession`/`TerminalEmulator`
do not depend on it. Provenance, the exact upstream revision and the four build-only deviations
are recorded in [`third_party/termux/README.md`](../third_party/termux/README.md).

The pseudoterminal itself comes from `terminal-emulator/src/main/jni/termux.c`, which opens
`/dev/ptmx`, `grantpt`/`unlockpt`/`ptsname_r`, `fork`s and `setsid`s. That is what makes the
shell an interactive login shell instead of a pipe, and it is why `isatty()`-dependent programs,
line editing, colours and full-screen programs work.

## How the runtime is bootstrapped

1. **Layout.** `TermuxPaths` derives the Termux filesystem layout from the app's data directory:
   `files/` is the rootfs, `usr/` is `$PREFIX`, `home/` is `$HOME`, `usr-staging/` is the staging
   prefix, `usr/tmp` is `$TMPDIR`. These are the paths termux-packages documents.
2. **Prefix check.** `TermuxPrefixPolicy` decides whether official Termux artifacts may be
   installed at all. See [Prefix constraint](#prefix-constraint) — this is the one place where the
   official artifacts cannot simply be used.
3. **Session first.** `$PREFIX/bin/<login|bash|zsh|fish|sh>` is used when the prefix has one
   (`TermuxShellResolver`, same order as `TermuxSession.execute`); otherwise `/system/bin/sh`.
   So the terminal is a real pty terminal from the first launch, before any download.
4. **Userland.** Tapping **Install** in the terminal header runs `TermuxBootstrapInstaller`:
   download the AgentX `bootstrap-<arch>.zip` for the device's ABI → verify SHA-256 against the
   digest pinned in `TermuxBootstrapCatalog` (a mismatch deletes the archive and aborts) → extract
   to `usr-staging` → apply `SYMLINKS.txt` → `chmod 0700` on `bin/`, `libexec`, `lib/apt/methods`,
   `lib/apt/apt-helper` → rename `usr-staging` to `usr` → write the install marker at
   `etc/termux/agentx-bootstrap.ok`. The move is last, so an interrupted install never leaves a
   half-populated prefix that later code would mistake for a working one. The arm64 archive is
   published and pinned in `TermuxBootstrapCatalog`; other ABIs remain unavailable until their
   verified archives are published. See [termux-bootstrap.md](termux-bootstrap.md) for the remedy.
5. **Environment file.** `$PREFIX/etc/termux/termux.env` is written so a login shell sources the
   same `$PREFIX`/`$HOME` even for a child process that did not inherit our `envp`.

## How packages are provided

The bootstrap *is* the package system. It ships `apt`, `dpkg`, `pkg`, `bash`, `dash`,
`coreutils`, `gawk`, `grep`, `sed`, `tar`, `gzip`, `termux-tools`, `termux-core`, `termux-exec`,
`libc++`/`libandroid-support` and the Termux apt configuration. There is no curated binary list
anywhere in this repository, and no package server of ours.

What it does **not** have is a package repository. The official repositories serve packages built
for `com.termux`, whose binaries cannot run in this prefix, so the bootstrap's
`etc/apt/sources.list` deliberately contains no active `deb` line. `pkg install <package>`
therefore fails with "Unable to locate package" — loudly, rather than by installing something
broken. `apt`, `dpkg` and `pkg` themselves work; what is unavailable is installing anything
outside the bootstrap until an AgentX-prefix repository is hosted. That decision, and how to lift
it, is in [termux-bootstrap.md § Package installation](termux-bootstrap.md#package-installation-is-disabled-until-an-agentx-repository-exists).

`git`, `nodejs`, `python`, `openssh`, `make` and `clang` are **not** in the base bootstrap; they
are ordinary packages. That matches a fresh Termux install — hardcoding a tool list would freeze
the environment — but until a repository exists they cannot be installed with `pkg`.

## Prefix constraint

The official bootstrap and every repository package are compiled and rewritten for the absolute
prefix `/data/data/com.termux/files/usr`:

- `scripts/build/termux_step_replace_guess_scripts.sh` rewrites script shebangs to
  `$TERMUX_PREFIX_CLASSICAL/bin/…`;
- `scripts/build/termux_step_massage.sh` does the same for `$TERMUX_PREFIX`;
- `scripts/properties.sh` derives `TERMUX__ROOTFS` from `TERMUX_APP__PACKAGE_NAME` (default
  `com.termux`) and its validator refuses a `TERMUX__PREFIX` that is not `TERMUX_PREFIX_CLASSICAL`.

An app installed as `com.agentx.app` has a different data directory, and rewriting the downloaded
binaries is not possible in place because the replacement prefix is longer than the original.
Silently extracting anyway would produce a shell full of `not found`. So the runtime refuses, and
says why, and points at the one route that works:

**Rebuild the bootstrap for this app's own package name.** The officially supported route for a
self-contained fork, and the only one this project supports: the application id is
`com.agentx.app` in every build type, and official Termux artifacts are refused rather than
adapted. See [termux-bootstrap.md](termux-bootstrap.md) for the build, the pinned upstream
revision, the verification each artifact must pass, and the publish-and-pin procedure.

Until an AgentX bootstrap exists, the Terminal tab still gives a genuine pty shell (`$PREFIX/bin/sh`
or `/system/bin/sh`), and the header says that Termux packages are unavailable and why.

## How sessions and long-running processes work

```
Terminal screen  ──attach──▶  TerminalView ──▶ TerminalEmulator
      │                                             ▲
      │ AndroidView                                 │ append(output)
      ▼                                             │
TerminalViewModel ──▶ TermuxRuntime ──▶ TermuxSessionManager ──▶ TerminalSession ──▶ pty ──▶ shell
                                                                    │
                                  TermuxSessionService (foreground) ┘  keeps the process alive
```

- **Sessions belong to `TermuxRuntime`, not the ViewModel.** `TermuxRuntime.get()` is a
  process-scoped singleton, so an Activity recreation (rotation, returning from the background)
  finds the same runtime and therefore the same shells. `TerminalViewModel.onCleared()` explicitly
  does not kill anything.
- **One shell per workspace.** `TermuxSessionManager.open(spec)` returns the running session for a
  workspace key, so a recomposition or a tab switch cannot spawn a duplicate. A session that
  exited on its own is replaced, not resurrected.
- **The process starts when the session is created, not when it is drawn.** `TermuxRuntime`
  immediately calls `TerminalSession.updateSize(80, 24, …)`, which allocates the pty and starts
  the shell. A later `TerminalView.attachSession()` resizes the existing pty; it does not start a
  second process.
- **Keep-alive.** `TermuxSessionService` is a foreground service started when a session exists and
  stopped when the last one exits. That is what keeps `npm run dev` alive while the app is
  backgrounded, given Android 12+ trims non-foreground child processes.
- **Ctrl+C** is the on-screen key sending `0x03` to the pty, which the line discipline turns into
  `SIGINT` for the foreground process group — the same byte a hardware terminal sends. All other
  keys (Tab, arrows, Alt combinations, IME input, `Ctrl` from a hardware keyboard) go through
  `TerminalView`'s own key handling; the IDE does not intercept them.
- **No leaked resources.** `TerminalSession` owns its reader, writer and `waitpid` threads and
  closes the pty file descriptor and both I/O queues when the process exits. `TermuxSessionManager`
  has no coroutine scope of its own; the only scope in the runtime belongs to `TermuxRuntime` and
  is cancelled by `release()`.

## Workspaces

`TermuxWorkspaceBindings.resolve` produces one of three outcomes, and the UI reports which:

| Workspace | Result | Shell behaviour |
| --- | --- | --- |
| Real filesystem path | `Direct` | the shell starts in the project directory; `pwd` shows it |
| SAF `content://` tree already mirrored | `Mirrored` | the shell starts in `$ROOTFS/workspaces/<id>`, labelled as a copy |
| SAF tree, not mirrored | `Unavailable` | the shell starts in `$HOME` and the header explains that the project has no filesystem path |

A `content://` URI is never presented as a POSIX path. The mirror is a copy: commands run against
it, and nothing is written back to the original tree implicitly, so a project cannot be silently
duplicated or corrupted.

## Environment

`TermuxEnvironment` sets `HOME`, `PREFIX`, `PATH`, `TMPDIR`, `PWD`, `TERM=xterm-256color`,
`LANG=en_US.UTF-8`, `COLORTERM=truecolor` and `SHELL`, plus a fixed allow-list of Android
variables (`ANDROID_ROOT`, `BOOTCLASSPATH`, `EXTERNAL_STORAGE`, `TZ`, …).

Nothing else is inherited. `TermuxEnvironment.looksSecret` rejects any name matching
`api_key|access_key|private_key|secret|token|password|credential|authorization|bearer|oauth|
refresh_token|cookie|client_secret|keystore|passphrase`, and that filter is applied both to the
Android pass-through and to anything a caller passes as an extra variable. OAuth tokens, model API
keys, Supabase keys, `.env` values and signing material are therefore not readable from the
terminal.

## Agent separation

The terminal is a **human** path. The Agent Tool System keeps its own authorisation:

- human input → `TermuxSessionManager` → pty;
- model tool calls → Tool Router (`COMMAND_EXECUTION`) → `RuntimeProcessExecutor`, registered in
  `WorkspaceModule` with `allowsArbitrary = false`.

The two share the Termux filesystem, never the permission decision. `ServiceKeys` no longer
publishes a terminal session manager at all.

## Testing

- `:termux-terminal-emulator` runs Termux's own emulator regression suite unmodified
  (`TerminalTest`, `ControlSequenceIntroducerTest`, `HistoryTest`, `ResizeTest`,
  `ScrollRegionTest`, `WcWidthTest`, …) — real evidence that escape sequences, scrolling,
  reflow and Unicode width behave as upstream.
- `:termux-runtime` unit tests cover the prefix policy, environment (including that credentials
  are refused), bootstrap catalogue digests and URLs, archive symlink/manifest/permission rules,
  login-shell resolution, workspace binding for every branch, and session manager lifecycle
  (reuse, restart, capacity, exit, terminate-all).
- `:ui` covers the terminal chrome state.
- `:workspace` keeps the one-shot executor tests, which no longer cover the human terminal.

Verified on a device only where it can be: see "Not verified here" below for the honest list.

## Known Android limitations

- **Foreground service.** A development server survives backgrounding only while the notification
  is up. Android 12+ can still kill excessive-CPU child processes; `dontkillmyapp.com` guidance
  applies exactly as it does to Termux itself.
- **Primary user only.** Termux's layout assumes the primary user's data directory; secondary
  profiles and work profiles have a different storage root.
- **`/data/data` is not writable by apps**, so the official prefix can only be reached by owning
  the `com.termux` package name, not by symlinking to it.
- **External storage.** A project on shared storage is usable only if the app holds real
  filesystem access to it; SAF trees go through the mirror path.
- **No root features.** Nothing here needs root, Magisk, an external Termux install, Docker or a
  remote host, and nothing here assumes unrestricted filesystem access.

## Testing the aarch64 build on a real phone

The emulator can only run the x86_64 bootstrap, so arm64 needs a phone. These are the exact steps,
and the exact output to expect.

**1. Install both apps.** Install the AgentX APK and, if it is not already there, the official
Termux app from its own release page. They have different package ids, so neither replaces the
other:

```
adb install -r app-arm64-v8a-release.apk
adb shell pm list packages | grep -E "com\.agentx\.app|com\.termux"
# expected: two lines
#   package:com.agentx.app
#   package:com.termux
```

**2. Install the bootstrap.** Open AgentX → Terminal tab. Before installing, the header says
Termux packages are unavailable and the shell is Android's `/system/bin/sh`. Tap **Install**. The
banner walks through downloading → verifying → extracting → installing. On success the shell
restarts by itself and the header stops saying the packages are unavailable.

**3. Check the environment.** In that terminal, run:

```
printf 'hello\n'
pwd
printf '%s\n' "$PREFIX"
command -v sh
command -v pkg
command -v apt
pkg --version || true
```

Expected:

```
hello
/data/data/com.agentx.app/files/home
/data/data/com.agentx.app/files/usr
/data/data/com.agentx.app/files/usr/bin/sh
/data/data/com.agentx.app/files/usr/bin/pkg
/data/data/com.agentx.app/files/usr/bin/apt
Termux-PKG/2.0 … (a version banner; `pkg --version` is allowed to fail)
```

`$PREFIX` must be `/data/data/com.agentx.app/files/usr`. If it prints anything containing
`com.termux`, the archive is the official one and must not be used.

**4. Check the interesting cases.**

```
ls /data/data/com.agentx.app/files/usr/etc/termux/agentx-bootstrap.ok   # the install marker
pkg install git                                                          # expected to fail
```

`pkg install` is expected to say `Unable to locate package`: apt is deliberately pointed at no
repository, because the official one ships packages built for a prefix this app cannot use. That
is not a bug and must not be "fixed" by adding the official URL.

Then: type into the keyboard (`echo typed-by-keyboard`) and confirm it appears in the transcript;
rotate the device; open a `content://` folder as the workspace and confirm the terminal reports
that it is running in a copy; press Ctrl-D to exit the shell and confirm the exit strip appears
with a readable status and a **Restart terminal** action that works.

**5. If it fails, collect this.** The stage named in the banner is the single most useful fact —
`download`, `checksum`, `extraction`, `symlink`, `permissions`, `prefix`, `runtime` — together
with the message beside it. Then:

```
adb logcat -d | grep -iE "agentx|termux|Termux" > agentx-logcat.txt
adb shell run-as com.agentx.app ls -l /data/data/com.agentx.app/files/usr/bin | head -20
adb shell run-as com.agentx.app cat /data/data/com.agentx.app/files/usr/etc/termux/agentx-bootstrap.ok
adb shell "run-as com.agentx.app /data/data/com.agentx.app/files/usr/bin/sh -c 'printf ok'"
```

The marker file records the ABI, prefix and source revision the archive was built from, so it is
worth pasting in full. A `Permission denied` from `login` or `bash` means the extracted files are
not executable; a shell that exits with 1 within seconds means the prefix is incomplete; a shell
that exits with 9 was killed, usually by memory pressure.

Report the stage, the marker file, and `agentx-logcat.txt`. Until a phone run is reported, every
arm64 item in the checklist below stays **unverified** — the emulator does not test it.

## Acceptance checklist

Each item is marked with what actually backs it. **Unverified means unverified** — the arm64
archive is verified, but the runtime has not yet been executed on a real device. The emulator job
in
[`.github/workflows/termux-smoke.yml`](../.github/workflows/termux-smoke.yml) stops at its
catalogue gate.

| # | Item | Status | Evidence |
| --- | --- | --- | --- |
| 1 | APK package is `com.agentx.app` | **unverified at runtime** | Enforced in CI by `aapt dump badging` on every push; not yet observed on a device. |
| 2 | Official Termux coexists | **unverified** | Different ids, prefixes and data dirs, and the smoke job asserts both packages install; never executed. |
| 3 | APK installs without conflict | **unverified** | Same as above. |
| 4 | Custom shell starts | **unverified** | Arm64 bootstrap is built and catalogued; `TermuxShellResolver` is unit tested; no phone shell has run. |
| 5 | No `Permission denied` from `login`/`bash` | **unverified** | The installer chmods `bin/`, `libexec`, `lib/apt/*`; never observed on a device. |
| 6 | Keyboard input reaches the shell | **unverified** | Wired and unit tested (`canType`, `send`); the `adb shell input text` assertion has never run. |
| 7 | `pwd`, `ls`, `echo`, `command -v pkg`, `command -v apt` work | **unverified** | The verifier asserts these binaries are present in the published arm64 archive; no phone run has confirmed them. |
| 8 | Install button restarts the shell | **unverified** | `TerminalViewModel.provision()` restarts on `Ready`; the transition is unit tested, the restart is not. |
| 9 | Terminal does not exit immediately with code 1 or 9 | **unverified** | Depends on a real prefix. |
| 10 | Unreadable workspace does not kill the terminal | **unit tested** | Falls back to `$HOME` and keeps a usable shell; the process-exit path is unchanged by a workspace error. |
| 11 | APK signature valid | **unverified at runtime** | `apksigner verify` runs in CI on every push. |
| 12 | APK zip alignment valid | **unverified at runtime** | `zipalign -c -v 4` runs in CI on every push. |
| 13 | No ELF/script references `/data/data/com.termux` | **verified for archives only** | `verify_bootstrap.py` enforces zero occurrences; it has only ever been run against upstream's archive, which it correctly rejects. |

**Do not describe this project as providing full Termux support.** Items 4, 5, 7 and 9 are the
ones that decide it, and all four need a real bootstrap on a real device.

## Not verified here

This change was verified by compilation and by the unit/regression suites in CI. It could **not**
be verified interactively in this environment (no Android device, and this repository's rule is
that APKs are built only by GitHub Actions), so the following remain unproven until run on a
device:

- the JNI pty actually allocating a pty on-device (it is Termux's own `termux.c`, so the risk is
  in the packaging, not the code);
- the bootstrap download, checksum verification and extraction completing against the live
  release URL;
- `pkg install git` and a real `npm install` / `npm run dev` cycle, Ctrl+C, and backgrounding;
- the SAF mirror path against a real `content://` tree.
