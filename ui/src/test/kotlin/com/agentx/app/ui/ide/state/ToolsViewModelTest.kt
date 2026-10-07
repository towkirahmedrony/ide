package com.agentx.app.ui.ide.state

import com.agentx.app.tools.DefaultToolPreferences
import com.agentx.app.tools.DefaultToolRegistry
import com.agentx.app.tools.InMemoryToolPreferenceStore
import com.agentx.app.tools.Tool
import com.agentx.app.tools.ToolCapability
import com.agentx.app.tools.ToolCategory
import com.agentx.app.tools.ToolDefinition
import com.agentx.app.tools.ToolExecutionContext
import com.agentx.app.tools.ToolInput
import com.agentx.app.tools.ToolOutput
import com.agentx.app.tools.ToolPermissionDecision
import com.agentx.app.tools.ToolPermissionLevel
import com.agentx.app.tools.extensions.BrowserToolStub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Settings → Tools derives every card from the registry and writes the live
 * enablement, so a change on the screen reaches the Tool Router. These tests use
 * a real registry and the real preference set.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolsViewModelTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeTool(
        name: String,
        category: ToolCategory,
        mutating: Boolean,
    ) : Tool {
        override val definition = ToolDefinition(
            name = name,
            title = name.replace('_', ' '),
            description = "Performs $name.",
            permission = if (mutating) ToolPermissionDecision.ASK else ToolPermissionDecision.ALLOW,
            capabilities = if (mutating) setOf(ToolCapability.MUTATING) else setOf(ToolCapability.READ_ONLY),
            requiredPermissions = if (mutating) {
                setOf(ToolPermissionLevel.WORKSPACE_WRITE)
            } else {
                setOf(ToolPermissionLevel.READ_ONLY)
            },
            category = category,
        )

        override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput = ToolOutput()
    }

    private fun registry() = DefaultToolRegistry().apply {
        register(FakeTool("read_file", ToolCategory.FILESYSTEM, mutating = false))
        register(FakeTool("write_file", ToolCategory.FILESYSTEM, mutating = true))
        register(FakeTool("web_search", ToolCategory.WEB, mutating = false))
        register(BrowserToolStub())
    }

    private fun viewModel(
        store: InMemoryToolPreferenceStore = InMemoryToolPreferenceStore(),
    ): Pair<ToolsViewModel, DefaultToolPreferences> {
        val prefs = DefaultToolPreferences(store)
        return ToolsViewModel(registry(), prefs) to prefs
    }

    @Test
    fun `the catalog lists real tools and marks the unavailable one`() {
        val (vm, _) = viewModel()

        assertEquals(4, vm.tools.size)
        assertEquals(3, vm.enabledCount)
        assertEquals(1, vm.unavailableCount)

        val browser = vm.tools.first { it.id == "browser" }
        assertEquals(ToolStatus.UNAVAILABLE, browser.status)
        assertFalse(browser.available)
        assertFalse(browser.editable, "an unavailable tool must not be toggleable")

        val read = vm.tools.first { it.id == "read_file" }
        assertEquals(ToolStatus.ENABLED, read.status)
        assertEquals("Filesystem", read.categoryLabel)
    }

    @Test
    fun `disabling a tool updates its state and persists the choice`() {
        val store = InMemoryToolPreferenceStore()
        val (vm, prefs) = viewModel(store)

        vm.setEnabled("web_search", false)

        assertEquals(ToolStatus.DISABLED, vm.tools.first { it.id == "web_search" }.status)
        assertFalse(prefs.isEnabled("web_search"))
        assertEquals(setOf("web_search"), runBlocking { store.load() })

        vm.setEnabled("web_search", true)
        assertTrue(prefs.isEnabled("web_search"))
        assertTrue(runBlocking { store.load() }.isEmpty())
    }

    @Test
    fun `search and filters narrow the list`() {
        val (vm, _) = viewModel()

        vm.setQuery("web")
        assertEquals(listOf("web_search"), vm.filtered.map { it.id })

        vm.setQuery("")
        vm.setCategoryFilter(ToolCategory.FILESYSTEM)
        assertEquals(listOf("read_file", "write_file"), vm.filtered.map { it.id })

        vm.setCategoryFilter(null)
        vm.setStatusFilter(ToolFilter.UNAVAILABLE)
        assertEquals(listOf("browser"), vm.filtered.map { it.id })
        assertEquals(1, vm.grouped.size)
    }

    @Test
    fun `a preview without a registry reports the tool system as unavailable`() {
        val vm = ToolsViewModel(registry = null, preferences = null)

        assertFalse(vm.available)
        assertTrue(vm.tools.isEmpty())
    }
}
