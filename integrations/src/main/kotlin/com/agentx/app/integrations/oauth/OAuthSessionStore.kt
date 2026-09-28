package com.agentx.app.integrations.oauth

import com.agentx.app.integrations.connection.ConnectionId

/**
 * Holds the authorizations that are currently waiting for a provider callback.
 *
 * Pending authorizations are intentionally **memory only**: they contain the PKCE
 * verifier and the `state` value, and losing them on process death is safe (the
 * user simply starts the connection again). Nothing here is persisted, and the
 * store is not shared with any agent-facing surface.
 */
interface OAuthSessionStore {

    fun save(pending: OAuthPendingAuthorization)

    /**
     * Atomically removes the pending authorization for [state] and reports what
     * happened. A state value is single-use, which is what makes callback replay
     * harmless: the second delivery finds no pending authorization.
     */
    fun consume(state: String): OAuthSessionLookup

    /** The authorization in flight for [connectionId], if any. */
    fun pendingFor(connectionId: ConnectionId): OAuthPendingAuthorization?

    /** Drops any pending authorization for [connectionId] (user cancelled). */
    fun cancel(connectionId: ConnectionId)

    /** Pending authorizations, for diagnostics that never print the verifier. */
    fun pending(): List<OAuthPendingAuthorization>
}

/**
 * Default [OAuthSessionStore].
 *
 * Round-trips of a single callback are recognised as [OAuthSessionLookup.AlreadyHandled]
 * so a duplicate delivery cannot trigger a second token exchange, and an unknown
 * `state` never matches a connection at all.
 */
class InMemoryOAuthSessionStore(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxHandledStates: Int = 16,
) : OAuthSessionStore {

    private val pendingByState = LinkedHashMap<String, OAuthPendingAuthorization>()
    /** States that were already consumed, so a replay is recognised as such. */
    private val handledStates = LinkedHashSet<String>()
    private val consumedByState = LinkedHashMap<String, OAuthPendingAuthorization>()

    override fun save(pending: OAuthPendingAuthorization) {
        pendingByState[pending.state] = pending
        handledStates.remove(pending.state)
    }

    override fun consume(state: String): OAuthSessionLookup {
        val pending = pendingByState.remove(state)
        if (pending == null) {
            // An unknown state is never remembered: only states this app issued can
            // be replayed, and a stranger's value must stay unrecognised.
            return if (handledStates.contains(state)) {
                OAuthSessionLookup.AlreadyHandled(consumedByState[state])
            } else {
                OAuthSessionLookup.Unknown
            }
        }
        remember(pending.state, pending)
        return if (pending.isExpired(clock())) {
            OAuthSessionLookup.Expired(pending)
        } else {
            OAuthSessionLookup.Consumed(pending)
        }
    }

    override fun pendingFor(connectionId: ConnectionId): OAuthPendingAuthorization? =
        pendingByState.values.lastOrNull { it.connectionId == connectionId }

    override fun cancel(connectionId: ConnectionId) {
        val state = pendingFor(connectionId)?.state ?: return
        pendingByState.remove(state)
    }

    override fun pending(): List<OAuthPendingAuthorization> = pendingByState.values.toList()

    /**
     * Records that [state] was already delivered, so a second delivery of the same
     * callback is reported as a replay instead of being exchanged twice.
     */
    private fun remember(state: String, pending: OAuthPendingAuthorization) {
        handledStates.add(state)
        consumedByState[state] = pending
        while (handledStates.size > maxHandledStates) {
            val oldest = handledStates.firstOrNull() ?: break
            handledStates.remove(oldest)
            consumedByState.remove(oldest)
        }
    }
}
