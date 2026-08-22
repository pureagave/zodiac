package org.pureagave.zodiac.control.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class FleetRoleNameTest {
    @Test
    fun known_models_and_the_jetson_hostname_map_to_roles() {
        assertEquals("HERO", FleetRoleName.of("SM-X810"))
        assertEquals("DRIVER", FleetRoleName.of("SM-A546V"))
        assertEquals("BEACON", FleetRoleName.of("SM-G715U"))
        assertEquals("PASSENGER 11", FleetRoleName.of("KFTUWI"))
        assertEquals("PASSENGER 9", FleetRoleName.of("KFMAWI"))
        // The Jetson emits its hostname lowercase on the wire.
        assertEquals("JETSON", FleetRoleName.of("zvision"))
    }

    @Test
    fun the_two_fires_get_distinct_labels_so_a_stale_passenger_is_identifiable() {
        assertEquals(
            "the two passenger Fires must not collapse to one label",
            2,
            setOf(FleetRoleName.of("KFTUWI"), FleetRoleName.of("KFMAWI")).size,
        )
    }

    @Test
    fun matching_is_case_insensitive() {
        assertEquals("HERO", FleetRoleName.of("sm-x810"))
        assertEquals("BEACON", FleetRoleName.of("sm-g715u"))
    }

    @Test
    fun an_unknown_device_falls_back_to_its_raw_name() {
        // A new tablet not yet in the table shows its model, never blank or a wrong guess.
        assertEquals("SM-T870", FleetRoleName.of("SM-T870"))
        assertEquals("newbox", FleetRoleName.of("newbox"))
    }
}
