# API model connections vs Local / Custom connections

This document is the audit behind the `ModelConnectionManager` split, and the
specification of the boundary it establishes. It exists because an API provider and a
user-run endpoint used to share one connection lifecycle, which made an API provider's
saved configuration disappear when something entirely unrelated failed.

Read [model-manager.md](model-manager.md) first for the layer this fits into.

---

## 1. What was actually coupled

The layers were already separated where it is easy to see: `ModelPreset` +
`ModelPresetRepository` for configuration, `ModelConnectionRegistry` for provider
registration, `ModelGateway` for execution, `ModelConnectService` for the connect
flow, and a UI that already asked for "Local" or "API" and showed different fields.

The coupling was **not** in those. It was that the *runtime availability lifecycle* was
the only connection lifecycle there was, and both kinds were handed to it:

- `DefaultModelManager.runnerFor(preset)` picks a `ModelRunner` from
  `preset.providerType`. That type does not distinguish the two kinds: a Gemini preset
  and a Groq preset are `REMOTE_OPENAI_COMPATIBLE`, exactly like a remote server the
  user runs themselves, and both are served by `HostedEndpointRunner`.
- `AbstractModelRunner` therefore implemented — and the manager therefore applied —
  bounded start retries, endpoint discovery, health gating, a periodic poll and a
  `reconnect()` loop to API providers as well.

From that came the concrete defects. Every one of them is a place where an
**unreachable probe released a connection**:

| Where | What it did |
| --- | --- |
| `DefaultModelManager.refresh()` tail | For the active preset: `healthCheck` → not reachable → `registry.disconnect(active.id)` + `stopMonitor()` + `activeConfig = null`. An API provider that was merely unreachable at start-up lost its connection. |
| `DefaultModelManager.onAppForeground()` | Same probe → `releaseConnection(preset)`. A provider that was rate limited or briefly offline while the app was backgrounded was disconnected on return. |
| `DefaultModelManager.checkModelHealth()` | `if (health.isReachable) bind(...) else releaseConnection(...)`. Verifying an API provider could disconnect it. |
| `DefaultModelManager.startMonitor()` | A periodic loop that on an unhealthy probe called `runner.reconnect(preset)` — a runtime recovery sequence — and released the connection if it failed. API providers were polled and "reconnected" like a Colab runtime. |
| `DefaultModelManager.applyConnection()` | A failed `start`/`reconnect` → `releaseConnection`. `selectModel()` reached an API provider through this path, so selecting a configured provider depended on a runtime answering at that moment. |
| `DefaultModelManager.updatePreset()` | Any edit to a connected preset → `registry.disconnect` + `markStale`. Editing an API provider's display name dropped its live connection and claimed its runtime had gone stale, although it has no runtime. |
| `DefaultModelManager.onAppBackground()` | Marked any usable connection "not re-checked yet", a runtime concept applied to a configuration-only connection. |
| `DefaultModelManager.onRunnerStatuses()` | Published the runner's status over the provider's status for every preset, so a runtime state could overwrite the state of a connection that has no runtime. |

