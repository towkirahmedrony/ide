package dev.forge.ide.ui.ide.data.mock

import dev.forge.ide.ui.ide.data.AgentSession
import dev.forge.ide.ui.ide.data.AgentStreamEvent
import dev.forge.ide.ui.ide.data.GitRepository
import dev.forge.ide.ui.ide.data.ProjectCatalog
import dev.forge.ide.ui.ide.data.TerminalSession
import dev.forge.ide.ui.ide.data.WorkspaceFileSource
import dev.forge.ide.ui.ide.model.AgentActivity
import dev.forge.ide.ui.ide.model.AgentActivityStatus
import dev.forge.ide.ui.ide.model.FileNode
import dev.forge.ide.ui.ide.model.FileNodeKind
import dev.forge.ide.ui.ide.model.GitChange
import dev.forge.ide.ui.ide.model.GitSnapshot
import dev.forge.ide.ui.ide.model.ProjectSummary
import dev.forge.ide.ui.ide.model.TerminalLine
import dev.forge.ide.ui.ide.model.TerminalLineKind
import dev.forge.ide.ui.ide.model.TerminalResult
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * In-memory stand-ins for the not-yet-implemented runtime layers. They let the
 * IDE shell be navigated and demonstrated end-to-end. Replace these bindings in
 * [dev.forge.ide.ui.ide.IdeDependencies] when the real layers land; the UI does
 * not change.
 */

private class MockState {
    val projects = LinkedHashMap<String, ProjectSummary>()
    val trees = HashMap<String, List<FileNode>>()
    val contents = HashMap<String, String>()
}

private val state = MockState()

object MockWorkspaceData {

    private fun node(path: String, children: List<FileNode> = emptyList()): FileNode {
        val name = path.substringAfterLast('/')
        return FileNode(
            path = path,
            name = name,
            kind = if (children.isEmpty()) FileNodeKind.FILE else FileNodeKind.DIRECTORY,
            children = children,
        )
    }

    fun seed() {
        if (state.projects.isNotEmpty()) return
        val project = ProjectSummary(
            id = "forge-ide",
            name = "forge-ide",
            rootPath = "/workspaces/forge-ide",
            branch = "main",
            lastOpenedLabel = "2h ago",
            fileCount = 8,
        )
        state.projects[project.id] = project
        state.trees[project.id] = sampleTree()
        state.contents.putAll(sampleContents())

        val notes = ProjectSummary(
            id = "notes-api",
            name = "notes-api",
            rootPath = "/workspaces/notes-api",
            branch = "develop",
            lastOpenedLabel = "yesterday",
            fileCount = 3,
        )
        state.projects[notes.id] = notes
        state.trees[notes.id] = listOf(
            node(
                "src",
                listOf(
                    node("src/main.kt"),
                    node("src/routes.kt"),
                ),
            ),
            node("notes.md"),
        )
        state.contents["src/main.kt"] = "fun main() {\n    println(\"notes-api\")\n}\n"
        state.contents["src/routes.kt"] = "// route definitions land here\n"
        state.contents["notes.md"] = "# notes-api\n\nA small sample project.\n"
    }

    private fun sampleTree(): List<FileNode> = listOf(
        node(
            "app",
            listOf(
                node("app/MainActivity.kt"),
                node("app/build.gradle.kts"),
            ),
        ),
        node(
            "core",
            listOf(node("core/Foundation.kt")),
        ),
        node(
            "ui",
            listOf(
                node(
                    "ui/theme",
                    listOf(node("ui/theme/Color.kt")),
                ),
                node("ui/FoundationScreen.kt"),
            ),
        ),
        node("README.md"),
        node("settings.gradle.kts"),
    )

    private fun sampleContents(): Map<String, String> = mapOf(
        "app/MainActivity.kt" to """
            |package dev.forge.ide
            |
            |import android.os.Bundle
            |import androidx.activity.ComponentActivity
            |
            |class MainActivity : ComponentActivity() {
            |    override fun onCreate(savedInstanceState: Bundle?) {
            |        super.onCreate(savedInstanceState)
            |    }
            |}
            |""".trimMargin(),
        "app/build.gradle.kts" to """
            |// Android application module.
            |plugins {
            |    alias(libs.plugins.android.application)
            |}
            |""".trimMargin(),
        "core/Foundation.kt" to """
            |package dev.forge.ide.core
            |
            |object Foundation {
            |    const val name = "Forge"
            |}
            |""".trimMargin(),
        "ui/theme/Color.kt" to """
            |package dev.forge.ide.ui.theme
            |
            |import androidx.compose.ui.graphics.Color
            |
            |val ForgeCanvas = Color(0xFF07080B)
            |""".trimMargin(),
        "ui/FoundationScreen.kt" to "// foundation status screen\n",
        "README.md" to "# forge-ide\n\nAn Android-first, agentic AI IDE.\n",
        "settings.gradle.kts" to "rootProject.name = \"Forge\"\n",
    )
}

class InMemoryProjectCatalog : ProjectCatalog {

    init {
        MockWorkspaceData.seed()
    }

    override suspend fun recentProjects(): List<ProjectSummary> = state.projects.values.toList()

