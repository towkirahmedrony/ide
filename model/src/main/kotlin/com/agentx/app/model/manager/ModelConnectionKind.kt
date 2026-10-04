package com.agentx.app.model.manager

import com.agentx.app.model.connect.KnownModelProviders
import com.agentx.app.model.connect.ModelSetupKind
import com.agentx.app.model.preset.ModelPreset

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
 * The connection kind of a saved preset.
 *
 * Derived from [ModelPreset.setupKind] through the provider catalogue, which is the
 * project's own declaration of which providers are hosted APIs. A setup kind the
 * catalogue does not know — including the generic `custom` kind — is an endpoint the
 * user runs, so it gets the local/custom lifecycle. Classifying an unknown provider
 * as local/custom is the conservative choice: it never claims a user-run endpoint is
 * a persistent hosted provider.
 *
 * No new field is persisted: adding a provider to the catalogue is what gives it the
 * API lifecycle, and removing one puts it back on the local/custom one.
 */
val ModelPreset.connectionKind: ModelConnectionKind
    get() = setupKind.connectionKind

/** The connection kind of a persisted setup kind id, as [ModelPreset.setupKind] stores it. */
val String?.connectionKind: ModelConnectionKind
    get() = ModelSetupKind.fromId(this).connectionKind

/** The connection kind of a setup kind the connect/form layer works with. */
val ModelSetupKind.connectionKind: ModelConnectionKind
    get() = if (KnownModelProviders.spec(this) == null) {
        ModelConnectionKind.LOCAL_CUSTOM
    } else {
        ModelConnectionKind.API
    }
