# MonkeyCode Frontend: How Live Agent Task Execution Is Represented

Read-only research on `github.com/chaitin/MonkeyCode` (branch `main`). All paths are repo-relative.
Everything below was read from the repository via `raw.githubusercontent.com` and the GitHub API.
Where I could not verify something I say so explicitly; inferences are labelled **[inferred]**.

Stack note: the frontend is **React 19 + TypeScript + Vite** (not Vue), routing via `react-router-dom`,
virtualization via `@tanstack/react-virtual`, panels via `react-resizable-panels` (`frontend/package.json`).
There is **no pinia/redux/vuex/zustand store** — live task state is held in React hooks inside the page,
plus one non-React class (`TaskStreamClient` → `TaskMessageHandler`) and a React context `DataProvider`
(`frontend/src/components/console/data-provider.tsx`) for account/host/model data. This directly answers
research point 3: there is no store module modelling tasks; the model lives in a plain class + handler.

---

## A. Files inspected

| Path | Purpose (one line) |
|---|---|
| `frontend/doc.md` | Product/feature doc; describes task detail page regions, statuses, plan blocks, message history loading. |
| `frontend/package.json` | Confirms React/Vite stack and the absence of Vuex/pinia/redux. |
| `frontend/src/App.tsx` | Route entry; `/console/task/:taskId` → `TaskDetailPage` (keyed by `taskId`). |
| `frontend/src/pages/console/user/task/task-detail.tsx` | The task detail page: wires stream client, message list, plan block, history paging, panels, elapsed time. |
| `frontend/src/components/console/task/message.tsx` | Defines `MessageType` and dispatches each message type to its renderer item. |
| `.../task/message-toolcall.tsx` | Tool-call renderer registry + status markers + collapsible execution block. |
| `.../task/message-thought.tsx` | Renders `agent_thought_chunk` ("thought") blocks with auto-collapse-when-not-latest. |
| `.../task/message-text.tsx` | Renders `agent_message_chunk` text via Markdown. |
| `.../task/message-error.tsx` | Renders `error_message` with hover detail + "repair"/continue action. |
| `.../task/message-system.tsx` | Renders `system_message` as a centered pill. |
| `.../task/message-alert.tsx` | Renders `alert_message` (info/warning) with copy. |
| `.../task/message-ask-user-question.tsx` | Renders `ask_user_question` (radio/checkbox/custom) and its status footer. |
| `.../task/message-userinput.tsx` | Renders `user_input` with attachments and copy. |
| `.../task/message-restart-session.tsx` | Renders `restart_session` marker. |
| `.../task/chat-panel.tsx` | `PlanStepsBlock`: the plan-step UI (collapsed/expanded, status icons). |
| `.../task/task-shared.ts` | Shared types: `PlanEntry`, `TaskPlan`, `AvailableCommand(s)`, `TaskStreamStatus`, user-input payloads. |
| `.../task/task-message-handler.ts` | Pure reducer class: raw WS chunk → `messages[]`, `plan`, `availableCommands`, `contextUsage`, cursor. |
| `.../task/task-stream-client.ts` | WebSocket client: connect/reconnect/dedupe/execute-timer; emits `TaskStreamClientState`. |
| `.../task/task-stream-dedupe.ts` | `TaskStreamDedupChunk` shape + de-dup key. |
| `.../task/task-message-virtual-list.tsx` | Virtualized message list with history-loader row and scroll/highlight. |
| `.../task/task-rounds.ts` | REST loader for historical rounds (replays chunks through a fresh `TaskMessageHandler`). |
| `.../task/toolcalls/fallback.tsx` | Default tool renderer: title synthesis + command/output detail panel. |
| `.../task/toolcalls/apply_patch.tsx` | Patch/edit detail renderer with diff viewer. |
| `.../task/toolcalls/opencode_read.tsx`, `opencode_search.tsx`, `edit-diff-preview.tsx` | Per-tool detail/title renderers. |

Not read directly: `frontend/src/api/Api.ts` (230 KB generated API). Task-type field names below that come
from `Api.ts` are therefore cited only as they are *used* in `task-detail.tsx`, not from `Api.ts` itself.

---

