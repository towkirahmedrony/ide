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
   download the official `bootstrap-<arch>.zip` for the device's ABI → verify SHA-256 against the
   digest pinned in `TermuxBootstrapCatalog` (the same digests termux-app embeds) → extract to
   `usr-staging` → apply `SYMLINKS.txt` → `chmod 0700` on `bin/`, `libexec`, `lib/apt/methods`,
   `lib/apt/apt-helper` → rename `usr-staging` to `usr`. The move is last, so an interrupted
   install never leaves a half-populated prefix that later code would mistake for a working one.
5. **Environment file.** `$PREFIX/etc/termux/termux.env` is written so a login shell sources the
   same `$PREFIX`/`$HOME` even for a child process that did not inherit our `envp`.

## How packages are provided

The bootstrap *is* the package system. It ships `apt`, `dpkg`, `bash`, `coreutils`, `openssl`,
`libandroid-support`, `termux-am` and the Termux apt configuration, pointed at the official
repositories. `pkg install git`, `pkg install nodejs`, `apt upgrade` and the rest therefore work
through the real ecosystem — there is no curated binary list anywhere in this repository, and no
package server of ours.

`git`, `nodejs`, `python`, `openssh`, `make` and `clang` are **not** in the base bootstrap; they
are ordinary packages, installed by the user with `pkg`. That is the same situation as a fresh
Termux install, and it is deliberate: hardcoding a tool list would freeze the environment.

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
says why, and gives two real options:

1. **Build with the official prefix.** `./gradlew assembleRelease -Pagentx.termux.officialPrefix=true`
   sets `applicationId = com.termux`, so the data directory *is* the one the artifacts expect and
   the published bootstrap and repositories apply verbatim. Off by default: it takes over Termux's
   package name and cannot coexist with a real Termux install.
2. **Rebuild the bootstrap for your own package name.** The officially supported route for a
   self-contained fork. See [termux-bootstrap.md](termux-bootstrap.md).

Until one of those is done, the Terminal tab still gives a genuine pty shell (`$PREFIX/bin/sh` or
`/system/bin/sh`), and the header says that Termux packages are unavailable and why.

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
