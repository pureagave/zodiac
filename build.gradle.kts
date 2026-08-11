plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2" apply false
}

// --- FLEET-2: build identity, computed once, read by both modules ---------
// Every value fails toward "untrusted" (unknown sha / dirty), never toward a
// confident-but-wrong "clean known build", so the fleet monitor can never
// mistake a build it can't identify for a current one.
fun gitValue(vararg args: String): String? =
    try {
        val exec =
            providers.exec {
                workingDir = rootDir
                isIgnoreExitValue = true
                commandLine(listOf("git") + args)
            }
        if (exec.result.get().exitValue == 0) exec.standardOutput.asText.get().trim() else null
    } catch (e: Exception) {
        null
    }

val zodiacVersionBase = "0.1.0"
val zodiacGitSha =
    gitValue("rev-parse", "--short=9", "HEAD")
        ?.takeIf { Regex("[0-9a-f]{7,40}").matches(it) }
        ?: "unknown"
val zodiacGitDirty = gitValue("status", "--porcelain")?.isNotBlank() ?: true
val zodiacCommitEpoch = gitValue("log", "-1", "--format=%ct", "HEAD")?.toLongOrNull() ?: 0L

extra["zodiacVersionBase"] = zodiacVersionBase
extra["zodiacGitSha"] = zodiacGitSha
extra["zodiacGitDirty"] = zodiacGitDirty
extra["zodiacCommitEpoch"] = zodiacCommitEpoch
