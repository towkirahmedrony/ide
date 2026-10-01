# AgentX embedded developer runtime (Ubuntu ARM64)

The primary execution backend is a real Ubuntu ARM64 userland run through PRoot. The legacy
Termux bootstrap is kept intact as a fallback; nothing was deleted in this phase.

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

## Module

`:ubuntu-runtime` (`com.agentx.app.ubuntu`) is a new Android library that is deliberately
isolated from the legacy bootstrap. It depends on `:termux-runtime` **only** for the terminal
layer that is not bootstrap code: the vendored `TerminalSession`/PTY, `TermuxShellSpec` and the
terminal host/client ports. It never references `TermuxBootstrapCatalog`, `TermuxBootstrapInstaller`
or `TermuxPrefixPolicy`.

| File | Responsibility |
| --- | --- |
| `UbuntuRuntimeState.kt` | `AgentxRuntimeState` (NOT_INSTALLED … ERROR) and `RuntimeStatus`, the single source of truth for the UI. |
| `NativeRuntime.kt` | `NativeRuntimeLayout` (nativeLibraryDir + app-private runtime storage) and the native library probe. |
| `ProotCommand.kt` | The `proot -0 -l -r <rootfs> -b … -w … <cmd>` builder, bind mounts and `PROOT_LOADER`/`PROOT_L2S_DIR`/`PROOT_TMP_DIR`. |
| `UbuntuRootfsCatalog.kt` | The pinned Ubuntu Base arm64 entry (URL, SHA-256, size) and the required guest files. |
| `UbuntuRootfsInstaller.kt` | Download → SHA-256 → tar extraction → validation → apt/resolv configuration → activation → marker. |
| `UbuntuEnvironment.kt` | The guest `HOME`/`USER`/`PATH`/`TERM`/`TMPDIR`/`AGENTX_RUNTIME` environment, with credential filtering. |
| `UbuntuWorkspaceBinding.kt` | How a project is bind-mounted (or why it is not). |
| `AgentxExecution.kt` | The backend-agnostic `execute(command, workingDirectory)` seam for future agents. |
| `LocalUbuntuRuntime.kt` | The runtime: status flow, provisioning, terminal spec, process execution. |

## Rootfs

Not bundled. On first run the official Ubuntu Base 24.04.5 arm64 archive is downloaded (≈ 30 MB),
checked against the SHA-256 pinned in `UbuntuRootfsCatalog`, extracted **with the system `tar`**
(so Ubuntu's symlinks, hard links and modes survive — a hand-rolled reader cannot be trusted to),
validated for `bash`, `sh`, `dash`, `apt-get`, `dpkg`, `env` and `ls`, configured for PRoot, and
only then moved into place and marked installed. The extraction is roughly 110 MB on disk. A
checksum mismatch deletes the archive and aborts; there is no insecure skip.

`apt` is configured for `ports.ubuntu.com/ubuntu-ports` (`noble`, `-updates`, `-security`), with
`APT::Sandbox::User "root"` and `Install-Recommends "false"`.

## L2S

`PROOT_L2S_DIR` points at `<runtimeDir>/l2s`, outside the rootfs subtree, created before any
session and never replaced by an extraction. Without it, link-to-symlink emulation fails and
Ubuntu's `coreutils`, `ln` and `dpkg` break. The directory's existence and writability are the
first thing a session needs, and a missing one produces a clear runtime error rather than a
broken guest.

## Terminal

The existing vendored terminal emulator and PTY are reused unchanged. `LocalUbuntuRuntime.specFor`
returns an ordinary `TermuxShellSpec` whose executable is `libproot.so` and whose arguments are
the guest command; the session manager, foreground keep-alive service, key handling (Ctrl+C,
Ctrl+D, arrows, tab), resize and ANSI rendering are all the ones that already existed. There is
no second foreground service. The primary shell is `/bin/bash --login` inside the guest.

## First run

When the rootfs is missing the Terminal tab shows the **AgentX Developer Runtime** card —
"Ubuntu ARM64 · Required for Terminal, Git, Python, Node and local development · [Install
Runtime]" — followed by download, verification, extraction and setup progress. On success the
shell is replaced automatically; no bootstrap command is typed.

## Workspaces

A project with a real host path is bind-mounted to `/workspace/project`, so a file created in
the terminal is the same file the IDE's Files and editor see. A `content://` tree has no POSIX
path, so the shell runs in the guest home with a note; the project is not silently copied.

## Networking

PRoot and Android share the network namespace, so a guest `python3 -m http.server 8080` is
reachable from Android at `http://127.0.0.1:8080` with no proxy or tunnel. Guest DNS is provided
by a generated `resolv.conf`, built from the active network's DNS servers and bind-mounted as
`/etc/resolv.conf`.

## Legacy bootstrap

`:termux-runtime` and the Termux bootstrap stay in the tree and keep building. They are now the
fallback: if the developer runtime is absent or not installed, the Terminal tab uses the legacy
backend exactly as before. No bootstrap asset, catalog entry or release was regenerated or
changed. A cleanup phase can remove the legacy path once the developer runtime has passed a
real-device run.

## Verification status

**Not verified on a device.** This change was authored in an environment with no JDK, Android
SDK, NDK or ARM64 device, and this repository's rule is that Android artifacts are built only by
GitHub Actions. The Kotlin logic is unit tested in CI (`:ubuntu-runtime:test`), the APK is built
in CI, and the native binaries are cross-compiled and verified in CI. The on-device checklist
(runtime, apt, git, gh, python, node, local server, Ctrl+C/D, resize, background, workspace
round-trip) is still open and must not be described as working until it passes.
