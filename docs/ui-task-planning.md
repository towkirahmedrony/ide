# UI Task Classification and Planning

This document describes how AgentX decides that a request is UI/design work and
routes it through the `PLANNER` before implementation.

It is the routing and planning layer of the UI-quality architecture. It does
**not** add any visual capability: there is no browser, no rendering, no
screenshot, no visual reviewer and no visual quality gate. Those remain out of
scope and are listed at the end.

---

## 1. The flow

```text
User request
     │
     ▼
UiTaskClassifier            (deterministic, model-free, one call per delegation)
     │
     ├── NON_UI ────────────► existing routing (unchanged)
     ├── UI_SIMPLE ─────────► CODER / FAST_CODER directly
     ▼
UI_DESIGN / UI_COMPLEX
     │
     ▼
PLANNER (design plan)  ──►  CODER / FAST_CODER  ──►  REVIEWER  ──►  existing verification
     │                                ▲                    ▲
     └────────── plan forwarded ──────┴────────────────────┘
```

The classifier and the policy are pure functions. No model call is made to
classify a task, and no filesystem scan happens: classification reads only the
text of the current turn (the user prompt and objective).

## 2. Classification

`UiTaskClassifier` returns one of four `UiTaskClass` values:

| Class | Meaning | Planning |
|---|---|---|
| `NON_UI` | not UI/design work | existing behaviour, no planner |
| `UI_SIMPLE` | a small, localized UI change | none; go straight to the implementation role |
| `UI_DESIGN` | UI/design work worth a design plan | `PLANNER` required before implementation |
| `UI_COMPLEX` | large or cross-cutting UI work | `PLANNER` required before implementation |

It is deliberately **not a keyword blacklist**. It weighs two opposing evidence
sets and lets the whole request decide:

- **UI evidence** — UI nouns (screen, layout, dashboard, theme, …) and design
  intent (redesign, visual hierarchy, responsive, "more distinctive") or the
  creation of a substantial new surface (create/build a screen, page, dashboard,
  flow, navigation, layout).
- **Non-UI evidence** — data, server, tooling and infrastructure terms (sql,
  query, api, repository, gradle, parser, crash, …).

Rules, in order:

1. No UI noun and no design intent → `NON_UI`.
2. Only a stray intent word with no UI noun and some technical term
   ("redesign the API") → `NON_UI`.
