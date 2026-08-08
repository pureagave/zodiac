package org.pureagave.zodiac.control.core.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleDeviceMatchTest {
    private val pattern = Regex(".*(?i:GPS|Garmin|Bad ?Elf|XGPS|Holux|Qstarz|GNSS).*")

    @Test
    fun the_known_receiver_brands_all_match() {
        listOf("Bad Elf GPS Pro", "BadElf 2200", "XGPS160", "Garmin GLO 2", "Holux M-1000", "Qstarz BT-Q818", "u-blox GNSS")
            .forEach { assertEquals(it, matchGpsDeviceName(listOf(it), pattern)) }
    }

    @Test
    fun matching_ignores_case() {
        assertEquals("my gps puck", matchGpsDeviceName(listOf("my gps puck"), pattern))
    }

    @Test
    fun a_headset_is_not_mistaken_for_a_receiver() {
        // The reason this stays a pattern rather than "first paired SPP device":
        // connecting the cockpit to someone's earbuds would be worse than
        // reporting no device.
        assertNull(matchGpsDeviceName(listOf("Bose QC35", "Pixel Buds", "Car Audio"), pattern))
    }

    @Test
    fun the_first_match_wins_when_several_qualify() {
        assertEquals("XGPS160", matchGpsDeviceName(listOf("Bose QC35", "XGPS160", "Garmin GLO"), pattern))
    }

    @Test
    fun nothing_paired_says_nothing_is_paired() {
        // Distinct from "paired but unmatched" — different fix at camp.
        assertTrue(noGpsDeviceMessage(emptyList()).contains("No Bluetooth devices are paired"))
    }

    @Test
    fun an_unmatched_list_names_the_candidates() {
        val message = noGpsDeviceMessage(listOf("Bose QC35", "GT-02 Tracker"))

        assertTrue(message, message.contains("Bose QC35"))
        assertTrue(message, message.contains("GT-02 Tracker"))
    }

    @Test
    fun an_absurd_pairing_list_is_truncated_rather_than_flooding_the_log() {
        val many = (1..30).map { "Device $it" }

        val message = noGpsDeviceMessage(many)

        assertTrue(message, message.contains("+24 more"))
        assertTrue("message stayed a sane length: ${message.length}", message.length < 200)
    }
}
