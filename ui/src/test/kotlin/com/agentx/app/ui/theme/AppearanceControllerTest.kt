package com.agentx.app.ui.theme

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Settings → Appearance owns one value through [AppearanceController]. These
 * tests pin the contract the app relies on: the default is the appearance
 * AgentX has always shipped, a selection applies immediately and is persisted,
 * and the persisted value survives a restart (a fresh controller over the same
 * store).
 */
class AppearanceControllerTest {

    /** A store that fails every operation, to prove the app degrades safely. */
    private class BrokenStore : ThemeModeStore {
        override suspend fun load(): ThemeMode = error("disk unavailable")
        override suspend fun save(mode: ThemeMode): Unit = error("disk unavailable")
    }

    @Test
    fun `the default mode is dark, the appearance AgentX always shipped`() = runBlocking {
        val controller = AppearanceController()
        assertEquals(ThemeMode.DARK, controller.mode.value)
    }

    @Test
    fun `select applies the mode immediately and persists it`() = runBlocking {
        val store = InMemoryThemeModeStore()
        val controller = AppearanceController(store)

        controller.select(ThemeMode.LIGHT)

        // The UI reads the flow, so the value must be there before the disk write.
        assertEquals(ThemeMode.LIGHT, controller.mode.value)
        assertEquals(ThemeMode.LIGHT, store.saved)
    }

    @Test
    fun `the selection survives a restart through the same store`() = runBlocking {
        val store = InMemoryThemeModeStore()
        AppearanceController(store).select(ThemeMode.SYSTEM)

        // A restart is a fresh controller over the same persisted state.
        val restarted = AppearanceController(store)
        assertEquals(ThemeMode.SYSTEM, restarted.mode.value)
        restarted.restore()

        assertEquals(ThemeMode.SYSTEM, restarted.mode.value)
    }

    @Test
    fun `restore reads the persisted mode`() = runBlocking {
        val store = InMemoryThemeModeStore(ThemeMode.LIGHT)
        val controller = AppearanceController(store)

        controller.restore()

        assertEquals(ThemeMode.LIGHT, controller.mode.value)
    }

    @Test
    fun `a failed restore keeps the default instead of crashing`() = runBlocking {
        val controller = AppearanceController(BrokenStore())

        controller.restore()

        assertEquals(ThemeMode.DARK, controller.mode.value)
    }

    @Test
    fun `a failed save keeps the selection in memory`() = runBlocking {
        val controller = AppearanceController(BrokenStore())

        controller.select(ThemeMode.LIGHT)

        assertEquals(ThemeMode.LIGHT, controller.mode.value)
    }

    @Test
    fun `stored names are stable so an app update keeps the selection`() = runBlocking {
        val store = InMemoryThemeModeStore()
        ThemeMode.entries.forEach { mode ->
            store.save(mode)
            controllerRoundTrip(store, mode)
        }
        // The persisted strings are part of the on-disk format.
        assertEquals("system", ThemeMode.SYSTEM.stored)
        assertEquals("light", ThemeMode.LIGHT.stored)
        assertEquals("dark", ThemeMode.DARK.stored)
    }

    @Test
    fun `an unknown or missing stored value falls back to dark`() {
        assertEquals(ThemeMode.DARK, ThemeMode.fromStored(null))
        assertEquals(ThemeMode.DARK, ThemeMode.fromStored("neon"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored("system"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromStored("light"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromStored("dark"))
    }

    private suspend fun controllerRoundTrip(store: ThemeModeStore, expected: ThemeMode) {
        val controller = AppearanceController(store)
        controller.restore()
        assertEquals(expected, controller.mode.value)
    }
}