The registry itself was already per-provider (`disconnect(presetId)` unregisters only
that preset's provider), so one provider never directly removed another. The damage came
from the *manager's reaction to health*, which was the same for all of them.

Two smaller consequences of the same coupling:

- `connectQuick()` ends with `manager.selectModel(...)` and treats a status that is not
  `isUsable` as a failed connect. For an API provider that meant a successful
  configuration could be reported as a failed connect because one probe lapsed.
- `ModelRuntimeFailure.MODEL_API_UNREACHABLE` / `DISCONNECTED` are runtime vocabulary.
  There was no state meaning "configured, provider currently unhealthy".

**Root cause, in one sentence:** the code that decides *when a connection may exist* and
*what losing reachability means* was shared, even though one kind's connection is its
saved configuration and the other's is a live endpoint.

---

## 2. The boundary

```
ModelConnectionKind  ──selects──▶  ModelConnectionManager
                                     ├── ApiModelConnectionManager
                                     └── LocalModelConnectionManager
```

- `ModelConnectionKind.kt` — `API` | `LOCAL_CUSTOM`, decided in one place from the
  preset's persisted `setupKind` through `KnownModelProviders`. A provider the catalogue
  does not know — including the generic `custom` kind — is `LOCAL_CUSTOM`, so a
  user-run endpoint is never claimed to be a persistent hosted provider.
- `ModelConnectionManager.kt` — the lifecycle rules, one implementation per kind. It is
  a *policy* port, not a second registry: it holds no connections, no credentials and no
  descriptors.
- `DefaultModelManager` keeps its role as the single `ModelManager` façade, so
  `UI → ViewModel → ModelManager` and the gateway are unchanged. It asks the owning
  `ModelConnectionManager` at exactly the seams listed in §1.

### Rules each kind owns

| Rule | API | Local / Custom |
| --- | --- | --- |
| `connectsFromConfiguration` | `true` — the saved configuration is enough | `false` — a runtime endpoint must be detected and answer |
| `recoversLapsedRuntime` | `false` — no polling, no reconnect loop | `true` — periodic re-check plus one bounded reconnect |
| `endpointFor(preset, runtimeEndpoint)` | the preset's configured endpoint | only the runtime-published endpoint |
| `unreachableState()` | `DEGRADED` (health) | `DISCONNECTED` (lost connection) |

Everything else follows from those four, applied at the seams in §1:

- **API** connections are registered from configuration on select/start/reconnect,
  on `refresh()`, on `onAppForeground()` and after an edit. A failed probe changes only
  the reported status (`DEGRADED`, with `MODEL_API_UNREACHABLE` as the reason and the
  provider's own message as the detail). Nothing releases the connection, its
  credential, its model selection or its gateway registration — only an explicit
  `stopModel()` (disconnect) or `deletePreset()` does.
- **Local / Custom** connections behave exactly as before, unchanged, in one object.
  Endpoint discovery, the reachability gate, the bounded retry with backoff, the
  health-gated reconnect and the disconnect on loss are all still theirs.
- `onRunnerStatuses()` accepts a runner's status only for a runtime-backed preset, so a
  runner's state can no longer be published over — or persisted as — an API provider's
  connection state.

### What is deliberately still shared

Not split, because splitting them would duplicate working systems for no reason:

- `ModelConnectionRegistry` — one registry, but keyed by **connection identity**
  (the persisted preset id), not by provider family. Two connections of one family
  stay independent; the gateway is routed per request from
  `ModelConfig.connectionId`.
- `ModelCredentialResolver` / `ModelSecretStore` — one credential system.
- `ModelConfig` — one normalized descriptor, produced identically by both kinds. It
  carries `providerId` (family) and `connectionId` (identity) separately.
- `ModelCapabilityRegistry` / declared capabilities.
- `ModelGateway`, `HttpTransport`, JSON, logging, `ModelHealthChecker`.
- `AbstractModelRunner` — still the whole lifecycle for the Local / Custom kind.

An API provider and a Local / Custom connection are therefore indistinguishable to the
gateway and to the agent: both are `providerId` + `baseUrl` + `model` + credential. Only
who owns their lifecycle differs.

---

## 3. Tests

`model/src/test/kotlin/com/agentx/app/model/manager/ApiLocalConnectionSeparationTest.kt`
drives the real `DefaultModelManager` and the real `GatewayModelConnectionRegistry`
(only the runner is scripted) and covers:

1. **Local disconnect isolation** — disconnecting a local model leaves the API
   connection, credential and model selection intact.
2. **Local endpoint failure isolation** — a local endpoint going unreachable (via
   `refresh()`) leaves the API connection intact.
3. **API configuration persistence** — a reload of app state and repository restores the
   API connection from configuration alone.
4. **Local persistence** — a reload keeps the local endpoint, protocol and model.
5. **No cross-type reset** — a failed local reconnect neither resets nor deletes API
   credentials or the API model selection.
6. **API health failure** — a failed API request leaves the saved configuration and the
   connection in place, reported as `DEGRADED`.

Plus the boundary itself (the kinds and their rules), the unchanged local behaviour
(a health failure still disconnects a local model), and that an API provider is never
polled or reconnected while a local connection over the same period is.

---

## 4. Out of scope (unchanged on purpose)

Quota and rate limiting, fallback, agent delegation, tool permissions, the capability
registry, the Gemini endpoint handling, Devstral eligibility and the authentication
architecture were not touched. The only files changed are the model manager's lifecycle
boundary, its manager, and tests/docs.
