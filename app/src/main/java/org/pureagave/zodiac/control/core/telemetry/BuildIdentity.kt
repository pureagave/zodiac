package org.pureagave.zodiac.control.core.telemetry

/**
 * Identity of the running build: the commit it was built from, whether the
 * working tree was clean, and how new that commit is. Baked at build time by the
 * FLEET-2 Gradle wiring into [org.pureagave.zodiac.control.BuildConfig] and
 * rendered into `versionName` so it is visible over
 * `adb shell dumpsys package` without launching the app.
 *
 * The string form is a contract the fleet version monitor (FLEET-1) will parse,
 * so [render] is the single definition of how the fields compose into a
 * `versionName` and [parse] is its inverse. A test pins that the Gradle-produced
 * `BuildConfig.VERSION_NAME` equals [render] of the structured `BuildConfig`
 * fields, so the Gradle and Kotlin sides cannot silently drift.
 *
 * Every unknown fails toward [UNKNOWN_SHA] / [dirty] = true: an unidentifiable
 * build must read as "unknown", never as a confident current build.
 */
data class BuildIdentity(
    val base: String,
    val sha: String,
    val dirty: Boolean,
    val commitEpochSeconds: Long,
) {
    /** True only when this build carries a real commit sha. */
    val known: Boolean get() = sha != UNKNOWN_SHA && sha.isNotBlank()

    /** Compose the canonical `versionName`. Inverse of [parse]. */
    fun render(): String {
        val suffix = if (dirty) "$sha$DIRTY_SUFFIX" else sha
        return "$base$SEPARATOR$suffix"
    }

    companion object {
        const val UNKNOWN_SHA = "unknown"
        const val SEPARATOR = "+"
        const val DIRTY_SUFFIX = ".dirty"

        private val SHA_REGEX = Regex("[0-9a-f]{7,40}")

        /** Normalise a raw `git rev-parse --short` value; blank/garbage → [UNKNOWN_SHA]. */
        fun sanitizeSha(raw: String?): String {
            val trimmed = raw?.trim().orEmpty()
            return if (SHA_REGEX.matches(trimmed)) trimmed else UNKNOWN_SHA
        }

        /** `git status --porcelain` empty ⇒ clean; null (git failed) ⇒ dirty. */
        fun dirtyFromPorcelain(porcelain: String?): Boolean = porcelain?.isNotBlank() ?: true

        /**
         * Parse a `versionName` produced by [render]. Tolerant: a string with no
         * separator, or a garbage sha, yields an [known] == false identity rather
         * than throwing. [commitEpochSeconds] is not encoded in the string and is
         * supplied by the caller (from `BuildConfig.GIT_COMMIT_EPOCH_SECONDS`).
         */
        fun parse(
            versionName: String,
            commitEpochSeconds: Long = 0L,
        ): BuildIdentity {
            val sep = versionName.indexOf(SEPARATOR)
            if (sep < 0) {
                return BuildIdentity(versionName, UNKNOWN_SHA, dirty = true, commitEpochSeconds)
            }
            val base = versionName.substring(0, sep)
            val rest = versionName.substring(sep + SEPARATOR.length)
            val dirty = rest.endsWith(DIRTY_SUFFIX)
            val shaPart = if (dirty) rest.removeSuffix(DIRTY_SUFFIX) else rest
            return BuildIdentity(base, sanitizeSha(shaPart), dirty, commitEpochSeconds)
        }
    }
}
