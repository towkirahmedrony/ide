# Autonomous coding verification and self-correction

This document describes the verification stage of the AgentX coding workflow:

```
Inspect → Understand → Plan → Read → Modify → Diff → Verify → Fix → Re-verify → Commit → Push
```

## Design

The verification stage **extends** the existing agent loop. It does not add a
second loop, a second tool registry, a second Git system, a second workspace
system, or a second approval system. Every turn of the workflow is an ordinary
agent run through the same `AgentLoop`, the same `ToolRouter` (scope → permission
→ approval → executor) and the same Git/GitHub services.

Because the coding environment has no usable Android SDK, the coding agent never
attempts a local Gradle/APK build. The repository's existing GitHub Actions
workflow (`Build Android Release APK`) is the authoritative Android build
verification.

### Components

| Concern | Where |
| --- | --- |
| Neutral verification vocabulary (`VerificationStatus`, `VerificationCategory`, `VerificationOutcome`, CI models) and the read-only `CiVerificationService` port | `core/.../core/verification/Verification.kt` |
| GitHub Actions client + pure JSON parsing | `integrations/.../github/GitHubActionsService.kt` |
| Bounded verify → diagnose → fix → re-verify workflow | `agent/.../agent/verification/AutonomousVerificationWorkflow.kt` |
| CI runner (poll for the pushed commit's run, map to an outcome) | `agent/.../agent/verification/CiVerificationRunner.kt` |
| Read-only model-facing CI tool (`ci_verification`) | `tools/.../tools/verification/CiVerificationTool.kt` |
| Secret detection over changed content | `tools/.../tools/verification/SecretScan.kt` |
| Pre-commit / pre-push guard rails | `tools/.../tools/verification/CommitSafetyGuard.kt` |
| Safe high-level activity events | `agent/.../agent/domain/AgentActivity.kt`, `AgentEvent.ActivityChanged` |

### Verification states

A verification reports exactly one of `PASSED`, `FAILED`, `UNAVAILABLE`, or
`CANCELLED`. A failure carries only safe information — the check name, a coarse
result status, a redacted error excerpt, a location and a structured category —
and never a secret.

### Retry limits

`VerificationPolicy.maxCorrectionCycles` bounds the number of fix cycles after
the first failed verification (default `2`). When it is reached the workflow stops
modifying code, preserves the current workspace state, reports the failure and
does not keep pushing speculative fixes. The limit is never raised silently.

### Cancellation

Cancellation stops active tool execution, verification polling and GitHub Actions
polling. No further fix cycle runs after cancellation, and nothing is pushed.

### Git safety

- `git_commit` scans the staged diff and **blocks** the commit when a likely
  secret is present, reporting only the file and line.
- A push is only allowed from `main`; the push service cannot force, delete a ref
  or push a tag.
- Changes the task did not create are surfaced rather than committed silently.

### Secret detection

`SecretScan` detects token prefixes, private-key blocks, credential-like
assignments and environment-file secrets in added lines only. It reports location
and reason and never the value.

### Credentials

The LLM never sees a credential. GitHub tokens are lent only into an HTTP or Git
transport call through the existing `ConnectionCredentialGateway`; the model and
the UI only ever receive structured, redacted results.
