package org.pureagave.zodiac.control.core.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavShareArbiterTest {
    @Test
    fun userSet_is_monotonic_and_self_owning() {
        val arbiter = NavShareArbiter(mySrc = "A")
        assertFalse("nothing applied yet", arbiter.owning)

        val first = arbiter.userSet()
        assertEquals(1, first)
        assertEquals(1, arbiter.maxSeen)
        assertTrue("the device that just set locally owns the target", arbiter.owning)

        val second = arbiter.userSet()
        assertEquals(2, second)
        assertEquals(2, arbiter.maxSeen)
    }

    @Test
    fun a_higher_seq_from_another_src_is_adopted() {
        val arbiter = NavShareArbiter(mySrc = "A")
        arbiter.userSet() // seq=1, A owns

        val adopted = arbiter.onReceived(seq = 5, src = "B")

        assertTrue("higher seq must be adopted", adopted)
        assertEquals(5, arbiter.maxSeen)
        assertFalse("B now owns, not A", arbiter.owning)
    }

    @Test
    fun a_lower_seq_is_ignored() {
        val arbiter = NavShareArbiter(mySrc = "A")
        arbiter.userSet() // seq=1
        arbiter.onReceived(seq = 5, src = "B") // adopt: B owns at seq=5

        val adopted = arbiter.onReceived(seq = 3, src = "C")

        assertFalse("a stale seq must not be adopted", adopted)
        // B's key is still the last applied one.
        assertFalse(arbiter.owning)
    }

    @Test
    fun an_equal_seq_from_a_lexicographically_lower_src_is_ignored() {
        val arbiter = NavShareArbiter(mySrc = "A")
        arbiter.onReceived(seq = 5, src = "M") // late join, adopts; last applied = (5, "M")

        val adopted = arbiter.onReceived(seq = 5, src = "B") // "B" < "M" lexicographically

        assertFalse("equal seq must lose the tie-break to a lexicographically lower src", adopted)
    }

    @Test
    fun an_equal_seq_from_a_lexicographically_higher_src_does_win_the_tie_break() {
        // Positive control for the test above.
        val arbiter = NavShareArbiter(mySrc = "A")
        arbiter.onReceived(seq = 5, src = "M")

        val adopted = arbiter.onReceived(seq = 5, src = "Z") // "Z" > "M" lexicographically

        assertTrue("equal seq must win the tie-break against a lexicographically higher src", adopted)
    }

    @Test
    fun tie_convergence_exactly_one_side_yields_and_both_agree() {
        // Two authorities set locally at the "same time" (both start from
        // maxSeen=0, so both mint seq=1) and then cross-deliver to each other.
        val arbiterA = NavShareArbiter(mySrc = "A")
        val arbiterB = NavShareArbiter(mySrc = "B")

        val seqA = arbiterA.userSet()
        val seqB = arbiterB.userSet()
        assertEquals(1, seqA)
        assertEquals(1, seqB)

        val aAdoptedB = arbiterA.onReceived(seq = seqB, src = "B")
        val bAdoptedA = arbiterB.onReceived(seq = seqA, src = "A")

        // Exactly one side must yield (adopt the other's message) -- not zero, not both.
        assertTrue("exactly one side must yield", aAdoptedB != bAdoptedA)
        // Both converge on the same owner: "B" wins the (1,1) tie over "A".
        assertTrue("A must have yielded to B (B > A lexicographically)", aAdoptedB)
        assertFalse("B must not yield to A", bAdoptedA)
        assertFalse("A no longer owns", arbiterA.owning)
        assertTrue("B still owns", arbiterB.owning)
    }

    @Test
    fun own_src_is_never_adopted_even_with_a_much_higher_seq() {
        val arbiter = NavShareArbiter(mySrc = "A")
        arbiter.userSet() // seq=1, A owns

        val adopted = arbiter.onReceived(seq = 999, src = "A")

        assertFalse("a device must never adopt its own echoed transmission", adopted)
        assertTrue("still owning -- the echo changed nothing", arbiter.owning)
        assertEquals("own-echo must not even bump maxSeen", 1, arbiter.maxSeen)
    }

    @Test
    fun a_duplicate_seq_and_src_is_ignored_on_re_delivery() {
        val arbiter = NavShareArbiter(mySrc = "A")
        val firstAdopt = arbiter.onReceived(seq = 5, src = "B")
        assertTrue("positive control: the first copy adopts", firstAdopt)

        // The AP's second copy of the exact same datagram (multicast + subnet
        // broadcast both delivered), not a new message.
        val secondAdopt = arbiter.onReceived(seq = 5, src = "B")

        assertFalse("a byte-identical (seq, src) re-delivery must not re-adopt", secondAdopt)
    }

    @Test
    fun a_device_that_has_never_applied_anything_adopts_the_first_valid_message() {
        val arbiter = NavShareArbiter(mySrc = "A")
        assertFalse(arbiter.owning)

        val adopted = arbiter.onReceived(seq = 1, src = "B")

        assertTrue("a fresh/late-joining device must adopt its first message", adopted)
        assertFalse(arbiter.owning)
        assertEquals(1, arbiter.maxSeen)
    }

    @Test
    fun owning_flips_false_the_instant_a_higher_key_is_adopted() {
        val arbiter = NavShareArbiter(mySrc = "A")
        arbiter.userSet()
        assertTrue(arbiter.owning)

        arbiter.onReceived(seq = 2, src = "B")

        assertFalse("adopting a higher key must yield ownership", arbiter.owning)
    }

    @Test
    fun seed_raises_maxSeen_so_the_next_userSet_outbids_persisted_history() {
        val arbiter = NavShareArbiter(mySrc = "A")
        arbiter.seed(persistedSeq = 41)
        assertEquals(41, arbiter.maxSeen)

        val seq = arbiter.userSet()

        assertEquals("userSet must outbid the persisted history, not restart from 1", 42, seq)
    }

    @Test
    fun seed_never_lowers_maxSeen() {
        val arbiter = NavShareArbiter(mySrc = "A")
        arbiter.onReceived(seq = 100, src = "B")
        assertEquals(100, arbiter.maxSeen)

        arbiter.seed(persistedSeq = 5) // stale/smaller persisted value

        assertEquals("seed must be a max(), never a regression", 100, arbiter.maxSeen)
    }

    @Test
    fun seed_does_not_confer_ownership() {
        // Seeding restores the Lamport high-water mark from persistence, but it
        // is NOT a set: a rebooted device has applied nothing yet, so it must
        // not believe it owns the target -- otherwise it would start
        // re-broadcasting a target it never actually holds.
        val arbiter = NavShareArbiter(mySrc = "A")
        arbiter.seed(persistedSeq = 41)
        assertFalse("seeding the counter must not make the device an owner", arbiter.owning)
    }

    @Test
    fun userSet_mints_above_the_high_water_mark_not_the_last_applied_key() {
        // The whole reason maxSeen is tracked separately from the last-applied
        // key: after a reboot (seed) a device may adopt an OLD, low-seq message
        // -- late-join adopts the first thing it hears -- leaving lastApplied at
        // a low seq while maxSeen still holds the persisted high-water mark. A
        // local set must outbid the fleet's real history (maxSeen), not the
        // stale key it happens to be displaying, or a rebooted authority loses
        // to a fleet that already agreed on a far higher seq.
        val arbiter = NavShareArbiter(mySrc = "A")
        arbiter.seed(persistedSeq = 50)
        val adopted = arbiter.onReceived(seq = 5, src = "B") // late-join adopts despite the low seq
        assertTrue("precondition: late-join adopts the first message even below the seed", adopted)

        val seq = arbiter.userSet()

        assertEquals("userSet must mint maxSeen+1 (51), not lastApplied.seq+1 (6)", 51, seq)
        assertEquals(51, arbiter.maxSeen)
        assertTrue("a local set makes this device the owner again", arbiter.owning)
    }

    @Test
    fun a_seeded_device_still_adopts_a_first_message_below_the_seed() {
        // Adoption is gated on the last-APPLIED key, never on maxSeen. A device
        // that has only seeded its counter (applied nothing) must still adopt
        // the first valid message it hears -- even one whose seq is below the
        // seed -- or a rebooted device could never re-sync from the owner's
        // periodic re-broadcast. Guards against a "fix" that gates adoption on
        // maxSeen.
        val arbiter = NavShareArbiter(mySrc = "A")
        arbiter.seed(persistedSeq = 100)

        val adopted = arbiter.onReceived(seq = 5, src = "B")

        assertTrue("a seeded-but-never-applied device must adopt its first message", adopted)
        assertFalse("adopting a remote key never confers ownership", arbiter.owning)
    }
}
