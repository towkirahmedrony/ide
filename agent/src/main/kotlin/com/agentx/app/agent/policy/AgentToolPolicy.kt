package com.agentx.app.agent.policy

import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.protocol.AgentProtocol
import com.agentx.app.tools.ToolDefinition
import com.agentx.app.tools.ToolPermissionLevel
import com.agentx.app.tools.codeintel.FindDefinitionTool
import com.agentx.app.tools.codeintel.FindReferencesTool
import com.agentx.app.tools.codeintel.GetFileOutlineTool
import com.agentx.app.tools.codeintel.GetFileSymbolsTool
import com.agentx.app.tools.effectiveAvailability
import com.agentx.app.tools.execution.RunCommandTool
import com.agentx.app.tools.filesystem.ListDirectoryTool
import com.agentx.app.tools.filesystem.ReadFileTool
import com.agentx.app.tools.filesystem.SearchFilesTool
import com.agentx.app.tools.filesystem.WriteFileTool
import com.agentx.app.tools.git.GitBranchesTool
import com.agentx.app.tools.git.GitCommitTool
import com.agentx.app.tools.git.GitDiffTool
import com.agentx.app.tools.git.GitLogTool
import com.agentx.app.tools.git.GitStatusTool
import com.agentx.app.tools.web.WebFetchTool
import com.agentx.app.tools.web.WebSearchTool

/**
 * The single authoritative tool policy.
 *
 * Before this file, "which tools may this agent use?" was answered in several
 * places that disagreed with each other:
 *
 *  - [com.agentx.app.agent.catalog.AgentCatalog] hand-wrote an `allowedTools`
 *    list per role, which is exactly how MAIN lost the code-intelligence tools;
 *  - most roles listed no tools at all, and the sub-agent factory filled that gap
 *    with `registry.names()`, so a sub-agent that named no tools received the
 *    *entire* tool registry;
 *  - the permission categories (NETWORK, COMMAND_EXECUTION, GIT_WRITE) were
 *    declared on the tool stubs but never reached a role;
 *  - tool *availability* and tool *authorization* were the same question, so a
 *    declared-but-unimplemented tool looked as usable as a working one.
 *
 * This object answers all of them in one place, and every other component derives
 * from it: the role's effective catalog, what the model is told exists, and what
 * the runtime will actually run. Adding a tool later means editing one grant here
 * — not several unrelated lists.
 *
 * Two deliberately different questions are answered separately, because
 * collapsing them is how a scope escape happens:
 *
 *  - **discovery** — [visibleToolIds]: what the model may *know exists*;
 *  - **authorization** — [isAuthorized]: what the runtime will *actually run*.
 *
 * A tool hidden from the model is still rejected when requested directly, and a
 * tool shown to the model must still pass authorization. Neither answer is taken
 * from a prompt, and neither is decided by the model.
 *
 * Nothing is invented here. A capability whose tool does not exist (GitHub,
 * browser, MCP) is simply not granted, and its stub is reported as unavailable
 * rather than advertised as working. The families that now have real tools
 * (filesystem, code intelligence, shell, git, web) are granted per role below.
 */
object AgentToolPolicy {

    /**
     * A named bundle of tools.
     *
     * Tools are grouped by *capability*, not by role, so each tool is listed once
     * in the whole project and a role gains it by naming the bundle.
     */
    enum class ToolGrant(val toolIds: Set<String>) {
        /** Listing, reading and searching inside the open workspace. */
        INSPECT(
            setOf(
                ListDirectoryTool.NAME,
                SearchFilesTool.NAME,
                ReadFileTool.NAME,
            ),
        ),

        /** Structural, read-only code understanding. Never mutation. */
        CODE_INTELLIGENCE(
            setOf(
                GetFileSymbolsTool.NAME,
                GetFileOutlineTool.NAME,
                FindDefinitionTool.NAME,
                FindReferencesTool.NAME,
            ),
        ),

        /** Writing inside the open workspace. Mutating, so it needs approval. */
        WRITE(setOf(WriteFileTool.NAME)),

        /** Running a shell command inside the open workspace. Approval-gated. */
        EXECUTE(setOf(RunCommandTool.NAME)),

        /** Read-only git inspection: status, diff, history and branches. */
        GIT_READ(
            setOf(
                GitStatusTool.NAME,
                GitDiffTool.NAME,
                GitLogTool.NAME,
                GitBranchesTool.NAME,
            ),
        ),

        /** Controlled git write. Approval-gated and capability-gated. */
        GIT_WRITE(setOf(GitCommitTool.NAME)),

