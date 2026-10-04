# Model Manager, Presets and the Colab Model Runner

This document describes how the IDE saves model configurations and how a saved
model is started, connected and used by the agent.

It replaces nothing: the Model Gateway described in `AGENTS.md` is still the only
path model traffic takes. This layer decides **what** endpoint the gateway talks
to, and keeps that decision honest.

---

## 1. Architecture

```text
                         Model Manager
                              │
                    ┌─────────┴─────────┐
                    │                   │
               Model Presets       Model Runner
                                        │
                                  ┌─────┴─────┐
                                  │           │
                              ColabRunner   Future
                                  │        (HostedEndpointRunner, …)
                              Tunnel
                                  │
                           Model Endpoint
                                  │
                            Model Gateway
                                  │
                               Agent
```

Layering rules the code follows:

- **UI → ViewModel → ModelManager → ModelRunner → Model Gateway.** No Compose
  screen contains lifecycle logic; no runner knows about the gateway.
- The **Model Gateway never learns where a model runs.** It receives a
  `ModelConfig` (provider id, base URL, model, optional credential) and nothing
  else. Colab, the phone, or a remote server are indistinguishable to it.
- **Agent Core is untouched.** Per `AGENTS.md` §5.2 the change is confined to the
  model layer, the app composition root and the UI. `agent/` has no reference to
  presets, runners, tunnels or Colab.

### Module map

| Piece | Module / package |
| --- | --- |
| Presets, repository, store ports, codec | `model` → `dev.forge.ide.model.preset` |
| Lifecycle, runners, discovery, tunnel, health | `model` → `dev.forge.ide.model.runtime` |
| Manager, gateway connection, DI module | `model` → `dev.forge.ide.model.manager` |
| Android persistence (SharedPreferences, Keystore) | `model-android` |
| Models / Add-Edit / Model Runner screens | `ui` → `dev.forge.ide.ui.ide` |
| WebView host, composition root | `app` |

---

## 2. Concepts

### Model Preset

A saved, reproducible description of one model connection
(`ModelPreset`): id, display name, **provider type**, **API protocol**, model
identifier, API base path, optional credential *reference*, startup
script/configuration, server port, endpoint discovery, tunnel configuration,
health-check configuration, enabled flag, created/updated timestamps.

Nothing about a specific model is assumed. `Qwen`, `Llama` and `DeepSeek` are
ordinary user-created presets; there is no list of known model names anywhere in
the source.

Two orthogonal ideas are kept apart on purpose:

- **Provider type** (`ModelProviderType`) — *where* the model runs:
  `LOCAL_PHONE`, `GOOGLE_COLAB`, `REMOTE_OPENAI_COMPATIBLE`, `CUSTOM`. It selects
  the runner and whether plain HTTP is acceptable (only on-device).
- **API protocol** (`ModelApiProtocol`) — *how* the endpoint is spoken to:
  `OPENAI_COMPATIBLE`, `OLLAMA`. It selects the provider id, chat path and the
  default model-list path used for health checks.

### Model Manager

`ModelManager` owns presets and connections:

- create / update / delete / list presets, select the active model
- start, stop, reconnect, health-check
- track status per preset and publish the **active `ModelConfig`** for the agent
- own the background/foreground policy for connection monitoring

State is exposed as a single `StateFlow<ModelManagerState>` (presets, per-preset
status, active preset, active config, runner-session bookkeeping).

### Model Runner

`ModelRunner` is the generic port: `start`, `stop`, `reconnect`, `healthCheck`,
`status`/`statuses`, plus `forget` and `markStale`.

`AbstractModelRunner` implements the whole lifecycle once — bounded retries with
exponential backoff, overall operation timeout, health gating, cancellation,
"never restart a healthy runtime" — and subclasses supply only what is genuinely
runtime-specific:

| Runner | Provider types | What it adds |
| --- | --- | --- |
| `ColabRunner` | `GOOGLE_COLAB` | Colab wording; the runtime is started by the user in the notebook |
| `HostedEndpointRunner` | `LOCAL_PHONE`, `REMOTE_OPENAI_COMPATIBLE`, `CUSTOM` | Nothing to start; discovery + health + connect |

Adding a fourth runner does not require any change to the manager, the gateway
or the agent. Runners serve **Local / Custom** connections only; an API provider is
connected from its saved configuration instead — see the next section.

