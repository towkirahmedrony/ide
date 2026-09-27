package com.agentx.app.core.di

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode

/**
 * Minimal service locator used to wire modules together at startup. Intended to
 * be replaced by a full dependency-injection setup once the platform grows.
 */
class ServiceContainer {

    private val services = LinkedHashMap<String, Any>()

    fun register(key: String, value: Any) {
        if (services.containsKey(key)) {
            throw ForgeError(ForgeErrorCode.DUPLICATE_SERVICE, "Service '$key' is already registered")
        }
        services[key] = value
    }

    fun has(key: String): Boolean = services.containsKey(key)

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String): T? = services[key] as T?

    fun require(key: String): Any =
        services[key] ?: throw ForgeError(ForgeErrorCode.SERVICE_NOT_FOUND, "Service '$key' is not registered")

    fun keys(): Set<String> = services.keys
}