        /** Web research: search plus single-page retrieval. */
        WEB(setOf(WebSearchTool.NAME, WebFetchTool.NAME)),

        /** Delegating a focused sub-task. Only the orchestrating role may. */
        DELEGATE(setOf(AgentProtocol.DELEGATE_TOOL)),

        /** Completing a turn with a structured result. Every role may. */
        FINISH(setOf(AgentProtocol.FINISH_TOOL)),
    }

    /**
     * The grants of every role, and the only place a role's tools are written.
     *
     * Each entry follows the role's real responsibility. A read-only role is not
     * given WRITE merely because another role has it, and no role is given a
     * capability whose tool does not exist:
     *
     *  - MAIN orchestrates, inspects, understands code, edits it, reads git and
     *    delegates;
     *  - EXPLORER / PLANNER reason about code without changing it;
     *  - REVIEWER / SECURITY_REVIEWER additionally read git diffs and history;
     *  - RESEARCHER searches and fetches the web without mutating the workspace;
     *  - CODER / FAST_CODER / DOCS change code as well as read it;
     *  - DEBUGGER / TESTER change code and may execute commands, within their
     *    COMMAND_EXECUTION ceiling and approval gate;
     *  - COMMIT_PR inspects git and performs the controlled git commit; GitHub
     *    operations are withheld until a credential path for tools exists.
     */
    private val ROLE_GRANTS: Map<AgentRole, Set<ToolGrant>> = mapOf(
        // MAIN orchestrates: it inspects, understands and edits code, reads git
        // history, and delegates. It does not hold shell or git write: those
        // belong to the specialist roles, so a broad capability never leaks into
        // the orchestrator.
        AgentRole.MAIN to setOf(
            ToolGrant.INSPECT,
            ToolGrant.CODE_INTELLIGENCE,
            ToolGrant.WRITE,
            ToolGrant.GIT_READ,
            ToolGrant.DELEGATE,
            ToolGrant.FINISH,
        ),
        // Read-only investigation. No mutation, no shell, no network.
        AgentRole.EXPLORER to setOf(
            ToolGrant.INSPECT,
            ToolGrant.CODE_INTELLIGENCE,
            ToolGrant.FINISH,
        ),
        AgentRole.PLANNER to setOf(
            ToolGrant.INSPECT,
            ToolGrant.CODE_INTELLIGENCE,
            ToolGrant.FINISH,
        ),
        // A reviewer reads the change set it reviews, but never mutates it.
        AgentRole.REVIEWER to setOf(
            ToolGrant.INSPECT,
            ToolGrant.CODE_INTELLIGENCE,
            ToolGrant.GIT_READ,
            ToolGrant.FINISH,
        ),
        AgentRole.SECURITY_REVIEWER to setOf(
            ToolGrant.INSPECT,
            ToolGrant.CODE_INTELLIGENCE,
            ToolGrant.GIT_READ,
            ToolGrant.FINISH,
        ),
        // The researcher's real job is external information: it searches and
        // fetches the web, and reads the project it is researching. It never
        // mutates the workspace.
        AgentRole.RESEARCHER to setOf(
            ToolGrant.INSPECT,
            ToolGrant.CODE_INTELLIGENCE,
            ToolGrant.WEB,
            ToolGrant.FINISH,
        ),
        AgentRole.CODER to setOf(
            ToolGrant.INSPECT,
            ToolGrant.CODE_INTELLIGENCE,
            ToolGrant.WRITE,
            ToolGrant.GIT_READ,
            ToolGrant.FINISH,
        ),
        AgentRole.FAST_CODER to setOf(
            ToolGrant.INSPECT,
            ToolGrant.CODE_INTELLIGENCE,
            ToolGrant.WRITE,
            ToolGrant.FINISH,
        ),
        // The debugger must run the code and its tests. It may execute commands,
        // but its write access stays approval-gated and its permission ceiling is
        // COMMAND_EXECUTION, not unrestricted.
        AgentRole.DEBUGGER to setOf(
            ToolGrant.INSPECT,
            ToolGrant.CODE_INTELLIGENCE,
            ToolGrant.WRITE,
            ToolGrant.EXECUTE,
            ToolGrant.GIT_READ,
            ToolGrant.FINISH,
        ),
        AgentRole.TESTER to setOf(
            ToolGrant.INSPECT,
            ToolGrant.CODE_INTELLIGENCE,
            ToolGrant.WRITE,
            ToolGrant.EXECUTE,
            ToolGrant.FINISH,
        ),
        AgentRole.DOCS to setOf(
            ToolGrant.INSPECT,
            ToolGrant.CODE_INTELLIGENCE,
            ToolGrant.WRITE,
            ToolGrant.FINISH,
        ),
        // Commit/PR owns the repository writes: git inspection, git commit and
        // (once a credential path exists) GitHub operations. It still holds no
        // source-editing tool, so it commits what the coder changed rather than
        // rewriting it.
        AgentRole.COMMIT_PR to setOf(
            ToolGrant.INSPECT,
            ToolGrant.CODE_INTELLIGENCE,
            ToolGrant.GIT_READ,
            ToolGrant.GIT_WRITE,
            ToolGrant.FINISH,
        ),
    )

