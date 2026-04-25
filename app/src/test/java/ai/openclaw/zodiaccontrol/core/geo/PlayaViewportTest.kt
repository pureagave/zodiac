package ai.openclaw.zodiaccontrol.core.geo

import org.junit.Assert.assertEquals
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

    private companion object {
        const val EPSILON = 1e-9
    }
}
