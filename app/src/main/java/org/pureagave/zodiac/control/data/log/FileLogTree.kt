package org.pureagave.zodiac.control.data.log

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
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
 * Writes go through a bounded channel drained on IO: a log call from a render
 * or sensor coroutine costs an offer and nothing else, and can never block a
 * frame on a slow flash write. The buffer drops **oldest** on overflow, on the
 * same reasoning as the file's rotation — under a burst, the lines nearest the
 * problem are the ones worth keeping.
 *
 * [Timber.DebugTree] handles logcat separately in debug builds; this tree's job
 * is only the file.
 */
class FileLogTree(
    private val log: RollingFileLog,
    scope: CoroutineScope,
    private val minPriority: Int = Log.INFO,
    private val clock: () -> Long = System::currentTimeMillis,
    // Extends DebugTree, not Tree, purely for its class-name tag inference —
    // a bare Tree gets a null tag unless every call site says Timber.tag(...),
    // and "gps: select NET" is a lot less useful without knowing who said it.
    // log() below never calls super, so nothing reaches logcat from here.
) : Timber.DebugTree() {
    private val pending =
        Channel<String>(capacity = BUFFER_LINES, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        scope.launch(Dispatchers.IO) {
            for (line in pending) log.append(line)
        }
    }

    override fun isLoggable(
        tag: String?,
        priority: Int,
    ): Boolean = priority >= minPriority

    override fun log(
        priority: Int,
        tag: String?,
        message: String,
        t: Throwable?,
    ) {
        pending.trySend(formatLogLine(clock(), priority, tag, message, t?.stackTraceString()))
    }

    /**
     * Write one line synchronously, bypassing the channel. For the uncaught-
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