3. No design intent, and a strong data/server/infrastructure term is present
   (sql, query, api, database, repository, gradle, parser, crash, …) → `NON_UI`,
   however many UI nouns the request names ("optimize the database query that
   backs the dashboard screen").
4. No design intent, and technical terms otherwise meet or exceed the UI nouns
   ("fix the SQL query used by the Settings screen repository") → `NON_UI`.
5. Heavy technical dominance even with design intent → `NON_UI`.
6. Design intent plus a breadth marker (entire, multiple, across, system, full)
   → `UI_COMPLEX`.
7. Design intent otherwise → `UI_DESIGN`.
8. UI nouns but no design intent → `UI_SIMPLE`.

Ambiguity is safe: anything the classifier cannot place confidently stays
`NON_UI` and keeps the existing routing.

## 3. Planning policy and routing

`UiPlanningPolicy` turns a `UiTaskClass` into routing, on top of the existing
delegation machinery (it never introduces a parallel orchestrator):

- `UI_SIMPLE` / `NON_UI` → no gate; the existing path is used unchanged.
- `UI_DESIGN` / `UI_COMPLEX` → a `CODER` / `FAST_CODER` delegation is **refused**
  until a usable plan exists. The refusal is reported to the `MAIN` Agent exactly
  like every other delegation rejection — as a structured tool result tagged
  `PLANNING_REQUIRED` — so the agent delegates to `PLANNER` and retries.

The gate is structural, not a suggestion. It executes inside
`AgentLoop.handleDelegate`, beside `DelegationPolicy.evaluate`.

## 4. How `PLANNER` differs from `CODER`

`PLANNER` is still a read-only role: it holds the same inspect and
code-intelligence tools it always did, never a write, shell or delegate tool. For
UI/design work its prompt asks for a concise, implementation-oriented design
plan rather than code:

- user purpose and audience when inferable
- information hierarchy
- major sections / components
- interaction model
- important states (loading, empty, error)
- responsive / adaptive behaviour
- platform considerations
- reuse of the existing project design system
- the distinctive design direction
- accessibility considerations
- implementation boundaries
- files/components likely to change

The planner inspects the existing project first, so it extends existing screens,
layouts, components, navigation and theme files instead of inventing a new design
system. Because `PLANNER` has no delegate tool and only `MAIN` may delegate, the
planner can never recurse into another planner.

## 5. How the design context reaches planning

Three existing layers reach the planner through the normal context pipeline —
none of them is copied into the planner prompt and none is duplicated:

| Layer | Answers | Delivered by |
|---|---|---|
| `anti-slop-design` skill | what a UI must **not** become, universally | the skill context resolver (assigned to `PLANNER`) |
| `DESIGN.md` | what **this** product should look like | `ProjectDesignContextResolver` → `# Project Design Context` |
| platform profile | how this **platform** is normally built | `ProjectPlatformProfileResolver` → `# Platform Profile` |

`PLANNER` is already one of the UI roles, so these layers were reaching it before
this phase; this phase only makes the planner responsible for turning them into a
task-specific plan. Missing design context or a missing platform profile simply
means no block, and planning continues.

## 6. Planner → implementation handoff

The planner's result is not discarded. When a `PLANNER` delegation completes, its
summary and findings are stored in the run's `DelegationState` as the design plan.
When the `MAIN` Agent then delegates to an implementation role (`CODER`,
`FAST_CODER`) or a review role (`REVIEWER`, `SECURITY_REVIEWER`) on a design task,
the plan is folded into that delegation's existing **scoped context** under
`# Design Plan (from Planner)`, added at most once and before any other scoped
note, so the existing `MAX_SCOPED_CONTEXT_CHARS` cap can never truncate it away.

The handoff therefore rides the context channel that already exists; no second
context store is created and no context budget is exceeded.

A planner result that failed, parked, or produced no text is not a plan: the
state's `hasUsablePlan()` stays false and the implementation gate stays closed. A
failed plan is never silently treated as a successful one.

## 7. Reviewer

`REVIEWER` and `SECURITY_REVIEWER` receive the same relevant context as before —
the anti-slop rules, the project's design direction, the platform profile and the
implementation diff — plus the planner's design plan through the handoff above.
The reviewer judges the change textually. It must **not** claim a rendered UI was
visually verified, because no rendering or screenshot capability exists.

## 8. Avoiding unnecessary planning

- `NON_UI` and `UI_SIMPLE` never invoke the planner.
- Planning happens **once per relevant task**: the gate opens as soon as a usable
  plan exists, and `DelegationPolicy` still forbids re-delegating a role that
  already completed the same task.
- The classifier is a pure function evaluated from the current turn's text only,
  so it adds no model request and no filesystem scan.
- `PLANNER` cannot delegate, so `PLANNER → PLANNER → … → CODER` cannot occur.

## 9. Related code

| Concern | Location |
|---|---|
| Classes, classifier, planning policy, phase contract | `agent/src/main/kotlin/com/agentx/app/agent/delegation/UiTask.kt` |
| Delegation accounting + `hasUsablePlan` / `withPlan` | `DelegationPolicy.kt` (`DelegationState`) |
| `PLANNING_REQUIRED` rejection | `DelegationPolicy.kt` (`DelegationRejection`) |
| The gate, the plan handoff and the UI prompt guidance | `AgentLoop.kt` (`handleDelegate`, `handoffContext`, `buildSystemPrompt`) |
| Planner responsibility | `DefaultAgentPrompts.PLANNER` |
| Tests | `UiTaskClassifierTest.kt`, `UiPlanningRoutingTest.kt` |

## 10. Not implemented (out of scope for this phase)

- browser or rendering capability
- screenshot capture or screenshot analysis
- visual inspection, visual diff or a vision model
- a visual reviewer role
- a visual quality score or quality gate
- computed contrast / accessibility verification
- automatic `DESIGN.md` generation
