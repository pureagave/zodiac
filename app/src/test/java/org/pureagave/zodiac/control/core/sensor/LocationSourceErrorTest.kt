package org.pureagave.zodiac.control.core.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSourceErrorTest {
    @Test
    fun healthy_states_are_not_faults() {
        listOf(
            LocationSourceState.Disconnected to "OFF",
            LocationSourceState.Searching to "SEARCHING",
            LocationSourceState.Active(fix()) to "FIX",
        ).forEach { (state, text) ->
            val label = gpsStatusLabel(state)
            assertEquals(text, label.text)
            assertFalse("$state should not read as a fault", label.fault)
        }
    }

    @Test
    fun every_error_category_gets_its_own_wording_and_reads_as_a_fault() {
        // The whole point of the enum: each label names a different job at
        // camp. If two of them collide, the split has bought nothing.
        val labels =
            LocationSourceError.entries.map { kind ->
                val label = gpsStatusLabel(LocationSourceState.Error("detail", kind))
                assertTrue("$kind should read as a fault", label.fault)
                label.text
            }

        assertEquals("labels must be distinct", labels.size, labels.toSet().size)
    }

    @Test
    fun the_actionable_categories_say_what_to_do() {
        assertEquals("⊘ PERMISSION", gpsStatusLabel(error(LocationSourceError.PERMISSION_DENIED)).text)
        assertEquals("⊘ ADAPTER OFF", gpsStatusLabel(error(LocationSourceError.ADAPTER_UNAVAILABLE)).text)
        assertEquals("? NO DEVICE", gpsStatusLabel(error(LocationSourceError.NO_DEVICE_FOUND)).text)
        assertEquals("✕ I/O", gpsStatusLabel(error(LocationSourceError.IO_ERROR)).text)
        assertEquals("✕ ERROR", gpsStatusLabel(error(LocationSourceError.UNKNOWN)).text)
    }

    @Test
    fun an_untagged_error_degrades_to_unknown_rather_than_lying() {
        // The kind defaults, so a source that forgets to categorise says
        // "ERROR" — never something specific and wrong.
        assertEquals(LocationSourceError.UNKNOWN, LocationSourceState.Error("boom").kind)
        assertEquals("✕ ERROR", gpsStatusLabel(LocationSourceState.Error("boom")).text)
    }

    @Test
    fun the_detail_survives_for_the_log_even_though_the_screen_shows_the_category() {
        val state = LocationSourceState.Error("NET: bind :10110 failed — EADDRINUSE", LocationSourceError.IO_ERROR)

        assertEquals("✕ I/O", gpsStatusLabel(state).text)
        assertTrue(state.detail.contains("10110"))
    }

    private fun error(kind: LocationSourceError) = LocationSourceState.Error("detail", kind)

    private fun fix() =
        GpsFix(
            location = org.pureagave.zodiac.control.core.geo.LatLon(lon = -119.2, lat = 40.78),
            headingDeg = 0.0,
            speedKph = 0.0,
        )
}
