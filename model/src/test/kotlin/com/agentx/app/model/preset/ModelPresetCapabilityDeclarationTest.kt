package com.agentx.app.model.preset

import com.agentx.app.core.valueOrNull
import com.agentx.app.model.capability.CapabilitySupport
import com.agentx.app.model.capability.ModelCapability
import com.agentx.app.model.capability.ModelCapabilityDeclaration
import com.agentx.app.model.json.Json
import com.agentx.app.model.json.JsonCodec
import com.agentx.app.model.json.objectOrNull
import com.agentx.app.model.runtime.customPreset
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A capability declaration is worth nothing if it does not survive a restart, so
 * it travels with the preset it belongs to. These cases pin down the round trip
 * and prove that a document which states nothing gains nothing.
 */
class ModelPresetCapabilityDeclarationTest {

    private val devstral = "hf.co/unsloth/Devstral-Small-2-24B-Instruct-2512-GGUF:Q3_K_M"

    private var now = 1_000L
    private var ids = 0

    private val store = InMemoryModelPresetStore()

    private fun repository(): DefaultModelPresetRepository = DefaultModelPresetRepository(
        store = store,
        clock = { now },
        idFactory = { "preset-${++ids}" },
    )

    @Test
    fun `a declared capability survives a save and reload`() = runBlocking {
        val declared = customPreset(model = devstral).copy(
            declaredCapabilities = ModelCapabilityDeclaration.toolEnabledEndpoint(),
        )
        repository().create(declared)

        // A brand new repository over the same persisted store stands in for a
        // process restart.
        val reloaded = assertNotNull(repository().list().single())

        assertEquals(devstral, reloaded.modelIdentifier)
        assertEquals(CapabilitySupport.SUPPORTED, reloaded.declaredCapabilities.toolCalling)
        assertEquals(CapabilitySupport.SUPPORTED, reloaded.declaredCapabilities.streaming)
        assertEquals(
            ModelCapabilityDeclaration.toolEnabledEndpoint(),
            reloaded.declaredCapabilities,
        )
    }

    @Test
    fun `a preset that declares nothing gains no field and stays undeclared`() {
        val json = ModelPresetCodec.encode(customPreset(model = devstral))

        assertTrue(
            !json.contains("declaredCapabilities"),
            "an undeclared preset must not gain a capability field: $json",
        )
        val decoded = assertNotNull(ModelPresetCodec.decode(json))
        assertTrue(decoded.declaredCapabilities.isEmpty)
        assertEquals(CapabilitySupport.UNKNOWN, decoded.declaredCapabilities.toolCalling)
    }

    @Test
    fun `only the capabilities that were stated are persisted`() {
        val declaringOne = customPreset(model = devstral).copy(
            declaredCapabilities = ModelCapabilityDeclaration.of(
                ModelCapability.TOOL_CALLING,
                CapabilitySupport.SUPPORTED,
            ),
        )

        val decoded = assertNotNull(ModelPresetCodec.decode(ModelPresetCodec.encode(declaringOne)))

        assertEquals(CapabilitySupport.SUPPORTED, decoded.declaredCapabilities.toolCalling)
        assertEquals(CapabilitySupport.UNKNOWN, decoded.declaredCapabilities.streaming)
        // The other capabilities were never named, so the document must not claim
        // them for this model.
        assertEquals(setOf(ModelCapability.TOOL_CALLING), decoded.declaredCapabilities.declared.keys)
    }

    @Test
    fun `an unreadable declaration decodes to nothing rather than to a claim`() {
        // A document written by something that does not agree with this build: one
        // value that is not a support state, one capability that is not a
        // capability. Neither may turn into a claim about the model.
        val saved = assertNotNull(
            JsonCodec.parse(ModelPresetCodec.encode(customPreset(model = devstral))).objectOrNull(),
        )
        val tampered = Json.obj(
            saved + (
                "declaredCapabilities" to Json.obj(
                    "toolCalling" to Json.of("MAYBE"),
                    "telepathy" to Json.of("SUPPORTED"),
                )
                ),
        )

        val decoded = assertNotNull(ModelPresetCodec.decode(JsonCodec.encode(tampered)))

        assertTrue(
            decoded.declaredCapabilities.isEmpty,
            "an unreadable claim must not become one: ${decoded.declaredCapabilities}",
        )
    }

    @Test
    fun `a declaration can be withdrawn`() = runBlocking {
        val declared = customPreset(model = devstral).copy(
            declaredCapabilities = ModelCapabilityDeclaration.toolEnabledEndpoint(),
        )
        val stored = assertNotNull(repository().create(declared).valueOrNull())

        repository().update(stored.copy(declaredCapabilities = ModelCapabilityDeclaration.EMPTY))

        val reloaded = assertNotNull(repository().list().single())
        assertTrue(reloaded.declaredCapabilities.isEmpty)
    }
}
