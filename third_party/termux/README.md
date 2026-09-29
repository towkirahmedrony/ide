# Vendored Termux components

This directory documents the Termux source that the IDE embeds so the terminal is a real
Termux terminal instead of a re-implementation.

## What is vendored, and why

| Vendored module | Upstream path | License | Needed for |
| --- | --- | --- | --- |
| `termux-terminal-emulator` | `termux-app/terminal-emulator` | Apache-2.0 | ANSI/ECMA-48 terminal emulation (`TerminalEmulator`), the session/PTY plumbing (`TerminalSession`) and the `libtermux.so` JNI that allocates a real pseudoterminal (`src/main/jni/termux.c`). |
| `termux-terminal-view` | `termux-app/terminal-view` | Apache-2.0 | `TerminalView`, the `View` that renders the emulator, handles hardware/soft keyboard input, text selection, copy/paste and pinch-zoom. |

## License position

`termux/termux-app` as a whole is **GPL-3.0-only**, but its own `LICENSE.md` carves out an
explicit exception:

> - [Terminal Emulator for Android](https://github.com/jackpal/Android-Terminal-Emulator) code is used
>   which is released under [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0) license. Check
>   [`terminal-view`](terminal-view) and [`terminal-emulator`](terminal-emulator) libraries.

Those two libraries are the ones that descend from Jack Palevich's `Android-Terminal-Emulator`,
and they are the only ones vendored here. The full Apache-2.0 text is kept at
`third_party/termux/licenses/LICENSE-Apache-2.0.txt` and as a `LICENSE` file inside each
vendored module.

**`termux-shared` is deliberately NOT vendored** — it is GPL-3.0-only and is not required,
because `TerminalSession` and `TerminalEmulator` do not depend on it. Everything this project
needs from `termux-shared` (environment assembly, bootstrap extraction, session bookkeeping) is
re-implemented in the project's own `:termux-runtime` module. See
`docs/termux-terminal.md` for the mapping.

## Provenance

- Upstream repository: <https://github.com/termux/termux-app>
- Revision copied: `8629e632fcb95da272221be327db653fb24befe9` (2026-09-26)
- Upstream paths copied:
  - `terminal-emulator/src/main/java/**`
  - `terminal-emulator/src/main/jni/**`
  - `terminal-emulator/src/main/AndroidManifest.xml`
  - `terminal-emulator/src/test/java/**`
  - `terminal-emulator/proguard-rules.pro`
  - `terminal-view/src/main/java/**`
  - `terminal-view/src/main/res/**`
  - `terminal-view/src/main/AndroidManifest.xml`
  - `terminal-view/proguard-rules.pro`

Package names (`com.termux.terminal`, `com.termux.view`) and the JNI symbol prefix
(`Java_com_termux_terminal_JNI_*`) are intentionally left untouched so the vendored code
stays a drop-in copy of upstream.

## Deviations from upstream

Only build configuration was changed; **no Java, C, resource or manifest file was edited**.
Vendored test sources are byte-identical to upstream, which is what lets the build run
Termux's own emulator regression suite.

| Deviation | Location | Reason |
| --- | --- | --- |
| `build.gradle` rewritten as `build.gradle.kts` and aligned with this project's AGP/Gradle/JDK | `*/build.gradle.kts` | Upstream scripts read `project.properties`, apply `maven-publish`, and target Java 8; none of that belongs in an app module here. |
| `-Werror` dropped from the JNI `cFlags` | `termux-terminal-emulator/build.gradle.kts` | A benign warning from a different host clang must not break the IDE build. Every other upstream flag is preserved. |
| `minSdk` 26 instead of 21 | same | App-wide floor of this IDE. |
| `namespace` set explicitly | both modules | Upstream relies on `package` in the manifest, removed in modern AGP. |

## Keeping the copy honest

`third_party/termux/upstream-revision.txt` records the upstream commit. To re-sync, copy the
paths listed above from that revision and diff — the only expected differences are the four
rows in the table. No upstream copyright or license notice was removed; where upstream files
carried none, this attribution directory supplies it.
