package com.agentx.app.ui.ide.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentx.app.ui.theme.AppearanceController
import com.agentx.app.ui.theme.ThemeMode
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Settings → Appearance. A thin state holder over the process-wide
 * [AppearanceController]: the screen observes [mode] and writes selections
 * through [select], which is the same flow [com.agentx.app.ui.theme.ForgeTheme]
 * collects at the root, so a tap restyles the app immediately and is persisted
 * for the next launch. It keeps no copy of the value itself.
 */
class AppearanceViewModel(
    private val appearance: AppearanceController,
) : ViewModel() {

    val mode: StateFlow<ThemeMode> = appearance.mode

    fun select(mode: ThemeMode) {
        viewModelScope.launch { appearance.select(mode) }
    }
}
