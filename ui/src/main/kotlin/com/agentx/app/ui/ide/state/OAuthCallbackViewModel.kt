package com.agentx.app.ui.ide.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentx.app.core.errorOrNull
import com.agentx.app.integrations.connection.ConnectionManager
import com.agentx.app.integrations.connection.ConnectionStatus
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.ui.ide.data.OAuthCallbackInbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Handles the OAuth redirect wherever the user happens to be in the app.
 *
 * It lives above the navigation graph, so a deep link that arrives while another
 * screen is open is still completed and the app can return to Connections. The
 * redirect itself is only an input: `state`, PKCE and single-use semantics are
 * enforced by the manager, so a duplicate or forged redirect cannot succeed here.
 */
class OAuthCallbackViewModel(
    private val manager: ConnectionManager,
    private val inbox: OAuthCallbackInbox,
) : ViewModel() {

    var message by mutableStateOf<String?>(null)
        private set

    var busy by mutableStateOf(false)
        private set

    /**
     * The service the user was authorizing, once its redirect has been handled, so
     * the app can return to that service's details screen.
     */
    var returnToType by mutableStateOf<ConnectionType?>(null)
        private set

    /** Guards against handling the same redirect twice in one process. */
    private var handledUri: String? = null

    init {
        // A redirect that cold-started the app arrived before this collector existed.
        inbox.takePending()?.let { uri -> viewModelScope.launch { handle(uri) } }
        viewModelScope.launch {
            inbox.events.collect { uri -> handle(uri) }
        }
    }

    fun acknowledgeReturn() {
        returnToType = null
    }

    fun dismissMessage() {
        message = null
    }

    private suspend fun handle(uri: String) {
        if (uri.isBlank() || uri == handledUri) return
        handledUri = uri
        inbox.clear()

        busy = true
        // Which service is waiting decides where the user is returned to; it is read
        // before the redirect is handled, because completing it settles that state.
        val awaiting = manager.state.value.connections
            .firstOrNull { it.status == ConnectionStatus.AUTHORIZING }
            ?.type
        try {
            val failure = manager.completeAuthorization(uri).errorOrNull()
            message = failure?.message
            // The page reflects whatever the manager decided, connected or not.
            manager.refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            message = error.message ?: "The authorization could not be completed"
        } finally {
            busy = false
            returnToType = awaiting
        }
    }
}
