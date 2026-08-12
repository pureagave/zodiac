package org.pureagave.zodiac.control.core.net

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class FleetBusTest {
    @Test
    fun channel_groups_are_valid_multicast_addresses() {
        assertTrue("telemetry group must be multicast", InetAddress.getByName(FleetBus.TELEMETRY_GROUP).isMulticastAddress)
        assertTrue("threat group must be multicast", InetAddress.getByName(FleetBus.THREAT_GROUP).isMulticastAddress)
    }

    @Test
    fun telemetry_and_threat_channels_are_distinct() {
        assertNotEquals(FleetBus.TELEMETRY_GROUP, FleetBus.THREAT_GROUP)
        assertNotEquals(FleetBus.TELEMETRY_PORT, FleetBus.THREAT_PORT)
    }

    @Test
    fun nav_group_is_a_valid_multicast_address_distinct_from_the_others() {
        assertTrue("nav group must be multicast", InetAddress.getByName(FleetBus.NAV_GROUP).isMulticastAddress)
        assertNotEquals(FleetBus.NAV_GROUP, FleetBus.TELEMETRY_GROUP)
        assertNotEquals(FleetBus.NAV_GROUP, FleetBus.THREAT_GROUP)
        assertNotEquals(FleetBus.NAV_PORT, FleetBus.TELEMETRY_PORT)
        assertNotEquals(FleetBus.NAV_PORT, FleetBus.THREAT_PORT)
    }
}
