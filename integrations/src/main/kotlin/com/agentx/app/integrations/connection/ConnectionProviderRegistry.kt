package com.agentx.app.integrations.connection

/**
 * The providers this build can connect to, keyed by [ConnectionType].
 *
 * The Connection Manager depends on this registry only. Adding a service means
 * implementing [ConnectionProvider] and registering it here — the manager, the
 * Connections UI and the Tool System stay untouched.
 *
 * Providers are listed in [ConnectionType] declaration order so the Connections
 * page renders a stable, predictable order.
 */
class ConnectionProviderRegistry(
    providers: Map<ConnectionType, ConnectionProvider> = emptyMap(),
) {

    private val providers: Map<ConnectionType, ConnectionProvider> = LinkedHashMap(providers)

    fun provider(type: ConnectionType): ConnectionProvider? = providers[type]

    fun descriptor(type: ConnectionType): ProviderDescriptor? = providers[type]?.descriptor

    fun registeredTypes(): List<ConnectionType> = ConnectionType.entries.filter { providers.containsKey(it) }

    /** Descriptors of every registered provider, in declaration order. */
    fun descriptors(): List<ProviderDescriptor> = registeredTypes().mapNotNull { providers[it]?.descriptor }

    /**
     * What the Connections page shows for every service: registered or not, and
     * configured or not, with the reason when authorization cannot start.
     */
    fun availability(): List<ProviderAvailability> = ConnectionType.entries.map { availability(it) }

    fun availability(type: ConnectionType): ProviderAvailability {
        val provider = providers[type] ?: return ProviderAvailability(
            type = type,
            displayName = type.displayName,
            description = type.description,
            registered = false,
            configured = false,
            authMethods = listOf(ConnectionAuthMethod.NONE),
            unavailableReason = "No provider is registered for ${type.displayName} in this build.",
        )
        val descriptor = provider.descriptor

        // The provider reports its own state: the registry stays free of OAuth
        // knowledge, so a non-OAuth service is configured as soon as it exists.
        val needsClientConfiguration = descriptor.supportsHostedAuthorization
        return ProviderAvailability(
            type = type,
            displayName = descriptor.displayName,
            description = descriptor.description,
            registered = true,
            configured = !needsClientConfiguration || provider.configured,
            authMethods = descriptor.authMethods,
            unavailableReason = provider.configurationProblem,
        )
    }

    /** Tool catalogs for every registered provider. */
    fun toolCatalogs(): List<ProviderToolCatalog> = registeredTypes()
        .mapNotNull { providers[it]?.toolCatalog() }
        .filter { it.tools.isNotEmpty() }

    /** Tools the given capabilities may enable, across providers. */
    fun toolsFor(type: ConnectionType, capabilities: Set<ConnectionCapability>): List<ProviderToolSpec> =
        providers[type]?.toolCatalog()?.toolsFor(capabilities).orEmpty()

    class Builder {
        private val providers = LinkedHashMap<ConnectionType, ConnectionProvider>()

        fun register(provider: ConnectionProvider): Builder {
            providers[provider.descriptor.type] = provider
            return this
        }

        fun build(): ConnectionProviderRegistry = ConnectionProviderRegistry(providers)
    }

    companion object {
        /** No providers: every connection request is refused, never faked. */
        val EMPTY: ConnectionProviderRegistry = ConnectionProviderRegistry()

        fun builder(): Builder = Builder()
    }
}