    /**
     * Every tool id some role grant names — the set this policy is authoritative
     * for, and therefore the set the router is allowed to deny on role grounds.
     */
    private val governedToolIds: Set<String> =
        ROLE_GRANTS.values.flatten().flatMap { grant -> grant.toolIds }.toSet()

    /** The grants [role] holds. Deterministic for the lifetime of the build. */
    fun grants(role: AgentRole): Set<ToolGrant> = ROLE_GRANTS[role].orEmpty()

    /**
     * True when this policy names [toolId] at all.
     *
     * The policy owns the built-in catalog. A tool it does not name is one the
     * runtime contributed (a provider or integration tool): it is not granted here,
     * so it is judged by its own declared permission, connection and capability
     * requirements instead — and it still had to be visible to the run that invoked
     * it, because visibility is derived from this policy.
     */
    fun governs(toolId: String): Boolean = toolId in governedToolIds

    /**
     * The tools [role] may know about, in presentation order, before the registry
     * and availability are considered.
     */
    fun toolIdsFor(role: AgentRole): List<String> =
        grants(role).flatMap { grant -> grant.toolIds.toList() }.distinct()

    /**
     * Authorization: may the runtime run [toolId] for [role] at all?
     *
     * This is the deterministic answer the Tool Router re-checks, so a call that
     * reaches the router directly — malformed model output, a replayed call, or a
     * caller that built its own list — is still refused when the role was never
     * granted that tool. It never consults the model or a prompt.
     */
    fun isAuthorized(role: AgentRole, toolId: String): Boolean = toolId in toolIdsFor(role)

    /**
     * Narrows a caller-supplied list to what [role] is actually authorized for.
     *
     * A caller may only ever take away. A sub-agent request that asks for more
     * than its role holds cannot widen its own scope, which is what previously let
     * a sub-agent run with the parent's entire registry.
     */
    fun restrictToRole(role: AgentRole, requested: Collection<String>): List<String> {
        val authorized = toolIdsFor(role).toSet()
        return requested.filter { it in authorized }.distinct()
    }

    /**
     * The effective, ordered catalog for [role]: the role's own tools, narrowed by
     * [requested] when a caller asked for a subset, minus anything the registry
     * does not have and anything the runtime cannot actually run.
     *
     * @param definitionOf resolves a tool definition, so availability is judged on
     *   the tool itself. A missing definition means the tool does not exist here.
     * @param requested null for the role's full grant; a collection to narrow it.
     */
    fun effectiveToolIds(
        role: AgentRole,
        definitionOf: (String) -> ToolDefinition?,
        requested: Collection<String>? = null,
    ): List<String> {
        val base = when (requested) {
            null -> toolIdsFor(role)
            // A request can only remove tools from the grant. Anything it names that
            // the role was never granted is dropped, never honoured.
            else -> restrictToRole(role, requested)
        }
        return base.filter { toolId -> isRunnable(toolId, definitionOf) }
    }

    /**
     * Discovery: the tools [role] may be told about. A declared-but-unavailable
     * tool is filtered out here, so an unimplemented tool is never advertised to
     * the model as if it worked.
     */
    fun visibleToolIds(role: AgentRole, definitionOf: (String) -> ToolDefinition?): List<String> =
        effectiveToolIds(role = role, definitionOf = definitionOf)

    /** The permission category a tool requires, for policy reporting only. */
    fun requiredPermissions(definition: ToolDefinition): Set<String> =
        definition.requiredPermissions.map(ToolPermissionLevel::name).toSortedSet()

    /**
     * True when [toolId] can be run in this build: it exists in the registry and
     * the runtime can actually execute it.
     *
     * The loop-handled protocol tools are always runnable; they never enter the
     * Tool System.
     */
    private fun isRunnable(toolId: String, definitionOf: (String) -> ToolDefinition?): Boolean {
        if (toolId == AgentProtocol.DELEGATE_TOOL || toolId == AgentProtocol.FINISH_TOOL) return true
        val definition = definitionOf(toolId) ?: return false
        return definition.effectiveAvailability.isAvailable
    }
}
