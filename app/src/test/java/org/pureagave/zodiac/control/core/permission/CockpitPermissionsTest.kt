package org.pureagave.zodiac.control.core.permission

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CockpitPermissionsTest {
    @Test
    fun below_android_12_only_location_is_requested() {
        // Bluetooth's runtime permissions don't exist before S; asking returns
        // an instant denial and teaches the user to dismiss our dialogs.
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), requiredCockpitPermissions(API_R))
    }

    @Test
    fun android_12_and_up_adds_the_bluetooth_pair() {
        assertEquals(
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
            ),
            requiredCockpitPermissions(API_S),
        )
    }

    @Test
    fun the_fleet_tablets_both_get_a_supported_set() {
        // Fire HD 10 is API 30, the Samsungs are 36 — the two ends we ship to.
        assertEquals(1, requiredCockpitPermissions(API_FIRE).size)
        assertEquals(BLUETOOTH_ERA_COUNT, requiredCockpitPermissions(API_SAMSUNG).size)
    }

    @Test
    fun nothing_is_requested_when_everything_is_already_held() {
        // The whole point: a fully-granted tablet must not launch a request at
        // all, because an already-held permission comes back `true` and reads
        // as a fresh grant — which was restarting the location source (and on
        // NET, rebinding the multicast socket) on every cold launch.
        val required = requiredCockpitPermissions(API_S)

        val missing = permissionsToRequest(required) { true }

        assertTrue("expected nothing to ask for, got $missing", missing.isEmpty())
    }

    @Test
    fun only_the_missing_permissions_are_requested() {
        val required = requiredCockpitPermissions(API_S)

        val missing = permissionsToRequest(required) { it != Manifest.permission.BLUETOOTH_SCAN }

        assertEquals(listOf(Manifest.permission.BLUETOOTH_SCAN), missing)
    }

    @Test
    fun a_cold_tablet_requests_the_whole_set() {
        val required = requiredCockpitPermissions(API_S)

        assertEquals(required, permissionsToRequest(required) { false })
    }

    @Test
    fun a_result_with_a_grant_is_a_new_grant() {
        assertTrue(grantedAnythingNew(mapOf(Manifest.permission.ACCESS_FINE_LOCATION to true)))
        assertTrue(
            grantedAnythingNew(
                mapOf(
                    Manifest.permission.BLUETOOTH_SCAN to false,
                    Manifest.permission.ACCESS_FINE_LOCATION to true,
                ),
            ),
        )
    }

    @Test
    fun an_all_denied_result_does_not_restart_the_location_source() {
        assertFalse(grantedAnythingNew(mapOf(Manifest.permission.ACCESS_FINE_LOCATION to false)))
        assertFalse(grantedAnythingNew(emptyMap()))
    }

    private companion object {
        const val API_R = 30
        const val API_S = 31
        const val API_FIRE = 30
        const val API_SAMSUNG = 36
        const val BLUETOOTH_ERA_COUNT = 3
    }
}
