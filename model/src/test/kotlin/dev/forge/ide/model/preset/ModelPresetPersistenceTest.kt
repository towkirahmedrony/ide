package dev.forge.ide.model.preset

import dev.forge.ide.core.ForgeErrorCode
import dev.forge.ide.core.errorOrNull
import dev.forge.ide.core.valueOrNull
import dev.forge.ide.model.runtime.colabPreset
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Preset CRUD and persistence. A new repository is built over the same store to
 * stand in for a process restart, so "survives a restart" is exercised rather
 * than assumed.
 */
class ModelPresetPersistenceTest {

    private var now = 1_000L
    private var ids = 0

    private val store = InMemoryModelPresetStore()

    private fun repository(): DefaultModelPresetRepository = DefaultModelPresetRepository(
        store = store,
        clock = { now },
        idFactory = { "preset-${++ids}" },
    )

    @Test
    fun `creating a preset stores it with an identity and timestamps`() = runBlocking {
        val repository = repository()

        // An empty id is what the Add Model form produces; the repository assigns one.
        val created = assertNotNull(repository.create(colabPreset().copy(id = "")).valueOrNull())

        assertEquals("preset-1", created.id)
        assertEquals(1_000L, created.createdAtMillis)
        assertEquals(1_000L, created.updatedAtMillis)
        assertEquals(1, repository.list().size)
    }

    @Test
    fun `an invalid preset is refused with the field problems`() = runBlocking {
        val repository = repository()

        val result = repository.create(colabPreset().copy(displayName = "", modelIdentifier = ""))

        val error = assertNotNull(result.errorOrNull())
        assertEquals(ForgeErrorCode.MODEL_PRESET_INVALID, error.code)
        val errors = assertNotNull(error.details["errors"] as? List<*>)
        assertTrue(errors.any { it.toString().contains("Name") })
        assertTrue(errors.any { it.toString().contains("Model identifier") })
        assertTrue(repository.list().isEmpty())
    }

    @Test
    fun `saved presets survive a restart`() = runBlocking {
        repository().create(colabPreset(id = "qwen"))
        repository().create(colabPreset(id = "llama", name = "Llama"))

        // A brand new repository over the same persisted store.
        val restarted = repository()

        assertEquals(listOf("Llama", "Qwen"), restarted.list().map { it.displayName }.sorted())
        assertEquals("qwen", assertNotNull(restarted.find("qwen")).id)
    }

    @Test
    fun `updating a preset keeps the creation time and refreshes the modification time`() = runBlocking {
        val repository = repository()
        val original = colabPreset()
        repository.create(original)

        now = 5_000L
        val edit = original.copy(displayName = "Qwen 2.5", modelIdentifier = "qwen2.5-coder-32b")
        val updated = assertNotNull(repository.update(edit).valueOrNull())

        assertEquals("Qwen 2.5", updated.displayName)
        assertEquals("qwen2.5-coder-32b", updated.modelIdentifier)
        assertEquals(1_000L, updated.createdAtMillis)
        assertEquals(5_000L, updated.updatedAtMillis)
    }

    @Test
    fun `updating an unknown preset fails`() = runBlocking {
        val error = assertNotNull(repository().update(colabPreset(id = "missing")).errorOrNull())

        assertEquals(ForgeErrorCode.MODEL_PRESET_NOT_FOUND, error.code)
    }

    @Test
    fun `deleting a preset removes it`() = runBlocking {
        val repository = repository()
        repository.create(colabPreset(id = "qwen"))
        repository.create(colabPreset(id = "llama", name = "Llama"))

        repository.delete("qwen")

        assertEquals(listOf("llama"), repository.list().map { it.id })
        assertNull(repository.find("qwen"))
    }

    @Test
    fun `deleting an unknown preset fails`() = runBlocking {
        val error = assertNotNull(repository().delete("missing").errorOrNull())

        assertEquals(ForgeErrorCode.MODEL_PRESET_NOT_FOUND, error.code)
    }

    @Test
    fun `selecting a model is remembered and cleared when it is deleted`() = runBlocking {
        val repository = repository()
        repository.create(colabPreset(id = "qwen"))
        repository.create(colabPreset(id = "llama", name = "Llama"))

        repository.setActiveId("llama")
        assertEquals("llama", repository.activeId())

        repository.delete("llama")
        assertNull(repository.activeId())
    }

    @Test
    fun `selecting an unknown model fails`() = runBlocking {
        val error = assertNotNull(repository().setActiveId("missing").errorOrNull())

        assertEquals(ForgeErrorCode.MODEL_PRESET_NOT_FOUND, error.code)
    }

    @Test
    fun `last known status is stored per preset`() = runBlocking {
        val repository = repository()
        repository.create(colabPreset(id = "qwen"))
        repository.setLastStatus("qwen", "ONLINE")

        assertEquals("ONLINE", repository.lastStatus("qwen"))
        assertNull(repository.lastStatus("llama"))
    }

    // --- serialization -----------------------------------------------------

    @Test
    fun `a preset round trips through json`() {
        val preset = colabPreset(id = "qwen").copy(
            credentialRef = "model-credential-1",
            startupScript = "!pip install vllm\n!python -m vllm.entrypoints.openai.api_server --port 8000",
            serverPort = 8000,
            endpoint = EndpointConfig(EndpointDiscoveryMode.RUNTIME_OUTPUT),
            tunnel = TunnelConfig(TunnelType.CLOUDFLARE_QUICK, "FORGE_ENDPOINT="),
            health = HealthCheckConfig(path = "/v1/models", requireModelInList = false),
        )

        val decoded = ModelPresetCodec.decode(ModelPresetCodec.encode(preset))

        assertEquals(preset, decoded)
    }

    @Test
    fun `a collection of presets round trips through json`() {
        val presets = listOf(
            colabPreset(id = "qwen"),
            colabPreset(id = "llama", name = "Llama"),
            colabPreset(id = "deepseek", name = "DeepSeek"),
        )

        val decoded = ModelPresetCodec.decodeAll(ModelPresetCodec.encodeAll(presets))

        assertEquals(presets, decoded)
    }

    @Test
    fun `unknown json fields are ignored and unusable entries are dropped`() {
        val unknownField = ModelPresetCodec.encode(colabPreset())
            .replaceFirst("{", """{"somethingFromTheFuture":true,""")

        assertEquals("qwen", assertNotNull(ModelPresetCodec.decode(unknownField)).id)
        assertNull(ModelPresetCodec.decode("""{"id":"x","providerType":"TELEPATHY","displayName":"Nope"}"""))
        assertNull(ModelPresetCodec.decode("not json"))
        assertTrue(ModelPresetCodec.decodeAll("""[{"broken":true}]""").isEmpty())
    }

    @Test
    fun `serialized presets contain a credential reference but never a secret`() {
        val preset = colabPreset(id = "qwen").copy(credentialRef = "model-credential-1")

        val json = ModelPresetCodec.encode(preset)

        assertTrue(json.contains("model-credential-1"), "the reference is what gets persisted")
        assertFalse(json.contains("sk-"), "no credential material is stored")
    }
}
