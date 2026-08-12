package org.pureagave.zodiac.control.ui.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.pureagave.zodiac.control.core.ops.NavShareArbiter
import org.pureagave.zodiac.control.core.ops.NavShareMessage
import org.pureagave.zodiac.control.core.ops.NavSharePayload
import org.pureagave.zodiac.control.core.ops.NavShareProtocol
import org.pureagave.zodiac.control.core.ops.NavTarget
import org.pureagave.zodiac.control.data.nav.NavSharePublisher

/**
 * The `$ZNAV` set + adopt path (spec R2-R6): the single place a user action or
 * a received message turns into a call on [navigation], the fleet's Lamport
 * ordering ([arbiter]), and the wire ([publisher]).
 *
 * Two entry points, one rule that must never be broken: **adoption never
 * publishes.** [onReceived] applies a target through exactly the same
 * [NavigationController] methods a local set uses (spec: "adoption on the
 * receiving side should call these same methods, so a received target is
 * applied exactly like a local entry") but the only call to
 * [NavSharePublisher.publish] anywhere in this class is inside [userSet] —
 * that asymmetry is the entire no-echo guarantee. [NavShareArbiter] already
 * refuses to *adopt* a device's own transmission (own-echo), but that alone
 * would still leave every device re-broadcasting everything it hears; keeping
 * `publish` out of the receive path entirely is what makes only the owner
 * transmit.
 *
 * Same shape as [NavigationController]: an internal delegate the ViewModel
 * constructs and forwards to, sharing this class's [scope].
 */
internal class NavShareController(
    private val navigation: NavigationController,
    private val publisher: NavSharePublisher,
    private val isAuthority: () -> Boolean,
    private val arbiter: NavShareArbiter,
    private val persistSeq: suspend (Int) -> Unit,
    private val scope: CoroutineScope,
) {
    /**
     * A local user action (chip tap, address keypad, BATH, or CLEAR). Central
     * authority gate: a follower's tap must be a genuine no-op — no state
     * change, no publish — so a passenger poking a Fire cannot diverge its
     * target from the fleet's. Returns false when the gate closed it or (for
     * [NavSharePayload.Address]) the target itself didn't resolve; true means
     * it applied AND published.
     */
    fun userSet(payload: NavSharePayload): Boolean {
        if (!isAuthority()) return false
        if (!applyLocally(payload)) return false
        val seq = arbiter.userSet()
        publisher.publish(NavShareProtocol.build(NavShareMessage(seq = seq, src = arbiter.mySrc, payload = payload)))
        scope.launch { persistSeq(seq) }
        return true
    }

    /**
     * A received `$ZNAV`. Adopts iff the arbiter says this key is newer than
     * the last one applied (own-echo and stale/duplicate messages are
     * silently ignored by the arbiter itself). Applying is routed through the
     * exact same [NavigationController] entry points [userSet] uses, so a
     * remote target renders identically to a local one. If this device was
     * the owner before adopting, it yields: [NavSharePublisher.stop] cancels
     * its periodic re-broadcast (spec R5).
     */
    fun onReceived(msg: NavShareMessage) {
        val wasOwning = arbiter.owning
        if (!arbiter.onReceived(msg.seq, msg.src)) return
        applyLocally(msg.payload)
        if (wasOwning) publisher.stop()
        scope.launch { persistSeq(arbiter.maxSeen) }
    }

    private fun applyLocally(payload: NavSharePayload): Boolean =
        when (payload) {
            is NavSharePayload.Preset -> navigation.setNavTarget(payload.target).let { true }
            is NavSharePayload.Address -> navigation.driveToAddress(payload.clock, payload.ring)
            NavSharePayload.Bath -> navigation.driveToNearestToilet().let { true }
            // CLEAR has no "no target" state to clear to (decision 1) -- it
            // adopts setNavTarget(HOME), semantically distinct on the wire
            // (intent = "cancel") but identical in effect to PRESET,HOME.
            NavSharePayload.Clear -> navigation.setNavTarget(NavTarget.HOME).let { true }
        }
}
