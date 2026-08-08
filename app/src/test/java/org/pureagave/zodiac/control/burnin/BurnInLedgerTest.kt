package org.pureagave.zodiac.control.burnin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BurnInLedgerTest {
    private var now = 0L

    private fun ledger() = BurnInLedger { now }

    @Test
    fun time_is_banked_against_the_zone_that_was_open() {
        val l = ledger()

        l.mark("RADAR/ACTIVE")
        now += MINUTE * 10
        l.mark("MAP/ACTIVE")
        now += MINUTE * 5
        l.close()

        assertEquals(MINUTE * 10, l.totals()["RADAR/ACTIVE"])
        assertEquals(MINUTE * 5, l.totals()["MAP/ACTIVE"])
    }

    @Test
    fun revisiting_a_zone_accumulates_rather_than_replaces() {
        // The whole point is cumulative on-time across a burn, not the last visit.
        val l = ledger()

        l.mark("RADAR/ACTIVE")
        now += MINUTE * 3
        l.mark("RADAR/DIM")
        now += MINUTE
        l.mark("RADAR/ACTIVE")
        now += MINUTE * 4
        l.close()

        assertEquals(MINUTE * 7, l.totals()["RADAR/ACTIVE"])
        assertEquals(MINUTE, l.totals()["RADAR/DIM"])
    }

    @Test
    fun re_marking_the_same_zone_does_not_inflate_the_total() {
        // A recomposition storm must not turn into burn-risk hours.
        val l = ledger()

        l.mark("RADAR/ACTIVE")
        now += MINUTE
        repeat(REDUNDANT_MARKS) { l.mark("RADAR/ACTIVE") }
        now += MINUTE
        l.close()

        assertEquals(MINUTE * 2, l.totals()["RADAR/ACTIVE"])
    }

    @Test
    fun totals_include_the_still_open_zone() {
        // Reporting mid-session must not silently omit the zone you're in.
        val l = ledger()
        l.mark("DRIVER/ACTIVE")
        now += MINUTE * 90

        assertEquals(MINUTE * 90, l.totals()["DRIVER/ACTIVE"])
    }

    @Test
    fun a_backwards_clock_stalls_the_count_but_never_rewinds_it() {
        // elapsedRealtime shouldn't go backwards, but a risk figure that can be
        // *reduced* by a clock glitch is worse than one that merely stalls.
        // Time already banked is what must be protected — the ledger can't know
        // that the interval spanning the jump really happened, only that it
        // must not subtract from the record.
        val l = ledger()
        l.mark("RADAR/ACTIVE")
        now += MINUTE * 5
        l.mark("MAP/ACTIVE") // banks 5m against RADAR
        assertEquals(MINUTE * 5, l.totals()["RADAR/ACTIVE"])

        now -= HOUR
        l.mark("RADAR/ACTIVE")
        l.mark("MAP/ACTIVE")

        assertTrue("banked time must never shrink", l.totals()["RADAR/ACTIVE"]!! >= MINUTE * 5)
        assertTrue("no zone may go negative", l.totals().values.all { it >= 0 })
    }

    @Test
    fun the_report_leads_with_the_zone_most_at_risk() {
        val l = ledger()
        l.mark("MAP/ACTIVE")
        now += MINUTE * 20
        l.mark("RADAR/ACTIVE")
        now += HOUR * 2
        l.close()

        val report = l.report()

        assertTrue(report, report.startsWith("burn-in: RADAR/ACTIVE 2h0m"))
        assertTrue(report, report.contains("MAP/ACTIVE 20m"))
    }

    @Test
    fun an_untouched_ledger_says_so_rather_than_reporting_zeroes() {
        assertEquals("burn-in: no on-time recorded", ledger().report())
    }

    @Test
    fun durations_are_formatted_at_a_glanceable_scale() {
        assertEquals("45s", formatDuration(SECOND * 45))
        assertEquals("31m", formatDuration(MINUTE * 31))
        assertEquals("2h14m", formatDuration(HOUR * 2 + MINUTE * 14))
    }

    private companion object {
        const val SECOND = 1_000L
        const val MINUTE = 60 * SECOND
        const val HOUR = 60 * MINUTE
        const val REDUNDANT_MARKS = 500
    }
}
