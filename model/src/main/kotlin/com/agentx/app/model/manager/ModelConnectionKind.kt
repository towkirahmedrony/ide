package com.agentx.app.model.manager

import com.agentx.app.model.connect.ModelSetupKind
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.preset.ModelProviderIds

/**
 * The two kinds of model connection, which follow two different lifecycles.
 *
 * This is the single place the distinction is decided. Nothing below it branches on
 * a provider name or on a setup kind string again: the kind selects a
 * [ModelConnectionManager], and that is what owns the lifecycle rules.
 *
 * - [API]: a hosted provider reached with a credential (`gemini`, `groq`, …). Its
 *   connection is *the saved configuration*, so it is established once and stays
 *   until the user changes or removes it.
 * - [LOCAL_CUSTOM]: an endpoint the user runs (a phone-local server, a Colab or
 *   ngrok/Cloudflare tunnel, any other compatible server). Its connection is *the
 *   live endpoint*, so it may legitimately be absent and is re-established when the
 *   runtime is back.
 */
enum class ModelConnectionKind(val displayName: String) {
    /** A hosted provider, reached with a credential and a provider endpoint. */
    API("API provider"),

    /** Local, Colab, tunnel or any other endpoint the user runs themselves. */
    LOCAL_CUSTOM("Local / Custom"),
}

/**
 * Setup-kind ids that own the API lifecycle.
 *
 * This is the single, explicit declaration of which providers are hosted APIs. It is
 * deliberately *not* derived from a provider's endpoint, its scheme, its hostname or
 * its model name: a provider is an API provider because it is listed here, never
 * because its endpoint happens to be remote. `KnownModelProviders` remains what turns
 * a declared API provider into a *connectable* preset (endpoint, protocol, model
 * list); this set only decides which connection lifecycle a preset owns.
 *
 * Providers declared here but not yet described by `KnownModelProviders` (Cerebras,
 * Mistral, OpenRouter, Cloudflare, NVIDIA NIM) are classified as API without being
 * exposed as first-class connect choices: no endpoint, auth rule or model list is
 * invented for them, so a saved configuration for one is still treated as the
 * persistent API configuration it is, and a temporary failure never releases it.
 *
 * Anything not listed here — including the generic `custom` kind — is an endpoint the
 * user runs, so it keeps the local/custom lifecycle. This is the conservative choice:
 * it never claims a user-run endpoint is a persistent hosted provider.
 */
private val API_SETUP_KINDS: Set<String> = setOf(
    ModelSetupKind.GEMINI.id,
    ModelSetupKind.GROQ.id,
    ModelProviderIds.CEREBRAS,
    ModelProviderIds.MISTRAL,
    ModelProviderIds.OPENROUTER,
    ModelProviderIds.CLOUDFLARE,
    ModelProviderIds.NVIDIA_NIM,
)

/** The explicit connection kind of a persisted setup-kind id. */
private fun connectionKindFor(setupKind: String?): ModelConnectionKind =
    if (setupKind?.trim()?.lowercase() in API_SETUP_KINDS) {
        ModelConnectionKind.API
    } else {
        ModelConnectionKind.LOCAL_CUSTOM
    }

/**
 * The connection kind of a saved preset.
 *
 * Derived from the preset's persisted [ModelPreset.setupKind] through the explicit
 * [API_SETUP_KINDS] declaration. No new field is persisted: adding a provider to that
 * declaration is what gives it the API lifecycle, and removing one puts it back on
 * the local/custom one.
 */
val ModelPreset.connectionKind: ModelConnectionKind
    get() = connectionKindFor(setupKind)

/** The connection kind of a persisted setup kind id, as [ModelPreset.setupKind] stores it. */
val String?.connectionKind: ModelConnectionKind
    get() = connectionKindFor(this)

/** The connection kind of a setup kind the connect/form layer works with. */
val ModelSetupKind.connectionKind: ModelConnectionKind
    get() = connectionKindFor(id)
