package org.pureagave.zodiac.control.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationCueTest {
    @Test
    fun street_label_reflects_the_cue_kind() {
        assertEquals("Esplanade", NavigationCue.OnArc("Esplanade", ClockTime(4, 30)).streetLabel())
        assertEquals("4:30", NavigationCue.OnRadialInbound("4:30", "Esplanade").streetLabel())
        // Driving *out* a radial announces the arc most recently crossed.
        assertEquals("Atwood", NavigationCue.OnRadialOutbound("4:30", "Atwood").streetLabel())
    }

    @Test
    fun off_street_cues_have_no_street_label() {
        assertNull(NavigationCue.Unknown.streetLabel())
        assertNull(NavigationCue.TowardClock(ClockTime(2, 0), 100.0).streetLabel())
        assertNull(NavigationCue.AwayFromClock(ClockTime(8, 0), 100.0).streetLabel())
    }
}
