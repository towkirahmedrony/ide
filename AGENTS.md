# 🤖 Coder — AI Agent Guidelines & Architecture

## 1. Project Mission
**Coder** is a professional, **Android-first, agentic AI IDE**. 
- **Android-Native:** Designed for touch, bottom sheets, and mobile screens. Not a shrunk desktop IDE.
- **Model-Agnostic:** Built for local (Ollama, vLLM) and remote (OpenAI, API) models.
- **Separation of Concerns:** `UI` ➔ `Agent Core` ➔ `Model Gateway` ➔ `Tool Bus` ➔ `Execution Backend`.

## 2. The Agent Workflow
Agents must not blindly generate code. Always follow this logical loop:
> `Inspect` ➔ `Understand` ➔ `Plan` ➔ `Targeted Read` ➔ `Modify (Patch)` ➔ `Verify` ➔ `Review`

## 3. Tools & Permissions
All actions pass through a secure **Tool Bus**. Models cannot execute arbitrary operations directly.
- **Capabilities:** Filesystem, Terminal, Git, GitHub, Web Search, MCP, Reusable Skills.
- **Permission System:**
  - 🟢 **ALLOW:** Low risk (read files, search, git status).
  - 🟡 **ASK:** Medium risk (write files, run shell commands).
  - 🔴 **DENY / ASK:** High risk (delete files, push to remote, access secrets).

## 4. Context Intelligence
- **No full repo dumps.** Use targeted retrieval (symbols, file ranges, project maps).
- Retain compact **Session Memory** (current task, errors) and **Project Memory** (architecture rules, build commands).

---

## ⚠️ 5. Strict Rules for AI Agents Modifying This Project

**1. Always Inspect First**
Do not rewrite files blindly. Read existing architecture, relationships, and implementations before planning changes.

**2. Make Focused Changes**
Implement the smallest correct change. Do not perform unrelated refactoring, rename files arbitrarily, or change working dependency versions without explicit need.

**3. Do Not Invent Functionality**
If a feature or tool isn't implemented yet, use clear placeholders or interfaces. Do not fake AI responses, tool executions, or Git operations to make the UI look complete.

**4. NO Local Android Builds**
**DO NOT** attempt to build/compile APKs locally within the AI environment. 
- **Required Workflow:** `Write Code` ➔ `Push to GitHub` ➔ `Verify via GitHub Actions` ➔ `Read CI Logs` ➔ `Fix if needed`.

**5. User is the Owner**
The AI is the worker. Ask for confirmation before high-risk actions (destructive Git commands, deleting files). Every AI modification must be reviewable by the user via a clear Diff (Before/After).

---
*Product Philosophy: Make AI powerful while keeping the user's project, files, credentials, and decisions strictly under user control.*
