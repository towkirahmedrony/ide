package com.agentx.app.settings

import com.agentx.app.skills.SkillDefinition
import com.agentx.app.skills.SkillDocument
import com.agentx.app.skills.SkillFileStore
import com.agentx.app.skills.SkillMarkdown
import com.agentx.app.skills.SkillParseResult
import com.agentx.app.skills.SkillSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Stores user-imported skills on the app-private filesystem in the project's
 * `skills/<id>/SKILL.md` format.
 *
 * Skill files are read and written as plain text and parsed as data; they are
 * never executed. Ids are validated before any path is built, so an import can
 * never escape the skills root.
 */
class FilesystemSkillFileStore(
    private val root: File,
) : SkillFileStore {

    override suspend fun list(): List<SkillDefinition> = withContext(Dispatchers.IO) {
        if (!root.isDirectory) return@withContext emptyList()
        root.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { directory -> readSkill(directory) }
    }

    override suspend fun write(skill: SkillDefinition) = withContext(Dispatchers.IO) {
        if (!SkillMarkdown.isSafeId(skill.id)) return@withContext
        val directory = File(root, skill.id)
        directory.mkdirs()
        File(directory, SkillMarkdown.FILE_NAME).writeText(SkillMarkdown.encode(skill))
    }

    override suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        if (!SkillMarkdown.isSafeId(id)) return@withContext false
        val directory = File(root, id)
        val safe = directory.canonicalPath.startsWith(root.canonicalPath + File.separator)
        if (safe && directory.exists()) directory.deleteRecursively() else false
    }

    private fun readSkill(directory: File): SkillDefinition? {
        val file = File(directory, SkillMarkdown.FILE_NAME)
        if (!file.isFile) return null
        val document = SkillDocument(
            path = "skills/${directory.name}/${SkillMarkdown.FILE_NAME}",
            content = file.readText(),
        )
        return when (val parsed = SkillMarkdown.parse(document, directory.name)) {
            is SkillParseResult.Valid -> parsed.skill.copy(source = SkillSource.IMPORTED)
            is SkillParseResult.Invalid -> null
        }
    }
}
