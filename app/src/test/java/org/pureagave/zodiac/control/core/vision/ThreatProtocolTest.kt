package org.pureagave.zodiac.control.core.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreatProtocolTest {
    @Test
    fun round_trips_contacts() {
        val threats =
            listOf(
                DriverThreat(relAzDeg = -12.5f, size = 0.3f, collision = false, id = 1),
                DriverThreat(relAzDeg = 4.5f, size = 0.9f, collision = true, id = 2),
            )
        val parsed = ThreatProtocol.parse(ThreatProtocol.format(threats))!!
        assertEquals(2, parsed.size)
        assertEquals(1, parsed[0].id)
        assertEquals(-12.5f, parsed[0].relAzDeg, 0.01f)
        assertFalse(parsed[0].collision)
        assertEquals(0.9f, parsed[1].size, 0.01f)
        assertTrue(parsed[1].collision)
    }

    @Test
    fun empty_frame_is_all_clear() {
        assertEquals(emptyList<DriverThreat>(), ThreatProtocol.parse("ZTHREAT"))
    }

    @Test
    fun non_frame_is_null() {
        assertNull(ThreatProtocol.parse("hello world"))
        assertNull(ThreatProtocol.parse("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"))
    }

    @Test
    fun skips_malformed_contacts_keeps_the_good_ones() {
        val parsed = ThreatProtocol.parse("ZTHREAT;garbage;2:4.5:0.9:1")!!
        assertEquals(1, parsed.size)
        assertEquals(2, parsed[0].id)
    }

    @Test
    fun rejects_non_finite_az_and_size() {
        // NaN/Infinity parse as floats but would poison the HUD's Canvas math.
        assertEquals(emptyList<DriverThreat>(), ThreatProtocol.parse("ZTHREAT;1:NaN:0.5:0"))
        assertEquals(emptyList<DriverThreat>(), ThreatProtocol.parse("ZTHREAT;2:5.0:Infinity:1"))
    }

    @Test
    fun clamps_size_to_the_unit_range() {
        assertEquals(1f, ThreatProtocol.parse("ZTHREAT;1:0.0:9.0:0")!!.single().size, 0.001f)
        assertEquals(0f, ThreatProtocol.parse("ZTHREAT;1:0.0:-4.0:0")!!.single().size, 0.001f)
    }

    @Test
    fun drops_contacts_outside_the_forward_arc() {
        assertEquals(emptyList<DriverThreat>(), ThreatProtocol.parse("ZTHREAT;1:120.0:0.5:0"))
        assertEquals(1, ThreatProtocol.parse("ZTHREAT;1:89.0:0.5:0")!!.size)
    }

    @Test
    fun caps_the_contact_count() {
        val frame = "ZTHREAT" + (0 until 100).joinToString("") { ";$it:0.0:0.5:0" }
        assertEquals(32, ThreatProtocol.parse(frame)!!.size)
    }
}
