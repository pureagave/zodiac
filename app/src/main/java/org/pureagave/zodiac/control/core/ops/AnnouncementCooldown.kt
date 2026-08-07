package org.pureagave.zodiac.control.core.ops

/**
 * Suppresses re-announcing the same thing while it is still nearby.
 *
 * The cockpit flashes a callout when the ego reaches a new street or comes
 * within range of a new piece of art. "New" was judged by comparing against
 * only the *previous* value, which ping-pongs: two art pieces at similar range
 * take turns being nearest as the ego jitters, so the pair re-announces on
 * every flip; a street name flickering to null and back at a block edge
 * re-flashes each time it returns. Both spam a display a driver is meant to
 * glance at.
 *
 * Remembering *recently* announced keys instead of just the last one fixes
 * both, and lets a genuine second pass hours later still announce.
 *
 * Bounded by construction: entries older than the cooldown are pruned on every
 * call, so a night spent driving past hundreds of pieces cannot grow this
 * without limit.
 */
class AnnouncementCooldown(
    private val cooldownMs: Long,
    private val nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
) {
    private val announcedAt = mutableMapOf<String, Long>()

    /**
     * Whether [key] should be announced now — and, if so, records it as
     * announced. Calling this is what starts the cooldown, so callers must only
     * call it when they intend to act on the answer.
     */
    fun shouldAnnounce(key: String): Boolean {
        val now = nowMs()
        prune(now)
        val last = announcedAt[key]
        if (last != null && now - last < cooldownMs) return false
        announcedAt[key] = now
        return true
    }

    /** Number of keys currently held — exposed so growth can be asserted. */
    val trackedCount: Int
        get() = announcedAt.size

    /** Forget everything; used when the relevant context resets. */
    fun clear() = announcedAt.clear()

    private fun prune(now: Long) {
        if (announcedAt.isEmpty()) return
        announcedAt.entries.removeAll { now - it.value >= cooldownMs }
    }

    companion object {
        private const val NANOS_PER_MS: Long = 1_000_000L
    }
}
