package com.agentx.app.ui.ide.nav

/** Route definitions for the IDE shell's single-activity navigation graph. */
object IdeDestinations {

    const val HOME = "home"

    const val WORKSPACE = "workspace/{workspaceId}"
    const val ARG_WORKSPACE_ID = "workspaceId"

    const val SETTINGS = "settings"

    // Prefixed with "section/" so it cannot collide with the exact agent/skill routes below.
    const val SETTINGS_DETAIL = "settings/section/{sectionId}"
    const val ARG_SECTION_ID = "sectionId"
    const val ARG_PRESET_ID = "presetId"

    /** Settings → Agents: the list of agent prompts and one prompt editor. */
    const val AGENT_PROMPTS = "settings/agents"
    const val AGENT_PROMPT_EDITOR = "settings/agents/{role}"
    const val ARG_ROLE = "role"

    /** Settings → Agent Models: per-role provider/model assignment. */
    const val AGENT_MODELS = "settings/agent-models"

    /** Settings → Skills: the installed list and one skill's detail. */
    const val SKILLS = "settings/skills"
    const val SKILL_DETAIL = "settings/skills/{skillId}"
    const val ARG_SKILL_ID = "skillId"

    /** Settings → Permissions: the Android permissions and system access AgentX uses. */
    const val PERMISSIONS = "settings/permissions"

    /** Settings → Tools: the live tool catalog and per-tool enablement. */
    const val TOOLS = "settings/tools"

    const val ABOUT = "about"
    const val DEVELOPER = "developer"
    const val DEVELOPER_LOGS = "developer/logs"

    /** Saved model presets: the Model Manager screen. */
    const val MODELS = "models"

    const val MODEL_EDITOR = "models/editor/{presetId}"
    const val MODEL_RUNNER = "models/runner/{presetId}"

    /** One saved model: identity, status, assigned roles, usage and actions. */
    const val MODEL_DETAIL = "models/detail/{presetId}"

    /** Sentinel for "the user is adding a model" in [MODEL_EDITOR]. */
    const val NEW_MODEL = "new"

    /** Saved external-service connections. */
    const val CONNECTIONS = "connections"

    /** One service, in full: capabilities, connect/manage and its agent tools. */
    const val SERVICE_DETAILS = "connections/service/{type}"
    const val ARG_SERVICE_TYPE = "type"

    const val CONNECTION_EDITOR = "connections/editor/{connectionId}"
    const val ARG_CONNECTION_ID = "connectionId"

    /** Sentinel for "the user is adding a connection" in [CONNECTION_EDITOR]. */
    const val NEW_CONNECTION = "new"

    fun workspace(workspaceId: String): String = "workspace/$workspaceId"

    fun settingsDetail(sectionId: String): String = "settings/section/$sectionId"

    fun agentPromptEditor(role: String): String = "settings/agents/$role"

    fun skillDetail(skillId: String): String = "settings/skills/$skillId"

    fun modelEditor(presetId: String? = null): String = "models/editor/${presetId ?: NEW_MODEL}"

    fun modelRunner(presetId: String): String = "models/runner/$presetId"

    fun modelDetail(presetId: String): String = "models/detail/$presetId"

    fun serviceDetails(type: String): String = "connections/service/$type"

    /** The editor can start on a pre-selected service, e.g. adding an MCP server. */
    fun connectionEditor(connectionId: String? = null, type: String? = null): String {
        val base = "connections/editor/${connectionId ?: NEW_CONNECTION}"
        return if (type.isNullOrBlank()) base else "$base?$ARG_SERVICE_TYPE=$type"
    }
}