## B. Data model

### Message (`message.tsx`, interface `MessageType`)
```
id: string; time: number; role: 'agent' | 'user' | 'system';
type: 'agent_message_chunk' | 'agent_thought_chunk' | 'user_input' | 'user_cancel'
    | 'tool_call' | 'tool_call_update' | 'available_commands_update' | 'plan'
    | 'error_message' | 'alert_message' | 'ask_user_question' | 'system_message' | 'restart_session';
data: {
  content?: any;      // text for text/thought chunks; structured content for tool calls
  kind?: string; status?: string; title?: string; toolCallId?: string;
  rawInput?: any; rawOutput?: any; locations?: string;
  entries?: { content: string; priority: string; status: string }[];  // plan payload
  details?: string; text?: string; level?: 'info' | 'warning';         // error / alert
  attachments?: TaskUserInputAttachment[];
  askId?: string;
  questions?: { custom: boolean; header: string; multiSelect: boolean;
                question: string; answer?: string | string[];
                options: { label: string; description: string }[] }[];
  _meta?: any; requestId?: string;
}
```
Renderers are attached as callbacks on the message object (`onResponseAskUserQuestion`, `onReloadSession`,
`onUserInput`) so interactive cards can call back into the page.

### Task step / plan step (`task-shared.ts`)
```
interface PlanEntry { content: string; status: string }
interface TaskPlan  { entries: PlanEntry[]; version: number }
```
`version` is a monotonically increasing counter bumped on every `plan` update (used for change detection).
Note the plan entry status is a free `string` in the type; the UI compares against `"in_progress"` and
`"completed"` (`chat-panel.tsx`). There is **no per-entry id** — entries are keyed by array index.

### Task
There is no client-side `Task` model class. The page stores `task` as `DomainProjectTask` fetched from REST
(`task-detail.tsx` imports it from `@/api/Api`) and uses `ConstsTaskStatus.{TaskStatusPending,
TaskStatusProcessing, TaskStatusFinished, TaskStatusError}`. `taskInteractive` is computed as
`task?.status === ConstsTaskStatus.TaskStatusProcessing`. Field meanings are described in `doc.md`
§五.4: cards show name, repo/ZIP name, status, token usage and creation time. **[inferred]** concrete
column names were not read from `Api.ts`.

### Tool-call execution
Represented as `tool_call` messages; the tool identity is `data.title` (+ `data.kind`), correlated updates
by `data.toolCallId`. Status domain observed: `"pending" | "in_progress" | "completed" | "failed"`
(`message-toolcall.tsx` `renderStatus`). `rawInput` carries tool arguments (e.g. `command`, `parsed_cmd[]`,
`file_path`/`filePath`, `patchText`, `port`, `questions`); `rawOutput` carries results — the renderers use
`rawOutput.output`, `rawOutput.error`, `rawOutput.stdout`, `rawOutput.stderr` (verified across
`toolcalls/*.tsx`). **No exit-code field is read anywhere** (grep for `exit` across all toolcall renderers
returned nothing).

### Stream client state (`task-stream-client.ts`)
```
TaskStreamClientState = TaskMessageHandlerState & {
  executionTimeMs: number; connectionState: 'connecting'|'connected'|'reconnecting'|'closed';
  queuedReplyIds: string[]; submittingReplyIds: string[];
  closeReason: 'manual'|'task_ended'|'unknown'|null }
TaskMessageHandlerState = { status:'inited'|'connected'|'finished'|'error'; messages: MessageType[];
  plan: TaskPlan; availableCommands: AvailableCommands;
  contextUsage:{ size:number|null; used:number|null }; historyCursor:{cursor|null;hasMore;ready} }
```
`AvailableCommand = { name; description; input:{hint:string|null}|null }` (`task-shared.ts`).

---

## C. Event flow

**Transport: a single WebSocket** (not SSE).
`task-stream-client.ts` → `buildStreamUrl()`:
`${ws|wss}://${host}/api/v1/users/tasks/stream?id=<taskId>&mode=<attach|new>`.
Server→client frames are JSON. `task-stream-dedupe.ts` defines the envelope:
```
interface TaskStreamDedupChunk { type?: string; kind?: string; data?: unknown; timestamp?: number; seq?: number|string }
```
De-dup key = JSON of `[type, kind, seq, timestamp, data]`; keys are tracked up to `MAX_TRACKED_CHUNKS = 2000`.