### Connection lifecycles (API vs Local / Custom)

There are two kinds of model connection, and they follow two different lifecycles.
`ModelConnectionKind` decides which one a preset gets, from its persisted
`setupKind`: a provider the catalogue knows (`gemini`, `groq`, …) is **API**; anything
else, including the generic `custom` kind, is **Local / Custom**.

`ModelConnectionManager` owns the lifecycle *rules*, one implementation per kind:

| | `ApiModelConnectionManager` | `LocalModelConnectionManager` |
| --- | --- | --- |
| The connection *is* | the saved configuration | the live endpoint |
| `connectsFromConfiguration` | `true` | `false` |
| `recoversLapsedRuntime` | `false` | `true` |
| Endpoint comes from | the preset's configured endpoint | what the runtime published |
| Model API unreachable → | health: `DEGRADED`, connection kept | `DISCONNECTED`, connection released |

What is deliberately **not** split: `ModelConnectionRegistry`,
`ModelCredentialResolver` / `ModelSecretStore`, `ModelConfig`, the capability
descriptor and `ModelGateway`. Both kinds produce an ordinary `ModelConfig` and are
routed identically; only the rules for when a connection may exist and what losing
reachability means differ.

For an API provider this means the connection, its credential, its model selection and
its gateway registration survive a failed probe, a local endpoint going away, an app
restart, and a background/foreground cycle. Editing it re-points the connection at the
new configuration; only an explicit disconnect or delete releases it. Its runtime
health is tracked by the request-time health layer (`model` →
`com.agentx.app.model.health`), not by a polling loop.

The full audit that motivated this split — the exact coupling points, and the rules
each kind owns — is in
[api-local-connection-separation.md](api-local-connection-separation.md).

### Tunnel discovery

`TunnelProvider` describes a tunnel kind and detects its endpoint:

| Provider | Type | Behaviour |
| --- | --- | --- |
| `CloudflareQuickTunnelProvider` | `CLOUDFLARE_QUICK` | Recognises `https://<slug>.trycloudflare.com` in the runtime output |
| `ManualTunnelProvider` | `MANUAL` | Uses the URL the user configured |
| `NoTunnelProvider` | `NONE` | Reports that no tunnel is configured |

`createsTunnels` is `false` for every provider, and that is a statement of fact:
`cloudflared` runs **inside** the Colab runtime, so an Android app cannot create
or keep that tunnel. Only the preset's configured provider is consulted, so a
stray URL printed by the runtime is never silently trusted.

### Endpoint discovery

`ModelEndpointDiscovery` turns a preset plus captured runtime output into a
validated `ModelEndpoint`:

| Mode | Source |
| --- | --- |
| `CONFIGURED_ENDPOINT` | The URL the user typed |
| `RUNTIME_OUTPUT` | The tunnel provider that recognises the runtime's output |
| `DEVICE_LOCAL_PORT` | `http://127.0.0.1:<port>` for an on-device server |

Validation happens before anything is contacted: non-blank, syntactically a URL,
`http`/`https` only, a real host, **no embedded credentials**, and TLS for every
remote provider. A failure is reported either as a configuration problem
(`invalidEndpoint = true`) or as "the runtime has not published anything yet",
which is what lets the UI distinguish "fix your settings" from "start your
notebook".

### Health checking

`HttpModelHealthChecker` verifies **the model API**, not merely that a web server
answered:

1. `GET` the protocol's model-list endpoint (`<base>/v1/models` for
   OpenAI-compatible, `<root>/api/tags` for Ollama, or the user's custom path).
2. Any non-2xx answer is unhealthy (401/403 is reported as a credential problem).
3. The body must parse as that protocol's model list. A 200 HTML page — a proxy
   error page, a captive portal — is therefore **not** mistaken for a live model.
4. With `requireModelInList`, a reachable endpoint that does not offer the
   configured model is reported as `DEGRADED`, not `ONLINE`.
5. A custom health path defines its own contract, so any 2xx counts there.

The credential is sent only as an `Authorization` header and is never logged.

### Lifecycle states

`NOT_CONFIGURED`, `UNKNOWN`, `STOPPED`, `STARTING`, `CONNECTING`, `CHECKING`,
`ONLINE`, `DEGRADED`, `DISCONNECTED`, `STOPPING`, `FAILED`.

