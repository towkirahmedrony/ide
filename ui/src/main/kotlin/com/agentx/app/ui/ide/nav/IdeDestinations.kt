package com.agentx.app.ui.ide.nav

/** Route definitions for the IDE shell's single-activity navigation graph. */
object IdeDestinations {

    const val HOME = "home"

    const val WORKSPACE = "workspace/{workspaceId}"
    const val ARG_WORKSPACE_ID = "workspaceId"

    const val SETTINGS = "settings"

    const val SETTINGS_DETAIL = "settings/{sectionId}"
    const val ARG_SECTION_ID = "sectionId"
    const val ARG_PRESET_ID = "presetId"

    const val ABOUT = "about"
    const val DEVELOPER = "developer"

    /** Saved model presets: the Model Manager screen. */
    const val MODELS = "models"

    const val MODEL_EDITOR = "models/editor/{presetId}"
    const val MODEL_RUNNER = "models/runner/{presetId}"

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

    fun settingsDetail(sectionId: String): String = "settings/$sectionId"

    fun modelEditor(presetId: String? = null): String = "models/editor/${presetId ?: NEW_MODEL}"

    fun modelRunner(presetId: String): String = "models/runner/$presetId"

    fun serviceDetails(type: String): String = "connections/service/$type"

    /** The editor can start on a pre-selected service, e.g. adding an MCP server. */
    fun connectionEditor(connectionId: String? = null, type: String? = null): String {
        val base = "connections/editor/${connectionId ?: NEW_CONNECTION}"
        return if (type.isNullOrBlank()) base else "$base?$ARG_SERVICE_TYPE=$type"
    }
}
