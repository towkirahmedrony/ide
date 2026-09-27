<div align="center">
  
  <h1>💻 Coder</h1>
  <p><b>A local-first, agentic AI IDE for Android.</b></p>

  <p>
    Coder is a mobile-first development environment designed around autonomous AI agents rather than simple code generation.<br>
    Use your own local or self-hosted AI model, connect cloud/API models when needed, give the agent access to your workspace and development tools, and let it inspect, modify, test, debug, research, and manage your projects from one place.
  </p>

  <p><i>Your model. Your workspace. Your tools. Your control.</i></p>

</div>

<hr>

## ✨ Vision

Traditional AI coding tools mainly work like:
> `Prompt` ➔ `Generated Code`

**Coder** is designed to work like:
> `Task` ➔ `Understand` ➔ `Plan` ➔ `Inspect Workspace` ➔ `Search` ➔ `Read Code` ➔ `Use Tools` ➔ `Modify Files` ➔ `Run Commands` ➔ `Observe Results` ➔ `Debug` ➔ `Test` ➔ `Review` ➔ `Complete`

The goal is to bring a serious agentic software-development workflow to Android, while keeping the AI layer flexible and user-controlled.

---

## 🧠 Local-First AI

Coder is designed to work with your own AI infrastructure. You can run an open-source model on:
- Your own computer
- A local network server
- A remote Linux server
- A cloud GPU
- Google Colab
- Another self-hosted environment

The IDE communicates through a model/provider abstraction instead of being locked to a single AI company.

```text
Android │ ▼ Coder │ ▼ Agent Engine │ ▼ Model Gateway │ ▼ Qwen / Llama / DeepSeek / Other Model
```
*A model can be hosted remotely and exposed through an OpenAI-compatible endpoint, allowing the Android IDE to act as the control interface while inference happens elsewhere.*

---

## 🔌 Multiple AI Providers

Local AI is the primary design goal, but Coder is intended to remain provider-agnostic. Potential providers include:

- **Local:** `Ollama`, `llama.cpp`, `vLLM`
- **Endpoints:** OpenAI-compatible endpoints
- **Cloud:** `OpenAI`, `Anthropic`, `Google`, `OpenRouter`
- **Custom:** Self-hosted providers

---

## 🤖 Agentic Development

Coder is designed around an agent loop rather than a one-shot chatbot. An agent can potentially work through complex steps:

**Example Task:** *"Fix the authentication bug, run the tests, and resolve any errors you find."*

> `Find auth code` ➔ `Inspect files` ➔ `Identify problem` ➔ `Create patch` ➔ `Run tests` ➔ `Read failure` ➔ `Fix` ➔ `Run tests again` ➔ `Review changes`

---

## 🛠️ Tool System

Tools are the foundation of Coder's agentic capabilities. The agent should never receive unrestricted access to the environment. Actions are exposed through a controlled tool system.

| Category | Planned Tools |
| :--- | :--- |
| **Filesystem** | `list_files`, `read_file`, `read_range`, `search_files`, `search_text`, `find_symbol`, `create_file`, `write_file`, `apply_patch`, `rename_file`, `move_file`, `delete_file` |
| **Terminal** | `run_command`, `get_process`, `kill_process` |
| **Git** | `git_status`, `git_diff`, `git_log`, `git_branch`, `git_checkout`, `git_commit`, `git_stash`, `git_merge`, `git_pull`, `git_push`, `git_tag` |
| **Web & Browser** | `web_search`, `web_fetch`, `open_page`, `click`, `type`, `scroll`, `snapshot`, `screenshot`, `extract`, `navigate` |
| **GitHub** | Repository browsing, code search, issues, branches, commits, pull requests, reviews |

---

## 🔐 Permission & Safety System

Agentic software needs strong control over what an AI is allowed to do. Coder is designed around explicit tool permissions (`ALLOW`, `ASK`, `DENY`).

| Action | Policy |
| :--- | :--- |
| Read / Search files | 🟢 **ALLOW** |
| Git status | 🟢 **ALLOW** |
| Edit files / Delete files | 🟡 **ASK** |
| Run shell command / Git Push | 🟡 **ASK** |
| Access secrets | 🔴 **DENY** |

---

## 🧩 Skills & Context Intelligence

### Skills
Coder supports reusable AI skills containing instructions, references, templates, and scripts.
```text
skills/
├── android/
├── nextjs/
├── react/
├── python/
├── supabase/
├── debugging/
└── testing/
```

### Context Intelligence
Large repositories cannot simply be dumped into an AI context. Coder intelligently selects high-value context:
> `Current task` + `Current file` + `Relevant files` + `Repository map` + `Symbols` + `Search results` + `Tool results` + `Git diff` + `Previous errors` + `Project memory`

---

## 💻 Code Editor & Review

Coder aims to provide a capable mobile development environment:
- Syntax highlighting, Tabs, Search & Replace
- Code navigation, Symbol outline, Diagnostics
- AI-assisted editing & Code folding

### 🔀 AI Diff & Change Review
Instead of silently replacing entire files, the agent generates structured patches. The user can **Accept**, **Reject**, **Review**, or **Revert**.
```diff
- old implementation
+ new implementation
```

---

## 🧪 Build, Test & Debug

Close the loop between writing code and verifying code:
> `Edit` ➔ `Build` ➔ `Observe` ➔ `Diagnose` ➔ `Fix` ➔ `Build again`

Integrations include: **Gradle, npm, pnpm, yarn, Python, pytest, Rust, Cargo**, and custom commands.

---

## ☁️ Remote AI & Architecture

Coder does not require the model to run on the Android device.

```text
Android 
  ▼ Coder 
  ▼ Agent Core 
  ▼ Remote Endpoint 
  ┌────────┴────────┐ 
  ▼                 ▼ 
Cloudflare        ngrok 
  │                 │ 
  └────────┬────────┘ 
  ▼ Remote Server 
  ▼ Local AI Model (Qwen / Llama)
```

---

## 🛣️ Roadmap

### Phase 1 — IDE Foundation
- [ ] Project/workspace management
- [ ] File explorer & Code editor
- [ ] Tabs, Search, Terminal, Git integration

### Phase 2 — AI Foundation
- [ ] Model Gateway (Local & OpenAI-compatible endpoints)
- [ ] Streaming & Conversation management
- [ ] Tool calling & Basic agent loop

### Phase 3 — Coding Agent
- [ ] File & Search tools
- [ ] Patch-based editing & Shell tools
- [ ] Task planning & Agent permissions
- [ ] Build/test loop & Error recovery

### Phase 4 & Beyond (Advanced Features)
- [ ] Repository indexing & Context compaction
- [ ] Subagents, MCP, Skills, Project memory
- [ ] Web search & Browser automation
- [ ] Remote Development (SSH, Cloud GPU, Colab)

---

## 🔒 Privacy & Philosophy

<div align="center">
  <p><b>When using a self-hosted/local model:</b></p>
  <p><code>Your Code</code> ➔ <code>Your Agent</code> ➔ <code>Your Model</code></p>
</div>

There is no requirement to send source code to a third-party AI provider. AI should work inside your development environment — not just talk about code. 

**Coder is being built to make that possible on Android.**

<div align="center">
  <br>
  <p><i>🚧 Status: Early Development. The architecture and feature set are evolving.</i></p>
</div>