**Event names** (outer `type`, handled in `task-message-handler.ts` `processMessage`):
`user-input`, `user-cancel`, `task-started`, `ping`, `task-running`, `task-ended`, `task-error`,
`reply-question`, `cursor`. `task-running` is a wrapper whose `kind` is either `acp_event` or
`acp_ask_user_question`; its `data` is **base64-encoded JSON** (`b64decode`).

**Inner ACP `sessionUpdate` names** (`applyACPEvent`, same file): `agent_message_chunk`,
`agent_thought_chunk`, `tool_call`, `tool_call_update`, `available_commands_update`, `plan`,
`usage_update`, `llm_call_retry`, `compact_status`. Unknown ones log a warning.

**Client→server**: `user-input` (base64 content + attachments), `user-cancel`, `reply-question`
(`request_id`, `answers_json`, `cancelled`).

**Running → done/failed transitions** (verified):
- Each `tool_call_update` merges fields onto the existing `tool_call` message (`applyToolCall`), so a tool
  goes `pending`→`in_progress`→`completed`/`failed` by successive `sessionUpdate: "tool_call_update"` chunks.
- `task-ended` → `applyTaskEnded()` → `failPendingToolCalls()` flips every `tool_call` still in
  `in_progress`/`pending` to `failed`, then sets handler status `finished`.
- `disconnect()` / `setError()` also call `failPendingToolCalls()`.
- When the handler reaches `finished`, the stream client calls `disconnect()`; `onclose` sets
  `connectionState="closed"` and `closeReason` `task_ended`/`manual`/`unknown`.
- Reconnect: if the socket drops before `task-ended`, state becomes `reconnecting` and it retries with
  backoff `[500,1000,2000,4000,8000] ms`.

**Execution timer**: while `status==="connected"`, a 100 ms `setInterval` re-emits state so
`executionTimeMs` (now − first `user_input` timestamp) keeps ticking; the page copies it into `timeCost`.

**History** (REST, separate path): `task-rounds.ts` / `fetchTaskRounds` call `v1UsersTasksRoundsList`
with `{id, limit, cursor}`, then **replay the returned `chunks` through a fresh `TaskMessageHandler`**
and `finalizeCycle()`. Results are unshifted above live messages; `next_cursor`/`has_more` drive a
"load history / load more" button rendered as a virtual row at the top of the list.

---

## D. UX patterns

**Layout.** `task-detail.tsx` composes: vertical `ResizablePanelGroup` → horizontal group → left chat panel
(`ScrollArea` + `TaskMessageVirtualList`), optional right files panel, optional bottom terminal panel.
The chat column is capped at `max-w-[960px]` when no side panel. The task-input box sits below the message
list; the plan block sits just above the input.

**Activity/progress = the plan block** (`chat-panel.tsx` `PlanStepsBlock`, rendered only when
`taskInteractive && plan.entries.length > 0`). Header: `IconSubtask` + label
`t("taskDetail.plan.title", { completed, total })`, with a `ChevronsUpDown`/`ChevronsDownUp` toggle.
- **Collapsed (default):** shows only the first `in_progress` entry, and only while
  `streamStatus === "executing"`; otherwise it renders `null`.
- **Expanded:** all entries in a `max-h-48 overflow-y-auto` list.
- **Status markers:** `in_progress` (and executing) → `IconLoader animate-spin` + primary-colour text;
  `completed` → `IconCircleCheck text-primary` + muted text; else → `IconCircle text-muted-foreground`.
  There is no explicit "failed" marker for a plan entry — only three states are rendered.

**"executing" is derived, not sent:** `planStreamStatus = streamStatus === "connected" ? "executing" : streamStatus`
(`task-detail.tsx`). So auto-collapse-on-completion is achieved by the plan block returning `null` once the
stream leaves `connected`, and the thought block collapsing when it is no longer the latest message.

