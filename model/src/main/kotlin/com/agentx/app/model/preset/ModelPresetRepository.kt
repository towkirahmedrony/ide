package com.agentx.app.model.preset

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.success
import java.util.UUID

/**
 * CRUD over saved model presets. Validation happens here, once, so the manager
 * and the UI both work with presets that are already known to be usable.
 */
interface ModelPresetRepository {
    suspend fun list(): List<ModelPreset>

    suspend fun find(id: String): ModelPreset?

    /** Stores a new preset, assigning id and timestamps. */
    suspend fun create(preset: ModelPreset): ForgeResult<ModelPreset, ForgeError>

    /** Replaces an existing preset, preserving its creation time. */
    suspend fun update(preset: ModelPreset): ForgeResult<ModelPreset, ForgeError>

    suspend fun delete(id: String): ForgeResult<Unit, ForgeError>

    suspend fun activeId(): String?

    suspend fun setActiveId(id: String?): ForgeResult<Unit, ForgeError>

    /** Last known lifecycle state name, so the UI has something to show at start. */
    suspend fun lastStatus(presetId: String): String?

    suspend fun setLastStatus(presetId: String, state: String)
}

class DefaultModelPresetRepository(
    private val store: ModelPresetStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : ModelPresetRepository {

    override suspend fun list(): List<ModelPreset> = store.load()
        .sortedWith(compareBy({ it.createdAtMillis }, { it.displayName.lowercase() }))

    override suspend fun find(id: String): ModelPreset? = store.load().firstOrNull { it.id == id }

    override suspend fun create(preset: ModelPreset): ForgeResult<ModelPreset, ForgeError> {
        invalid(preset)?.let { return failure(it) }

        val now = clock()
        val stored = preset.copy(
            id = preset.id.takeIf { it.isNotBlank() } ?: idFactory(),
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        store.save(stored)
        return success(stored)
    }

    override suspend fun update(preset: ModelPreset): ForgeResult<ModelPreset, ForgeError> {
        val existing = find(preset.id)
            ?: return failure(
                ForgeError(
                    code = ForgeErrorCode.MODEL_PRESET_NOT_FOUND,
                    message = "No model preset with id '${preset.id}'",
                    details = mapOf("presetId" to preset.id),
                ),
            )
        invalid(preset)?.let { return failure(it) }

        val stored = preset.copy(
            createdAtMillis = existing.createdAtMillis,
            updatedAtMillis = clock(),
        )
        store.save(stored)
        return success(stored)
    }

    override suspend fun delete(id: String): ForgeResult<Unit, ForgeError> {
        if (find(id) == null) {
            return failure(
                ForgeError(
                    code = ForgeErrorCode.MODEL_PRESET_NOT_FOUND,
                    message = "No model preset with id '$id'",
                    details = mapOf("presetId" to id),
                ),
            )
        }
        // Deleting the selected model must not leave a dangling selection.
        if (store.activeId() == id) store.setActiveId(null)
        store.delete(id)
        return success(Unit)
    }

    override suspend fun activeId(): String? = store.activeId()

    override suspend fun setActiveId(id: String?): ForgeResult<Unit, ForgeError> {
        if (id != null && find(id) == null) {
            return failure(
                ForgeError(
                    code = ForgeErrorCode.MODEL_PRESET_NOT_FOUND,
                    message = "No model preset with id '$id'",
                    details = mapOf("presetId" to id),
                ),
            )
        }
        store.setActiveId(id)
        return success(Unit)
    }

    override suspend fun lastStatus(presetId: String): String? = store.lastStatus(presetId)

    override suspend fun setLastStatus(presetId: String, state: String) {
        store.setLastStatus(presetId, state)
    }

    private fun invalid(preset: ModelPreset): ForgeError? {
        val errors = preset.validate()
        if (errors.isEmpty()) return null
        return ForgeError(
            code = ForgeErrorCode.MODEL_PRESET_INVALID,
            message = "Model preset is invalid: ${errors.joinToString("; ")}",
            details = mapOf("errors" to errors),
        )
    }
}
