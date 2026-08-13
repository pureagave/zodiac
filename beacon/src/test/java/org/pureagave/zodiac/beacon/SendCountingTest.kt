package org.pureagave.zodiac.beacon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * AUDIT area 4: `send()` used to do `sentences++` on a `@Volatile Long` from
 * one coroutine per sentence on `Dispatchers.IO` -- a lost-update race under
 * concurrent writers -- and every per-target `sock.send()` failure was
 * swallowed with no counter at all, violating the project's "count everything
 * you discard" invariant. `sendToTargets` is the pulled-out, deterministic
 * core of `send()`; this exercises it directly with a fake sink, no real
 * socket or coroutine needed.
 */
class SendCountingTest {
    private val addrA: InetAddress = InetAddress.getByName("239.7.7.10")
    private val addrB: InetAddress = InetAddress.getByName("255.255.255.255")

    @Test
    fun every_target_failing_counts_every_failure_and_no_sentence() {
        // Mutation target: revert `.onFailure { sendFailures.incrementAndGet() }`
        // to a bare `runCatching { ... }` -- sendFailures drops to 0 and this
        // assertion goes red.
        val sentences = AtomicLong(0)
        val sendFailures = AtomicLong(0)

        sendToTargets("hello".toByteArray(), listOf(addrA, addrB), sentences, sendFailures) {
            error("simulated send() failure")
        }

        assertEquals(0L, sentences.get())
        assertEquals(2L, sendFailures.get())
    }

    @Test
    fun a_partial_failure_still_counts_one_sentence_and_one_failure() {
        val sentences = AtomicLong(0)
        val sendFailures = AtomicLong(0)
        var calls = 0

        sendToTargets("hello".toByteArray(), listOf(addrA, addrB), sentences, sendFailures) { _ ->
            calls++
            if (calls == 1) error("addrA send() failure")
            // addrB succeeds
        }

        assertEquals(1L, sentences.get())
        assertEquals(1L, sendFailures.get())
    }

    @Test
    fun a_fully_successful_send_counts_one_sentence_and_no_failures() {
        // Mutation target: revert `if (anySent) sentences.incrementAndGet()`
        // to an unconditional increment -- this test alone would not catch
        // that (it also expects 1), but the all-fail test above would then
        // wrongly see sentences == 1 instead of 0.
        val sentences = AtomicLong(0)
        val sendFailures = AtomicLong(0)
        val received = mutableListOf<DatagramPacket>()

        sendToTargets("hello".toByteArray(), listOf(addrA, addrB), sentences, sendFailures) { received.add(it) }

        assertEquals(1L, sentences.get())
        assertEquals(0L, sendFailures.get())
        assertEquals(2, received.size)
    }

    @Test
    fun eighty_thousand_concurrent_sends_across_eight_threads_land_exactly() {
        // AtomicLong under real multi-thread contention -- the old `Long++`
        // on a @Volatile field is a lost-update race here; AtomicLong is not.
        // This is a robustness/regression guard rather than a targeted
        // mutation test: it is high-probability, not guaranteed, to catch a
        // reintroduced plain-Long counter on a multi-core runner.
        val sentences = AtomicLong(0)
        val sendFailures = AtomicLong(0)
        val threadCount = 8
        val perThread = 10_000
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threadCount)
        val pool = Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) {
            pool.submit {
                startGate.await()
                repeat(perThread) {
                    sendToTargets("x".toByteArray(), listOf(addrA), sentences, sendFailures) { }
                }
                doneGate.countDown()
            }
        }
        startGate.countDown()
        doneGate.await()
        pool.shutdown()

        assertEquals((threadCount * perThread).toLong(), sentences.get())
        assertEquals(0L, sendFailures.get())
    }

    // --- broadcastFooter -------------------------------------------------

    @Test
    fun footer_with_no_failures_has_no_send_errs_clause() {
        val footer = broadcastFooter("239.7.7.10", 10110, sent = 5, failed = 0)
        assertEquals("→ 239.7.7.10:10110   ·   sent 5", footer)
    }

    @Test
    fun footer_with_failures_reports_them() {
        // Mutation target: drop the `if (failed > 0)` branch -- this
        // assertion (which requires "send errs" to be present) goes red.
        val footer = broadcastFooter("239.7.7.10", 10110, sent = 5, failed = 3)
        assertTrue("expected 'sent 5' in: $footer", footer.contains("sent 5"))
        assertTrue("expected 'send errs 3' in: $footer", footer.contains("send errs 3"))
    }
}
