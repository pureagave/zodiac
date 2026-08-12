package org.pureagave.zodiac.control.core.ops

/**
 * Lamport ordering + single-owner tracking for `$ZNAV` (spec R2/R5). Pure —
 * no I/O, no coroutines; [org.pureagave.zodiac.control.ui.viewmodel.NavShareController]
 * is the wiring around it.
 *
 * Total order is `(seq, src)`: higher `seq` wins outright; an equal `seq` is
 * broken by the lexicographically greater `src`, so two authorities setting
 * "simultaneously" from the same [maxSeen] still converge on exactly one
 * winner without relying on device clocks (unreliable — the beacon came back
 * 10 h off once). [mySrc] is never adopted from a receive (own-echo — the
 * device binds wildcard and the sender broadcasts subnet-wide, so a device
 * hears its own transmission back).
 */
class NavShareArbiter(val mySrc: String) {
    /** Highest seq this device has ever seen, from any source — grows on [seed], [userSet], and any non-own [onReceived]. */
    var maxSeen: Int = 0
        private set

    private var lastApplied: Key? = null

    /** True iff the last *applied* key (local or adopted) carries this device's own [mySrc] — the sole periodic re-broadcaster. */
    val owning: Boolean
        get() = lastApplied?.src == mySrc

    /** Raise [maxSeen] from a persisted seq (e.g. process restart) without adopting anything. */
    fun seed(persistedSeq: Int) {
        maxSeen = maxOf(maxSeen, persistedSeq)
    }

    /** A local user set: always outbids everything seen so far and makes this device the owner. Returns the seq to put on the wire. */
    fun userSet(): Int {
        val seq = maxSeen + 1
        maxSeen = seq
        lastApplied = Key(seq, mySrc)
        return seq
    }

    /**
     * A received `$ZNAV`. Returns true iff the caller should apply it (adopt).
     * Own-echo (`src == mySrc`) is ignored outright — [maxSeen] untouched.
     * Otherwise [maxSeen] grows to at least [seq] regardless of the outcome,
     * and the message is adopted iff `(seq, src)` is strictly greater than
     * the last applied key — a device that has never applied anything (late
     * join) adopts the first valid message it sees.
     */
    fun onReceived(
        seq: Int,
        src: String,
    ): Boolean {
        if (src == mySrc) return false
        if (seq > maxSeen) maxSeen = seq
        val incoming = Key(seq, src)
        val current = lastApplied
        val adopt = current == null || incoming > current
        if (adopt) lastApplied = incoming
        return adopt
    }

    private data class Key(val seq: Int, val src: String) : Comparable<Key> {
        override fun compareTo(other: Key): Int {
            val bySeq = seq.compareTo(other.seq)
            return if (bySeq != 0) bySeq else src.compareTo(other.src)
        }
    }
}
