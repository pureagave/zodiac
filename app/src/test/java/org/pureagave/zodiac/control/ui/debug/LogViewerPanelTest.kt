package org.pureagave.zodiac.control.ui.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The scroll target is the one piece of decision logic in the viewer that can be
 * wrong without a pixel test catching it: an off-by-one lands the reader on the
 * "aged out" banner, or one line shy of the newest entry — the entry they opened
 * this screen to see. So it lives in [logViewerScrollTarget] and is pinned here.
 */
class LogViewerPanelTest {
    @Test
    fun empty_log_has_nothing_to_scroll_to() {
        assertNull(logViewerScrollTarget(lineCount = 0, agedOut = 0L))
        // Even if lines aged out, an empty tail still scrolls nowhere (and the
        // banner is not rendered without lines beneath it).
        assertNull(logViewerScrollTarget(lineCount = 0, agedOut = 42L))
    }

    @Test
    fun with_no_aged_out_banner_the_target_is_the_last_line() {
        // No leading item: the newest line is the last index, size - 1.
        assertEquals(0, logViewerScrollTarget(lineCount = 1, agedOut = 0L))
        assertEquals(399, logViewerScrollTarget(lineCount = 400, agedOut = 0L))
    }

    @Test
    fun the_aged_out_banner_shifts_the_target_down_by_one() {
        // Banner is item 0, so lines start at index 1 and the newest is at
        // exactly lineCount. A mutation that ignored agedOut would land on the
        // second-newest line; one that always added the banner would overshoot
        // on a log that never rotated.
        assertEquals(1, logViewerScrollTarget(lineCount = 1, agedOut = 1L))
        assertEquals(400, logViewerScrollTarget(lineCount = 400, agedOut = 7L))
    }
}