- `STARTING`/`CONNECTING`/`CHECKING`/`STOPPING` are transient and bounded by
  `ModelConnectionPolicy`: attempts are finite, delays grow, and one overall
  timeout caps the operation, so the UI cannot spin forever.
- A failure carries a `ModelRuntimeFailure` reason (`RUNTIME_NOT_DETECTED`,
  `ENDPOINT_INVALID`, `MODEL_API_UNREACHABLE`, `INVALID_PRESET`, `TIMEOUT`,
  `CANCELLED`, `DISABLED`, `NO_RUNNER`) and the UI renders copy for it.
- `DEGRADED` means "usable, but something is off": for a runtime-backed connection
  the endpoint answered but is incomplete; for an API provider it is the state
  reported while the provider cannot be reached, because the saved configuration —
  not reachability — is what makes that connection exist.

### Reconnection

Everything in this subsection is the **Local / Custom** lifecycle. An API provider is
never monitored or reconnected: it is re-registered from its saved configuration, and a
failed probe is reported as `DEGRADED` without touching the connection.

- Monitoring runs only while the app is in the foreground, at the preset's
  interval. Android cannot promise background network continuity, so the model is
  never claimed to be online when it has not been re-checked: on background the
  status becomes `CHECKING` ("not re-checked yet") and it is verified again when
  the app returns.
- When a health check fails, the manager runs **one** bounded reconnect sequence
  (discovery → validation → health check, with backoff). If it fails, the state
  is `FAILED` with `awaitingRuntime` and the user is asked to act. There is no
  endless polling loop.
- The user can always press **Reconnect** manually.

---

## 3. Flows

### First connect

```text
Select a model
  → preset loaded (validated first)
  → STARTING
  → endpoint discovered (runtime output / configured / device local)
  → CONNECTING (validation)
  → health check against the model API
  → ONLINE  → Model Gateway connection registered
  → the agent can use it
```

### Already running

```text
Select a model that is ONLINE and healthy
  → the known endpoint is re-checked
  → never restarted, never rediscovered
  → Model Gateway re-pointed at it
```

### Restart of the app

An API provider:

```text
Load saved presets
  → restore the selected provider from its saved configuration
  → Model Gateway connection registered unconditionally
  → one health probe, reported as health (ONLINE / DEGRADED)
```

A Local / Custom connection:

```text
Load saved presets
  → restore the selected model
  → one reachability probe (a health check, never a workload launch)
  → ONLINE or DISCONNECTED
```

Nothing is started automatically: an endpoint is only ever *contacted*.
Connecting a model is always an explicit user action. A restore never releases an API
provider's connection, whatever the probe finds.

### Switching models

`Qwen → Llama → DeepSeek` replaces the provider registration and the active
`ModelConfig` in the Model Gateway. The Agent Core is not involved and does not
change; it asked for `providerId` + `baseUrl` + `model` — and gets new ones.

---

## 4. Persistence and security

- Presets live in app-private `SharedPreferences` (`forge.models`), the same
  mechanism the workspace runtime already uses. No second database.
- Serialization uses the JSON codec already shipped in the model layer. Unknown
  fields are ignored and unreadable entries are dropped, never guessed at.
- Presets survive app restarts, navigation and process recreation. Deleting a
  preset clears its credential and its runtime state.
- **Secrets are never stored in a preset.** A preset holds only a
  `credentialRef`; the value lives in `KeystoreModelSecretStore`, which encrypts
  it with an AES-256-GCM key held in the Android Keystore and writes only
  ciphertext to app-private storage. If the Keystore is unavailable the
  credential is kept in memory for the session and the UI says so
  (`credentialsPersistent = false`).
- **Endpoints are not persisted** as last-known state. A Quick Tunnel URL is a
  bearer capability, so only the state *name* is remembered for display.
- Credentials and endpoint URLs are excluded from every log statement; the log
  records `hasCredential=true` instead.
- The Model Runner browser refuses navigation outside the notebook host and
  Google's own hosts, and reports the refusal in the UI.

---

## 5. UI