    override suspend fun createWorkspace(name: String): ProjectSummary {
        val id = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            .ifBlank { "workspace" } + "-" + UUID.randomUUID().toString().take(4)
        val project = ProjectSummary(
            id = id,
            name = name.trim().ifBlank { "untitled" },
            rootPath = "/workspaces/$id",
            branch = null,
            lastOpenedLabel = "just now",
            fileCount = 0,
        )
        state.projects[project.id] = project
        state.trees[project.id] = emptyList()
        return project
    }

    override suspend fun find(workspaceId: String): ProjectSummary? = state.projects[workspaceId]
}

class InMemoryWorkspaceFileSource : WorkspaceFileSource {

    init {
        MockWorkspaceData.seed()
    }

    override suspend fun fileTree(workspaceId: String): List<FileNode> = state.trees[workspaceId].orEmpty()

    override suspend fun readFile(path: String): String = state.contents[path] ?: ""

    override suspend fun writeFile(path: String, content: String) {
        state.contents[path] = content
    }
}

/** Returns a canned response so the agent panel can be demonstrated. */
class MockAgentSession : AgentSession {

    override suspend fun run(input: String, onEvent: (AgentStreamEvent) -> Unit) {
        onEvent(AgentStreamEvent.Activity(AgentActivity(AgentActivityStatus.THINKING, "Thinking")))
        delay(700)

        onEvent(
            AgentStreamEvent.Activity(
                AgentActivity(AgentActivityStatus.USING_TOOL, "Using tool · read_file"),
            ),
        )
        delay(800)

        onEvent(
            AgentStreamEvent.Activity(
                AgentActivity(AgentActivityStatus.WAITING, "Waiting for model…"),
            ),
        )
        delay(600)

        val reply = buildString {
            append("Mock agent received: \"")
            append(input.take(120))
            append("\".\n\n")
            append("The Agent Core is not implemented yet, so this reply is generated ")
            append("locally to demonstrate the panel. Connect a model provider and the ")
            append("real loop will replace this without any UI changes.")
        }

        reply.chunked(24).forEach { chunk ->
            onEvent(AgentStreamEvent.Chunk(chunk))
            delay(28)
        }

        onEvent(AgentStreamEvent.Activity(AgentActivity(AgentActivityStatus.COMPLETED, "Completed")))
        onEvent(AgentStreamEvent.Completed(reply))
    }
}

/** Returns mock output. Never touches a real shell. */
class MockTerminalSession : TerminalSession {

    override suspend fun run(workspaceId: String, command: String): TerminalResult {
        val trimmed = command.trim()
        val lines = mutableListOf<TerminalLine>()

        lines += TerminalLine(
            id = UUID.randomUUID().toString(),
            text = "\$ ${trimmed}",
            kind = TerminalLineKind.INPUT,
        )

        delay(220)

        lines += TerminalLine(
            id = UUID.randomUUID().toString(),
            text = "[mock] real shell execution is not available yet",
            kind = TerminalLineKind.SYSTEM,
        )

        when {
            trimmed.isEmpty() -> Unit
            trimmed == "ls" || trimmed == "dir" -> {
                state.trees[workspaceId].orEmpty().forEach { node ->
                    lines += TerminalLine(
                        id = UUID.randomUUID().toString(),
                        text = node.name + if (node.isDirectory) "/" else "",
                        kind = TerminalLineKind.OUTPUT,
                    )
                }
            }

            trimmed.startsWith("echo ") -> {
                lines += TerminalLine(
                    id = UUID.randomUUID().toString(),
                    text = trimmed.removePrefix("echo "),
                    kind = TerminalLineKind.OUTPUT,
                )
            }

            trimmed == "pwd" -> {
                val root = state.projects[workspaceId]?.rootPath ?: "/workspaces"
                lines += TerminalLine(
                    id = UUID.randomUUID().toString(),
                    text = root,
                    kind = TerminalLineKind.OUTPUT,
                )
            }

            else -> {
                lines += TerminalLine(
                    id = UUID.randomUUID().toString(),
                    text = "command not executed (mock terminal): $trimmed",
                    kind = TerminalLineKind.ERROR,
                )
            }
        }

        return TerminalResult(lines = lines, exitCode = 0)
    }
}

/** Repository status is mocked per workspace; newly created ones have no repo. */
class MockGitRepository : GitRepository {

    override suspend fun snapshot(workspaceId: String): GitSnapshot {
        // A freshly created workspace has no repository yet.
        val branch = state.projects[workspaceId]?.branch
        if (branch == null) {
            return GitSnapshot(available = false)
        }
        return GitSnapshot(
            available = true,
            branch = branch,
            changes = listOf(
                GitChange("app/MainActivity.kt", "modified"),
                GitChange("ui/FoundationScreen.kt", "modified"),
                GitChange("notes/todo.md", "untracked"),
            ),
            diff = """
                |--- a/app/MainActivity.kt
                |+++ b/app/MainActivity.kt
                |@@ -1,6 +1,8 @@
                | class MainActivity : ComponentActivity() {
                |     override fun onCreate(savedInstanceState: Bundle?) {
                |         super.onCreate(savedInstanceState)
                |+        setContent { ForgeIdeApp() }
                |     }
                | }
                |""".trimMargin(),
        )
    }
}
