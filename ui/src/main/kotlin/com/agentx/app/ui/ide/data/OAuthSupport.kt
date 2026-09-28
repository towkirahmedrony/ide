package com.agentx.app.ui.ide.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Opens the provider's authorization page in the user's browser.
 *
 * The authorization URL carries the public client id, the redirect URI, the
 * `state` value and the PKCE challenge; it never carries a token or a secret.
 * Implemented by the Android app; previews use [NoOpOAuthBrowserLauncher].
 */
fun interface OAuthBrowserLauncher {

    /** Returns false when no browser could be opened. */
    fun launch(authorizationUrl: String): Boolean
}

/** Launcher used by previews and tests. It never pretends a browser opened. */
object NoOpOAuthBrowserLauncher : OAuthBrowserLauncher {
    override fun launch(authorizationUrl: String): Boolean = false
}

/**
 * Carries the OAuth redirect from the Activity back into the UI layer.
 *
 * The redirect URI contains an authorization code, so it is held in memory only:
 * it is never persisted, logged, or written to a tool result. The app publishes
 * the URI from `onCreate`/`onNewIntent`; the callback owner consumes it exactly
 * once, and the connection manager independently rejects replayed states.
 */
class OAuthCallbackInbox {

    private val mutableEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Emits every redirect URI as it arrives. */
    val events: SharedFlow<String> = mutableEvents

    private var pendingUri: String? = null

    /** Latest URI that arrived before a collector existed (cold start). */
    val pending: String? get() = pendingUri

    /**
     * Publishes a redirect URI. Called on the main thread from the Activity; if no
     * collector is attached yet the URI is kept until [takePending] reads it.
     */
    fun publish(uri: String) {
        if (uri.isBlank()) return
        pendingUri = uri
        mutableEvents.tryEmit(uri)
    }

    /** Returns the last uncollected URI, clearing it. */
    fun takePending(): String? {
        val uri = pendingUri
        pendingUri = null
        return uri
    }

    /** Drops any pending URI after it has been handled. */
    fun clear() {
        pendingUri = null
    }
}
