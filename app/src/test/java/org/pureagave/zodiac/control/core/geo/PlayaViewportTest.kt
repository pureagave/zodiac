package org.pureagave.zodiac.control.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayaViewportTest {
    @Test
    fun centered_origin_lands_at_screen_center() {
        val v = PlayaViewport(widthPx = 800, heightPx = 600)
        val s = v.toScreen(PlayaPoint(0.0, 0.0))
        assertEquals(400.0, s.x, EPSILON)
        assertEquals(300.0, s.y, EPSILON)
    }

    @Test
    fun north_up_at_heading_zero_north_is_top_of_screen() {
        val v = PlayaViewport(widthPx = 800, heightPx = 600, pixelsPerMeter = 1.0)
        val s = v.toScreen(PlayaPoint(eastM = 0.0, northM = 100.0))
        assertEquals(400.0, s.x, EPSILON)
        // 100 m north → 100 px above center (smaller Y)
        assertEquals(200.0, s.y, EPSILON)
    }

    @Test
    fun east_at_heading_zero_is_screen_right() {
        val v = PlayaViewport(widthPx = 800, heightPx = 600, pixelsPerMeter = 1.0)
        val s = v.toScreen(PlayaPoint(eastM = 100.0, northM = 0.0))
        assertEquals(500.0, s.x, EPSILON)
        assertEquals(300.0, s.y, EPSILON)
    }

    @Test
    fun heading_90_track_up_puts_east_at_top() {
        // Vehicle heading 90° (due east). Track-up means the heading direction
        // is at the top of the screen, so a point that is 100 m east of the
        // ego should appear directly above ego on screen.
        val v = PlayaViewport(headingDeg = 90.0, widthPx = 800, heightPx = 600, pixelsPerMeter = 1.0)
        val s = v.toScreen(PlayaPoint(eastM = 100.0, northM = 0.0))
        assertEquals(400.0, s.x, 1e-6)
        assertEquals(200.0, s.y, 1e-6)
    }

    @Test
    fun pixels_per_meter_scales_distance_linearly() {
        val a = PlayaViewport(widthPx = 800, heightPx = 600, pixelsPerMeter = 0.5)
        val b = PlayaViewport(widthPx = 800, heightPx = 600, pixelsPerMeter = 1.0)
        val pa = a.toScreen(PlayaPoint(eastM = 200.0, northM = 0.0))
        val pb = b.toScreen(PlayaPoint(eastM = 200.0, northM = 0.0))
        assertEquals(100.0, pa.x - 400.0, EPSILON)
        assertEquals(200.0, pb.x - 400.0, EPSILON)
    }

    @Test
    fun camera_offset_shifts_world() {
        // Camera centered 50 m east of origin → origin should appear 50 m west
        // of screen center.
        val v =
            PlayaViewport(
                center = PlayaPoint(eastM = 50.0, northM = 0.0),
                widthPx = 800,
                heightPx = 600,
                pixelsPerMeter = 1.0,
            )
        val s = v.toScreen(PlayaPoint(0.0, 0.0))
        assertEquals(350.0, s.x, EPSILON)
        assertEquals(300.0, s.y, EPSILON)
    }

    @Test
    fun anchor_y_frac_shifts_camera_origin_vertically() {
        // anchorYFrac = 0.78 puts the camera origin at 78% down the canvas.
        val v =
            PlayaViewport(
                widthPx = 800,
                heightPx = 600,
                pixelsPerMeter = 1.0,
                anchorYFrac = 0.78,
            )
        val s = v.toScreen(PlayaPoint(0.0, 0.0))
        assertEquals(400.0, s.x, EPSILON)
        assertEquals(0.78 * 600, s.y, EPSILON)
    }

    @Test
    fun north_offset_lifts_above_anchor() {
        // With anchor at 0.78 and 100 m north of origin, the projected point
        // should be 100 px above the anchor (smaller screen Y).
        val v =
            PlayaViewport(
                widthPx = 800,
                heightPx = 600,
                pixelsPerMeter = 1.0,
                anchorYFrac = 0.78,
            )
        val s = v.toScreen(PlayaPoint(eastM = 0.0, northM = 100.0))
        assertEquals(0.78 * 600 - 100.0, s.y, EPSILON)
    }

    @Test
    fun center_atCameraCenter_landsAtCxCy() {
        // A point AT the camera center maps to (cx, cy) = (widthPx/2, heightPx*anchorYFrac).
        val v =
            PlayaViewport(
                center = PlayaPoint(eastM = 123.0, northM = -45.0),
                widthPx = 1024,
                heightPx = 768,
                pixelsPerMeter = 1.0,
                anchorYFrac = 0.5,
            )
        val s = v.toScreen(PlayaPoint(eastM = 123.0, northM = -45.0))
        assertEquals(512.0, s.x, EPSILON)
        assertEquals(384.0, s.y, EPSILON)
    }

    @Test
    fun northOfCenter_isAboveCenter() {
        val v = PlayaViewport(widthPx = 800, heightPx = 600, pixelsPerMeter = 1.0)
        val s = v.toScreen(PlayaPoint(eastM = 0.0, northM = 50.0))
        assertEquals(400.0, s.x, EPSILON)
        // North of center → screen y strictly less than cy (300).
        assertTrue("north point should be above center, got y=${s.y}", s.y < 300.0)
    }

    @Test
    fun eastOfCenter_isRightOfCenter() {
        val v = PlayaViewport(widthPx = 800, heightPx = 600, pixelsPerMeter = 1.0)
        val s = v.toScreen(PlayaPoint(eastM = 50.0, northM = 0.0))
        assertEquals(300.0, s.y, EPSILON)
        // East of center → screen x strictly greater than cx (400).
        assertTrue("east point should be right of center, got x=${s.x}", s.x > 400.0)
    }

    @Test
    fun heading90_rotatesEastPointTowardScreenTop() {
        // headingDeg=90 → cosH=0, sinH=1. For an east-only offset (dx,0):
        // xRot = dx*0 - 0*1 = 0; yRot = dx*1 + 0*1 = dx. So x stays at cx and the
        // point lifts above center by dx*pixelsPerMeter (track-up).
        val v = PlayaViewport(headingDeg = 90.0, widthPx = 800, heightPx = 600, pixelsPerMeter = 1.0)
        val s = v.toScreen(PlayaPoint(eastM = 120.0, northM = 0.0))
        assertEquals(400.0, s.x, EPSILON)
        assertEquals(300.0 - 120.0, s.y, EPSILON)
    }

    @Test
    fun toScreenInline_matchesToScreen_headingZero() {
        val v = PlayaViewport(widthPx = 800, heightPx = 600, pixelsPerMeter = 0.75)
        val expected = v.toScreen(PlayaPoint(eastM = 60.0, northM = -30.0))
        var sx = Double.NaN
        var sy = Double.NaN
        v.toScreenInline(eastM = 60.0, northM = -30.0) { x, y ->
            sx = x
            sy = y
        }
        assertEquals(expected.x, sx, EPSILON)
        assertEquals(expected.y, sy, EPSILON)
    }

    @Test
    fun toScreenInline_matchesToScreen_withHeadingAndOffset() {
        val v =
            PlayaViewport(
                center = PlayaPoint(eastM = 10.0, northM = 20.0),
                headingDeg = 37.0,
                widthPx = 800,
                heightPx = 600,
                pixelsPerMeter = 0.4,
                anchorYFrac = 0.6,
            )
        val expected = v.toScreen(PlayaPoint(eastM = 90.0, northM = 140.0))
        var sx = Double.NaN
        var sy = Double.NaN
        v.toScreenInline(eastM = 90.0, northM = 140.0) { x, y ->
            sx = x
            sy = y
        }
        assertEquals(expected.x, sx, EPSILON)
        assertEquals(expected.y, sy, EPSILON)
    }

    @Test
    fun pixelsPerMeter_doubling_doublesNorthOffset() {
        val a = PlayaViewport(widthPx = 800, heightPx = 600, pixelsPerMeter = 0.5)
        val b = PlayaViewport(widthPx = 800, heightPx = 600, pixelsPerMeter = 1.0)
        val pa = a.toScreen(PlayaPoint(eastM = 0.0, northM = 200.0))
        val pb = b.toScreen(PlayaPoint(eastM = 0.0, northM = 200.0))
        // Offset above center (300 - y) should scale linearly with pixelsPerMeter.
        assertEquals(100.0, 300.0 - pa.y, EPSILON)
        assertEquals(200.0, 300.0 - pb.y, EPSILON)
    }

    @Test
    fun nonZeroCenter_offsetPointMapsRelativeToCenter() {
        // Camera centered 30 m east / 40 m north; a point 30 m east + 40 m north
        // beyond center is 30 m E and 40 m N of the camera center.
        val v =
            PlayaViewport(
                center = PlayaPoint(eastM = 30.0, northM = 40.0),
                widthPx = 800,
                heightPx = 600,
                pixelsPerMeter = 1.0,
            )
        val s = v.toScreen(PlayaPoint(eastM = 60.0, northM = 80.0))
        // dx=30 → 30 px right of cx; dy=40 → 40 px above cy.
        assertEquals(430.0, s.x, EPSILON)
        assertEquals(260.0, s.y, EPSILON)
    }

    @Test
    fun anchorYFrac_belowZero_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            PlayaViewport(widthPx = 800, heightPx = 600, anchorYFrac = -0.1)
        }
    }

    @Test
    fun anchorYFrac_aboveOne_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            PlayaViewport(widthPx = 800, heightPx = 600, anchorYFrac = 1.1)
        }
    }

    @Test
    fun anchorYFrac_zero_isAccepted() {
        val v = PlayaViewport(widthPx = 800, heightPx = 600, anchorYFrac = 0.0)
        val s = v.toScreen(PlayaPoint(0.0, 0.0))
        assertEquals(0.0, s.y, EPSILON)
    }

    @Test
    fun anchorYFrac_one_isAccepted() {
        val v = PlayaViewport(widthPx = 800, heightPx = 600, anchorYFrac = 1.0)
        val s = v.toScreen(PlayaPoint(0.0, 0.0))
        assertEquals(600.0, s.y, EPSILON)
    }

    private companion object {
        const val EPSILON = 1e-9
    }
}
