package org.pureagave.zodiac.control.data.log

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.pureagave.zodiac.control.core.log.RollingFileLog
import org.pureagave.zodiac.control.core.log.formatLogLine
import timber.log.Timber
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Timber tree that persists to a [RollingFileLog] so a tablet that misbehaves
 * on the playa can be diagnosed after the fact, without a laptop attached at
 * the moment it happened.
 *
 * Writes go through a bounded in-memory buffer drained on IO: a log call from a
 * render or sensor coroutine costs a deque push and a doorbell poke, never a
 * flash write, so it can never block a frame. The buffer drops **oldest** on
 * overflow, on the same reasoning as the file's rotation — under a burst, the
 * lines nearest the problem are the ones worth keeping.
 *
 * **Every overflow drop is counted** in [droppedBeforeWrite], and that count is
 * the whole reason this is a hand-rolled deque and not a
 * `Channel(capacity, DROP_OLDEST)`. That channel's `trySend` reports success on
 * *every* call — it silently evicts the oldest to make room — so its overflow
 * is invisible by construction. It was the one gap the [RollingFileLog] loss
 * counters could never see: a burst that outran the flash lost lines before
 * they ever reached the file, and nothing anywhere said so. A log that quietly
 * loses lines lies during the one investigation it exists for.
 *
 * [Timber.DebugTree] handles logcat separately in debug builds; this tree's job
 * is only the file.
 */
class FileLogTree(
    private val log: RollingFileLog,
    scope: CoroutineScope,
    private val minPriority: Int = Log.INFO,
    private val clock: () -> Long = System::currentTimeMillis,
    // Buffer depth and drain dispatcher are seams: a test drives a shallow
    // buffer on a dispatcher it can hold still, so it can force overflow
    // deterministically rather than racing a real drain.
    private val bufferLines: Int = BUFFER_LINES,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    // Extends DebugTree, not Tree, purely for its class-name tag inference —
    // a bare Tree gets a null tag unless every call site says Timber.tag(...),
    // and "gps: select NET" is a lot less useful without knowing who said it.
    // log() below never calls super, so nothing reaches logcat from here.
) : Timber.DebugTree() {
    private val lock = Any()
    private val buffer = ArrayDeque<String>()

    /**
     * Doorbell with a single conflated slot. A producer pokes it after pushing
     * a line; the drain wakes, empties the buffer, then waits again. Conflated
     * because many pushes need only one wake (the drain always empties fully),
     * and a poke that lands *while* the drain is running is retained until the
     * next `receive()` — so a line can never be stranded in the buffer with no
     * wake pending. That retention is what closes the lost-wakeup window.
     */
    private val doorbell = Channel<Unit>(capacity = Channel.CONFLATED)

    /**
     * Lines shed from the buffer under overflow since process start — surfaced,
     * never silent, exactly like [RollingFileLog.droppedLines]. This is the
     * pre-file stage: the app produced faster than the flash could absorb, so
     * the oldest buffered lines were dropped to keep memory bounded. Distinct
     * from the file's own counters, which can only see lines that reached it.
     */
    @Volatile
    var droppedBeforeWrite: Long = 0L
        private set

    init {
        scope.launch(ioDispatcher) {
            while (true) {
                // Wake on any poke (the value is only a signal), then drain the
                // whole buffer — many pokes coalesce into one wake, so one wake
                // must clear everything queued. Ends when the scope is cancelled
                // at process death, which cancels this receive.
                doorbell.receive()
                while (true) {
                    val line = synchronized(lock) { buffer.removeFirstOrNull() } ?: break
                    log.append(line)
                }
            }
        }
    }

    // public (not Timber's protected default) for the same reason log() is:
    // the priority floor is load-bearing and a test must be able to read it
    // directly. Timber still calls it virtually, so widening changes nothing.
    public override fun isLoggable(
        tag: String?,
        priority: Int,
    ): Boolean = priority >= minPriority

    public override fun log(
        priority: Int,
        tag: String?,
        message: String,
        t: Throwable?,
    ) {
        // Format before taking the lock: building the line (and any stack
        // trace) must not widen the critical section every other producer and
        // the drain contend on.
        val line = formatLogLine(clock(), priority, tag, message, t?.stackTraceString())
        synchronized(lock) {
            buffer.addLast(line)
            // Drop oldest until back under the cap. Under the lock, so the
            // count and the eviction can never disagree.
            while (buffer.size > bufferLines) {
                buffer.removeFirst()
                droppedBeforeWrite++
            }
        }
        doorbell.trySend(Unit)
    }

    /**
     * Write one line synchronously, bypassing the buffer. For the uncaught-
     * exception path only: the process is about to die and an async drain would
     * lose exactly the entry that mattered.
     */
    fun logBlocking(
        priority: Int,
        tag: String?,
        message: String,
        t: Throwable?,
    ) {
        log.append(formatLogLine(clock(), priority, tag, message, t?.stackTraceString()))
    }

    private companion object {
        /** Deep enough to ride out a burst, shallow enough to stay bounded. */
        const val BUFFER_LINES = 256
    }
}

private fun Throwable.stackTraceString(): String = StringWriter().also { sw -> PrintWriter(sw).use { printStackTrace(it) } }.toString()
