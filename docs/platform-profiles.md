# Platform Profiles

This document describes the platform layer of the UI-quality architecture: the
conventions an agent follows because of *the platform it is building for*.

Three layers feed a UI prompt, and none of them replaces another:

| Layer | Answers | Owned by |
|---|---|---|
| `anti-slop-design` skill | what must this **not** become, in every project | AgentX, versioned with the app |
| Project design direction (`DESIGN.md`) | what should **this** product look like | the project |
| **Platform profile** | how is this **platform** normally built and used | AgentX, versioned with the app |

A platform profile is deliberately *not* a style guide. It never says "use
Material 3", "always use a hamburger menu" or "never use cards". It says how to
build well *on that platform*, and defers to the project's own design system
wherever one exists.

---

## 1. Supported profiles

| Id | Platform | Scope |
|---|---|---|
| `WEB` | Web | semantic structure, responsive behaviour, keyboard and focus, pointer vs touch, forms, states, navigation, motion preferences |
| `ANDROID_COMPOSE` | Android (Compose) | state-driven screens, adaptive layouts, insets, theme, accessibility semantics, content variability |
| `ANDROID_XML` | Android (XML/Views) | view hierarchy, content descriptions, touch targets, configuration changes, resources, scrolling, input |
| `DESKTOP` | Desktop | resizable windows, keyboard-first interaction, density, menus/toolbars/dialogs, scrolling, states |

The set is intentionally coarse: a profile describes a *platform*, not a
framework. React, Next.js, Flutter and SwiftUI are all covered by Web or their
native platform, so no framework profile is needed.

## 2. How a profile is selected

Selection is **deterministic** and **offline**. No model call, no AI classifier,
no content classification — only paths, directory listings and a bounded read of a
few build files.

```text
Project root ──► ProjectPlatformProbe ──► PlatformEvidence ──► PlatformDetector ──► PlatformProfileId?
                  (bounded, non-recursive)   (pure data)        (pure function)
```

**Signals, by precedence:**

1. **Android** — an `AndroidManifest.xml` (root, `app/src/main`, or `src/main`),
   or a Gradle file naming the `com.android.` plugin.
   - `ANDROID_COMPOSE` when a Gradle file names Compose.
   - `ANDROID_XML` when XML layout resources exist and Compose does not.
   - Otherwise **no profile**: a manifest alone does not say how the UI is built.
2. **Desktop** — `tauri.conf.json`, an Electron builder config, a `package.json`
   naming Electron or Tauri, `Cargo.toml` naming Tauri/egui/eframe, or a
   `.csproj`/`.sln`. Checked before Web, because desktop shells ship HTML.
3. **Web** — UI source files (`.html`, `.htm`, `.css`, `.jsx`, `.tsx`, `.vue`,
   `.svelte`). A `package.json` **alone is not** a web-UI signal, so a Node
   library or a backend package collects no web guidance.

Android outranks Web because an Android app legitimately bundles web assets, and
a manifest is definitive. Android **plus** Desktop is treated as ambiguous: those
are two independent, equally strong claims.

**Ambiguity is refused, not guessed.** If the evidence is missing or
contradictory, `platform` is null and no block is injected. A wrong profile is
actively misleading, which is worse than none. The refusal is reported as
`NOT_DETECTED` or `AMBIGUOUS` and logged with the signals that led there.

## 3. What the probe is allowed to do

Platform profiles are chosen from the project, so the probe is deliberately
small and non-recursive:

| Bound | Value |
|---|---|
| Directories listed | a fixed list of 7 conventional locations (`ContextSource`-independent) |
| Children taken per directory | 200 |
| Marker files read for content | 6 |
| Characters kept per marker file | 1,200 |
| Editor-known paths carried as signals | 40 |

Nothing read this way reaches a prompt. Marker contents only choose between
profiles AgentX ships.

## 4. Trust boundary

- **Profiles are AgentX-controlled data.** The guidance text is compiled in
  (`PlatformProfiles`). A project cannot define, extend or override a profile — a
  project file named `PLATFORM.md` is just a file, and its contents never appear in
  a prompt.
- **Nothing is executed.** Project files are read as text for marker tokens only;
  no profile content is parsed, interpreted or run.
- **No network, no new dependency.** Profiles ship with the app.
- **The project's own direction wins.** The block states, in its own text, that the
  project's design direction and the rules above take precedence, and that where
  the project already has a deliberate design system, that system is followed.

## 5. Context budget

A profile is injected into the system instruction on every UI turn, so it is
bounded like the other prompt-side layers, inside the shared `ContextBudget`:

| Limit | Value | Constant |
|---|---|---|
| Characters kept from one profile block | 2,000 | `ContextBudget.DEFAULT_MAX_PLATFORM_CHARS` |
| Whole-request ceiling | unchanged | `ContextBudget.charLimit` |
| Small-model floor | 800 | `ModelContextBudget.PLATFORM_FLOOR_CHARS` |

Outcomes, all explicit and consistent with the design-direction layer:

| Situation | Status | Effect |
|---|---|---|
| Role does not create or judge UI | `NOT_APPLICABLE` | not even probed |
| No platform signal | `NOT_DETECTED` | no block |
| Contradictory signals | `AMBIGUOUS` | no block |
| Project could not be inspected | `UNREADABLE` | no block |
| Did not fit the request | `EXCLUDED_DUE_TO_BUDGET` | no block |
| Fits | `INCLUDED` | full block |
| Larger than its ceiling | `TRUNCATED` | shortened, with a marker saying how much was dropped |

Logging is content-free: status, profile id, characters and the signal file names.
No marker-file content and no profile prose is logged.

## 6. Which roles receive a profile

`MAIN`, `PLANNER`, `CODER`, `FAST_CODER`, `REVIEWER` — the UI roles, defined once
in `UiRoles.ALL` and shared with the design-direction layer.

`EXPLORER`, `RESEARCHER`, `DEBUGGER`, `TESTER`, `SECURITY_REVIEWER`, `DOCS` and
`COMMIT_PR` receive nothing and cause no probe at all.

## 7. Prompt order

```text
# Skills                     ← universal constraints (anti-slop-design)
# Platform Profile: <name>   ← how this platform is normally built
# Project Design Context      ← what this particular product should look like
```

Read in order of increasing specificity: general rules first, then the platform,
then the project's own direction, which refines both. A profile is injected at
most once per run.

## 8. Adding a platform

1. Add an entry to `PlatformProfileId`.
2. Add its `PlatformProfile` and register it in `PlatformProfiles.ALL`.
3. Add the detection signal in `PlatformDetector`.

Nothing in Agent Core changes: the loop receives a resolved block and does not know
which platforms exist.

## 9. Related code

| Concern | Location |
|---|---|
| Profile ids, content, registry | `context/.../PlatformProfile.kt` |
| Evidence, pure detector, bounded probe | `context/.../PlatformDetector.kt` |
| Resolver | `ProjectPlatformProfileResolver` (`PlatformProfile.kt`) |
| Budget | `ContextBudget.maxPlatformChars`, `ModelContextBudget` |
| Registration | `ContextModule` → `ServiceKeys.CONTEXT_PLATFORM` |
| Consumption | `AgentModule.assemble(platformProfile = …)` → `AgentLoop` |

UI-task routing now exists as the layer above this one (see `ui-task-planning.md`),
which decides when a UI task is planned and which roles receive this profile.
**Not implemented, and out of scope for this layer:** a browser or rendering
capability, screenshots, visual inspection, a visual reviewer, a visual quality
gate, and computed contrast verification.