**Tool-call block** (`message-toolcall.tsx`). A registry (`toolCallRenderers[]`) matches on
`data.kind`/`data.title`/cli name and supplies `renderTitle`/`renderDetail`/`expandable`; unmatched tools
fall back to `toolcalls/fallback.tsx`. Status marker (`renderStatus`): `in_progress` and `pending` →
`Spinner`; `completed` → `IconCircleCheck`; `failed` → `IconAlertTriangle`. Title is `line-clamp-1 text-xs`.
Expandable items use a `Collapsible` (local `open` state, default **false**) with `IconChevronUp/Down`;
expanded detail renders in `mt-1 rounded-md border bg-muted/30 text-xs max-h-[50vh] overflow-auto`.
`edit` tool calls in `pending`/`failed` are forced non-expandable (compact single-line). A `failed` step
stays visible because nothing removes messages — only its status icon/title change; the fallback title
becomes "edit failed"/"editing file" text (`fallback.tsx`, `apply_patch.tsx`).

**Tool detail content** (`toolcalls/fallback.tsx`): shows `cwd`, then a `$ <input>` line (from
`rawInput.command` or last element of `rawInput.command[]`), then output built from
`rawOutput.stdout` + `rawOutput.stderr`, falling back to `rawOutput.output` or the first text content
block; empty output shows a "command output empty" label. If no input is present it shows
`JSON.stringify(message.data, null, 2)`. `apply_patch.tsx` + `edit-diff-preview.tsx` render unified/split
diffs via `UnifiedDiffViewer`; `opencode_read.tsx`/`opencode_search.tsx` render `rawOutput.output` in a
`<pre>`. **No exit-code display exists.**

**Thought block** (`message-thought.tsx`): dashed-border `max-w-[80%]` card titled with
`t("taskDetail.thought.title")`; collapsed state is `!isLatest`, so the newest thought auto-expands and
older ones auto-collapse to `line-clamp-1 ellipsis`; a chevron toggles it; expanded uses `whitespace-pre-wrap`.

**Message list & auto-scroll** (`task-message-virtual-list.tsx`): virtualized rows with `estimateSize`
heuristics (user 72px, tool/thought 156px, error/question 132px, text scaled by length, capped 420px),
`overscan: 8`. A `history-loader` row is injected at the top. Jump-to-message highlights via a
`jump-highlight` class. `task-detail.tsx` tracks `shouldAutoScrollChatRef` (auto-follow only when already
within 24px of the bottom, with a short "intent lock" so user scroll-up is respected) and exposes
scroll-to-bottom.

**Other message kinds:** system → centered muted pill; alert (`message-alert.tsx`) → warning icon when
`level==="warning"`, text + copy button; error (`message-error.tsx`) → `IconAlertTriangle` + a
`HoverCard` showing `data.details` and a **"repair"/continue** button that reloads the session and sends a
continue instruction; `ask_user_question` (`message-ask-user-question.tsx`) → per-question
radio(single)/checkbox(multi) with a free-text "other" option and a footer status of
`pending`→`queued`/`submitting`/`completed`/`expired`; `user_input` → content + attachment chips.

**Elapsed time:** shown in the chat input box (`executionTimeMs={timeCost}` in `task-detail.tsx`); `doc.md`
§六.6 states that while executing the text box hides and "the page shows the current elapsed time" and the
send button becomes a stop button.

**Sub-agent / nested steps: NOT FOUND.** A grep for `subagent|sub-agent|subtask|Task tool` across the task
components returned nothing, and no renderer nests tool calls inside one another. `IconSubtask` is used
only as the plan-block header icon. I must therefore report that this repository shows **no evidence of a
dedicated sub-agent/nested-step UI**.

---

## E. Chain-of-thought handling

The frontend **does receive and display model "thought" text**, but as a distinct, de-emphasised block:

- Transport: `sessionUpdate: "agent_thought_chunk"` arrives inside `acp_event` and is accumulated into
  `MessageType.type === "agent_thought_chunk"` messages (`task-message-handler.ts` `applyAgentThoughtChunk`).
  Chunks are concatenated onto the previous thought message.
