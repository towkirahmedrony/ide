package com.agentx.app.ui.ide.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.model.AgentRoleModelRegistry
import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.errorOrNull
import com.agentx.app.model.manager.ModelManager
import com.agentx.app.model.manager.ModelManagerState
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.ratelimit.RateLimitManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * One model's detail page.
 *
 * Holds only what the page shows: the saved preset, which agent roles use it and
 * what has been used through it. Every lifecycle call still goes to the Model
 * Manager, exactly like the model list.
 */
class ModelDetailViewModel(
    private val manager: ModelManager,
    private val roleModels: AgentRoleModelRegistry,
    private val rateLimits: RateLimitManager?,
    private val presetId: String,
) : ViewModel() {

    val managerState: StateFlow<ModelManagerState> = manager.state

    var preset by mutableStateOf<ModelPreset?>(null)
        private set

    var loading by mutableStateOf(true)
        private set

    /** Roles whose configured model is this one. */
    var assignedRoles by mutableStateOf<List<AgentRole>>(emptyList())
        private set

    var usage by mutableStateOf(ModelUsageSummary(local = false))
        private set

    var busy by mutableStateOf(false)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    /** Set once the model is gone, so the screen can leave. */
    var deleted by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch { load() }
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    /** "Use": make this the model the agent runs on. */
    fun use() = operate { manager.selectModel(presetId) }

    fun start() = operate { manager.startModel(presetId) }

    /** "Disconnect": stop this connection without deleting the model. */
    fun stop() = operate { manager.stopModel(presetId) }

    fun reconnect() = operate { manager.reconnectModel(presetId) }

    fun testConnection() = operate { manager.checkModelHealth(presetId) }

    fun delete() {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val failure = manager.deletePreset(presetId).errorOrNull()
                if (failure == null) {
                    deleted = true
                } else {
                    message = failure.message
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                message = error.message ?: "The model could not be deleted"
            } finally {
                busy = false
            }
        }
    }

    fun dismissMessage() {
        message = null
    }

    private suspend fun load() {
        val loaded = manager.preset(presetId)
        preset = loaded
        loading = false
        if (loaded != null) {
            assignedRoles = ModelDetailPresentation.assignedRoles(roleModels, loaded)
            usage = ModelDetailPresentation.usage(rateLimits, loaded)
        }
    }

    private fun operate(block: suspend () -> ForgeResult<*, ForgeError>) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                block().errorOrNull()?.let { failure -> message = failure.message }
                // Status and usage change as a result, so the page is re-read.
                load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                message = error.message ?: "The model operation failed"
            } finally {
                busy = false
            }
        }
    }
}
