package dev.forge.ide.ui.ide.nav

/** Route definitions for the IDE shell's single-activity navigation graph. */
object IdeDestinations {

    const val HOME = "home"

    const val WORKSPACE = "workspace/{workspaceId}"
    const val ARG_WORKSPACE_ID = "workspaceId"

    const val SETTINGS = "settings"

    const val SETTINGS_DETAIL = "settings/{sectionId}"
    const val ARG_SECTION_ID = "sectionId"

    const val ABOUT = "about"
    const val DEVELOPER = "developer"

    fun workspace(workspaceId: String): String = "workspace/$workspaceId"

    fun settingsDetail(sectionId: String): String = "settings/$sectionId"
}