- Rendering: `message-thought.tsx` prints `message.data.content` verbatim inside a dashed-border block
  labelled by `t("taskDetail.thought.title")` (i.e. a "Thought/思考" label, exact string in
  `task-i18n.ts` not read here). Newest thought auto-expands; older ones collapse to a single ellipsised line.
- "Safe user-facing text" is the separate **`agent_message_chunk`** stream, rendered as Markdown in
  `message-text.tsx`, and the **tool titles/status** in `message-toolcall.tsx`. Tool details are rendered
  from structured `rawInput`/`rawOutput` only; the fallback renderer dumps JSON only when it has neither a
  command nor output.

I found **no code that strips, redacts, or filters reasoning for safety** — thoughts are shown as-is
(collapsed by default). Whether the *server* withholds reasoning before sending is outside the frontend and
not verifiable from this repository. This is a factual observation, not a claim that raw reasoning is safe
to expose; treat "do not render raw CoT" as a design choice to make explicitly.

---

## F. Ideas worth adapting (not copying) — for a Kotlin/Compose desktop UI

1. **Class reducer, not a store.** `TaskMessageHandler` is a pure `chunk → immutable state` function;
   a Compose `AgentSessionState` exposed via `StateFlow` would map 1:1 and stay testable without UI.
   *Rationale: keeps transport parsing deterministic and unit-testable, independent of Compose recomposition.*
2. **Derive "running" instead of trusting a flag.** The plan block computes `executing` from
   `streamStatus === "connected"`. *Rationale: a single source of truth avoids stuck spinners when the socket closes.*
3. **Fail-open pending items on disconnect.** `failPendingToolCalls()` flips lingering `pending/in_progress`
   tool calls to `failed` on `task-ended`/error/disconnect. *Rationale: prevents eternal spinners in the UI.*
4. **Tool-renderer registry keyed by kind/title.** Match → `(title, detail, expandable)` lets each tool own
   its card while unknown tools fall back gracefully. *Rationale: offline-extensible tool coverage without
   touching the message shell.*
5. **Three-state status icon set** (spinner / check / warning) reused by both plan steps and tool calls.
   *Rationale: consistent, low-cognitive-load status language across all execution surfaces.*
6. **Collapse-by-default detail with a hard scroll cap** (`max-h-[50vh]`, `line-clamp-1` title).
   *Rationale: long command output and diffs don't blow up the timeline; expansion is opt-in.*
7. **Auto-expand newest thought, auto-collapse older ones by an `isLatest` flag.** *Rationale: gives live
   feedback on the current step while keeping history compact — no timers needed, purely index-based.*
8. **Compact "current step only" progress strip that expands to the full checklist.** *Rationale: answers
   "what is it doing now?" at a glance but allows full-plan audit on demand.*
9. **Virtualize the transcript with per-type height estimates.** *Rationale: long agent runs stay smooth;
   Compose `LazyColumn` with `contentType`/estimated heights is the direct analogue.*
10. **Sticky "load older rounds" row at the top** that pages history and prepends. *Rationale: unbounded
    history without loading everything up front.*
11. **Only auto-scroll when the user is at the bottom** (with a short scroll-intent lock). *Rationale: live
    streams don't yank the viewport while the user reads earlier output.*
12. **A "repair/continue" action on failed messages.** Reload session + resend
    a continue prompt (`message-error.tsx`). *Rationale: turns a dead end into a one-click recovery path.*
13. **Optimistic local echo for user replies** (`applyOptimisticReplyQuestion`, `queued`/`submitting`
    states before server ack). *Rationale: responsive feel on high-latency/intermittent links.*

---

### Verified vs inferred — quick index
- **Verified by reading source:** all event names, the WS URL, base64 chunk encoding, `MessageType`, the
  plan/tool/thought rendering rules, status markers, virtual list + auto-scroll, history replay, dedupe,
  reconnect/backoff, and the absence of exit-code and sub-agent UI.
- **From `frontend/doc.md` only:** product-level page regions, task status labels (finished→"已终止",
  error→"启动失败", pending→"正在启动", processing→"运行中"), and the "elapsed time while executing" description.
- **Inferred / not read:** exact `Api.ts` type fields for `DomainProjectTask`; exact i18n strings in
  `task-i18n.ts`; server-side reasoning redaction.