| Screen | Route | Purpose |
| --- | --- | --- |
| Models | Settings → Models | List presets, add/edit/delete, Use/Start/Stop/Reconnect/Check, open the Model Runner |
| Add/Edit Model | `models/editor/{id\|new}` | Name, provider, model identifier, API protocol/base path, server port, endpoint discovery, tunnel, notebook URL, startup script, health path, API key, enabled |
| Model Runner | `models/runner/{presetId}` | Connection state, the notebook WebView, runtime output capture |

The Model Runner screen is a **focused runtime browser**, not a general browser:
it opens the configured notebook so the user can start and watch the runtime,
keeps its session while the process lives (page state is saved across
configuration changes), and shows the connection state. Closing it never stops
the model, because the agent does not go through it.

---

## 6. Limitations (stated, not hidden)

These are properties of Android and Google Colab, not of the implementation:

- **Android cannot start, restart or keep a Colab runtime alive.** The runtime
  belongs to Google and is driven by the notebook. The Model Runner browser is how
  the user starts it; the runner then detects the endpoint it publishes. There is
  no "start Colab" button because there is no honest way to implement one.
- **Android cannot create the tunnel.** `cloudflared` runs inside the Colab
  runtime. The app detects and validates the endpoint; it does not invent one.
- **Free Colab runtimes are short-lived and can be reclaimed.** Idle timeouts and
  usage limits will end the runtime. When that happens the model is reported as
  `FAILED / Model runtime stopped` with a clear next step, never as online.
- **An embedded WebView may not be accepted by Colab.** Notebook cell output is
  rendered in the page rather than the JavaScript console, so the console bridge
  is best-effort; the dependable paths are pasting the runtime output into the
  Model Runner screen or configuring the endpoint directly. If the embedded
  browser cannot host Colab at all, the screen offers "Open in browser" and says
  why instead of appearing broken.
- **Background monitoring is foreground-scoped.** No foreground service is
  introduced. On background the state becomes "not re-checked yet" and is
  verified when the app returns.
- **`LOCAL_PHONE` has no runner-specific logic beyond discovery and health.** A
  phone-hosted model server still has to be started by whatever app provides it.

Nothing in this feature fakes automation: there is no fabricated runtime, no
generated tunnel URL, no simulated model response, and no `ONLINE` state that did
not come from a successful health check.

---

## 7. Tests

Unit tests run on the JVM and need no Google account, Colab runtime, Cloudflare
account, API key or network. Fakes replace the runner, the tunnel provider and
the gateway where appropriate.

`./gradlew test` runs every platform-independent module's unit tests; `:model` holds
the bulk of them (412).

| Area | Test class | Covers |
| --- | --- | --- |
| API / Local separation | `ApiLocalConnectionSeparationTest` | the two kinds and their rules; a local disconnect, a local endpoint failure, a failed local reconnect or an API health failure never resets the other kind's configuration or credential; both kinds survive a reload; API is never polled or reconnected; the gateway still receives an ordinary descriptor for both |
| Presets | `ModelPresetPersistenceTest` | create, validate, survive a restart, update, delete, selection, last status, JSON round trip, tolerant decoding, no secrets serialized |
| Lifecycle | `ModelRunnerLifecycleTest` | STARTING→CONNECTING→ONLINE, bounded attempts, growing backoff, unhealthy API, degraded, healthy model not restarted, bounded reconnect, recovery, stop semantics, invalid preset, disabled preset, cancellation, timeout, health without endpoint |
| Discovery | `EndpointDiscoveryTest` | tunnel URL detected, marker and plain output, newest wins, foreign URL refused, no output, configured endpoint, http refused for remote, malformed URLs, embedded credentials refused, device-local port, manual tunnel, no fake tunnel creation |
| Health | `ModelHealthCheckerTest` | model list parsed, credential header only, HTML page rejected, JSON without model list rejected, HTTP errors, unreachable, model missing → degraded, relaxed check, empty list → degraded, Ollama path, custom path |
| Manager | `ModelManagerTest` | preset CRUD, credential storage/removal, gateway binding, switching, no restart when online, failed start, stop, health loss + reconnect, background/foreground, no credential or endpoint in logs, browser session cannot affect the connection, no-runner honesty |
| Gateway | `ActiveModelGatewayTest` | an agent-shaped caller reaches the active model through the gateway alone; switching models changes the endpoint it uses; the protocol decides the provider id |
