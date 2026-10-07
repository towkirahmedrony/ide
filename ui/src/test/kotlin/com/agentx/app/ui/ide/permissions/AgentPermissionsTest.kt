package com.agentx.app.ui.ide.permissions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Permissions screen's status rules, exercised off-device. These pin down the version
 * boundaries and the denied/permanently-denied/unavailable distinctions the screen renders.
 */
class AgentPermissionsTest {

    private fun resolve(
        permission: AgentPermission,
        sdkInt: Int,
        granted: Boolean,
        runtimeGrantable: Boolean = false,
        canAskAgain: Boolean = false,
    ) = permission.resolve(
        PermissionSignals(
            sdkInt = sdkInt,
            granted = granted,
            runtimeGrantable = runtimeGrantable,
            canAskAgain = canAskAgain,
        ),
    )

    @Test
    fun `a normal permission is granted at install with no action`() {
        val resolution = resolve(AgentPermission.INTERNET, sdkInt = 35, granted = true)

        assertEquals(PermissionStatus.GRANTED, resolution.status)
        assertEquals(PermissionAction.NONE, resolution.action)
    }

    @Test
    fun `a normal permission the platform refuses is reported unavailable`() {
        val resolution = resolve(AgentPermission.NETWORK_STATE, sdkInt = 35, granted = false)

        assertEquals(PermissionStatus.UNAVAILABLE, resolution.status)
        assertEquals(PermissionAction.NONE, resolution.action)
    }

    @Test
    fun `a normal permission is not applicable below the version that introduced it`() {
        val resolution = resolve(
            AgentPermission.FOREGROUND_SERVICE_DATA_SYNC,
            sdkInt = 33,
            granted = true,
        )

        assertEquals(PermissionStatus.NOT_APPLICABLE, resolution.status)
        assertEquals(PermissionAction.NONE, resolution.action)
    }

    @Test
    fun `the foreground service type is granted on Android 14`() {
        val resolution = resolve(
            AgentPermission.FOREGROUND_SERVICE_DATA_SYNC,
            sdkInt = 34,
            granted = true,
        )

        assertEquals(PermissionStatus.GRANTED, resolution.status)
    }

    @Test
    fun `all files access is not applicable below Android 11`() {
        val resolution = resolve(AgentPermission.ALL_FILES_ACCESS, sdkInt = 29, granted = false)

        assertEquals(PermissionStatus.NOT_APPLICABLE, resolution.status)
        assertEquals(PermissionAction.NONE, resolution.action)
    }

    @Test
    fun `a denied special access points at system settings`() {
        val resolution = resolve(AgentPermission.ALL_FILES_ACCESS, sdkInt = 30, granted = false)

        assertEquals(PermissionStatus.DENIED, resolution.status)
        assertEquals(PermissionAction.OPEN_SETTINGS, resolution.action)
    }

    @Test
    fun `granted all files access needs no action`() {
        val resolution = resolve(AgentPermission.ALL_FILES_ACCESS, sdkInt = 34, granted = true)

        assertEquals(PermissionStatus.GRANTED, resolution.status)
        assertEquals(PermissionAction.NONE, resolution.action)
    }

    @Test
    fun `a runtime permission can be requested while Android still asks`() {
        val resolution = resolve(
            AgentPermission.NOTIFICATIONS,
            sdkInt = 33,
            granted = false,
            runtimeGrantable = true,
            canAskAgain = true,
        )

        assertEquals(PermissionStatus.DENIED, resolution.status)
        assertEquals(PermissionAction.REQUEST, resolution.action)
    }

    @Test
    fun `a runtime permission reports don't-ask-again once Android stops asking`() {
        val resolution = resolve(
            AgentPermission.NOTIFICATIONS,
            sdkInt = 33,
            granted = false,
            runtimeGrantable = true,
            canAskAgain = false,
        )

        assertEquals(PermissionStatus.PERMANENTLY_DENIED, resolution.status)
        assertEquals(PermissionAction.OPEN_SETTINGS, resolution.action)
    }

    @Test
    fun `notifications report granted only when the app may actually post them`() {
        val granted = notificationsResolution(
            sdkInt = 33,
            permissionGranted = true,
            notificationsEnabled = true,
            canAskAgain = false,
        )
        val channelOff = notificationsResolution(
            sdkInt = 33,
            permissionGranted = true,
            notificationsEnabled = false,
            canAskAgain = false,
        )

        assertEquals(PermissionStatus.GRANTED, granted.status)
        assertEquals(PermissionAction.NONE, granted.action)
        assertEquals(PermissionStatus.DENIED, channelOff.status)
        assertEquals(PermissionAction.OPEN_SETTINGS, channelOff.action)
    }

    @Test
    fun `below Android 13 notifications follow the system setting, not a dialog`() {
        val on = notificationsResolution(
            sdkInt = 30,
            permissionGranted = false,
            notificationsEnabled = true,
            canAskAgain = true,
        )
        val off = notificationsResolution(
            sdkInt = 30,
            permissionGranted = false,
            notificationsEnabled = false,
            canAskAgain = true,
        )

        assertEquals(PermissionStatus.GRANTED, on.status)
        assertEquals(PermissionAction.NONE, on.action)
        assertEquals(PermissionStatus.DENIED, off.status)
        assertEquals(PermissionAction.OPEN_SETTINGS, off.action)
    }

    @Test
    fun `every listed permission is a real declared Android permission with a purpose`() {
        AgentPermission.entries.forEach { permission ->
            assertTrue(
                permission.androidName.startsWith("android.permission."),
                "${permission.name} must name a real manifest permission",
            )
            assertTrue(permission.label.isNotBlank(), "${permission.name} needs a label")
            assertTrue(permission.feature.isNotBlank(), "${permission.name} needs a feature")
            assertTrue(permission.purpose.isNotBlank(), "${permission.name} needs a purpose")
        }
    }

    @Test
    fun `only the declared runtime permission carries a runtime API level`() {
        val runtime = AgentPermission.entries.filter { it.category == PermissionCategory.RUNTIME }

        assertEquals(
            listOf(AgentPermission.NOTIFICATIONS),
            runtime,
            "POST_NOTIFICATIONS is the only runtime permission AgentX declares",
        )
        assertTrue(runtime.all { it.runtimeSdk != null }, "a runtime permission needs its API level")
    }
}
