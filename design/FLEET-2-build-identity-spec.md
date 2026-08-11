# FLEET-2 — Build identity in `versionName` and `BuildConfig`

**Status:** spec, ready to implement.
**Owner of the spec:** this document is the contract; the implementation must match it.
**Depends on:** nothing. **Unblocks:** FLEET-1 (the fleet version monitor).

## Why

`versionCode = 1` / `versionName = "0.1.0"` are pinned and identical on every
build of both `:app` and `:beacon`, so a device **physically cannot report what
code it runs**. Install *time* is the only signal, and it is worthless on a
device with a wrong clock — the beacon came back from a flat battery set to
`Asia/Dubai` and wrote ten hours of plausible-but-wrong timestamps. On
2026‑08‑11 six devices were 40 / 38 / 32 / 25 commits and 9 days apart and every
one needed a USB cable to discover which.

This task makes **every build self‑identifying**: which commit it came from,
whether the working tree was clean, and how new that commit is. The identity
goes into two places:

1. **`versionName`** — so it is readable over `adb shell dumpsys package <pkg>`
   *without launching the app*, and so the app's existing boot log
   (`ZodiacApplication.kt:84`) prints it for free.
2. **`BuildConfig` fields** — structured, so FLEET‑1 can announce
   `(node, role, version, sha, commit‑epoch)` on the fleet bus **without
   string‑parsing** `versionName`.

## Scope

**In scope**

- Gradle wiring in `:app` and `:beacon` that bakes build identity into
  `BuildConfig` and composes `versionName`.
- A single shared computation of the git values (compute git **once**, in the
  **root** `build.gradle.kts`, exposed via `rootProject.extra`; both modules
  read it). No duplicated git logic that can drift between modules.
- A pure `core/telemetry/BuildIdentity` value type in `:app` that **defines the
  `versionName` format as a contract** (`render` and its inverse `parse`), so the
  format is tested rather than an ad‑hoc string, and FLEET‑1 has a parser to lean
  on.
- Tests (see §5). Including a **contract test** that pins the Gradle‑produced
  `versionName` against `BuildIdentity.render()` of the structured fields — the
  same discipline as the ZTHREAT golden corpus, applied to the Gradle↔Kotlin
  seam.

**Explicitly NOT in scope** (do not build these — they are FLEET‑1 or later):

- No fleet‑bus announcement, no peer collection, no S9+ card, no UI.
- No `$Z*` sentence for the version. No networking of any kind.
- No Jetson/Python changes. The Jetson reports its own version in FLEET‑1.
- **No per‑build wall‑clock timestamp.** See the decision in §3.
- Do **not** touch `versionCode` — it stays `1` (see §3).

## 1. The `versionName` format (the contract)

```
<base>+<sha>            e.g.  0.1.0+8897cab95        (clean tree)
<base>+<sha>.dirty      e.g.  0.1.0+8897cab95.dirty  (uncommitted changes)
<base>+unknown.dirty    when git is unavailable at build time (fail‑untrusted)
```

- `base` = the existing semantic version string, unchanged: `"0.1.0"`.
- `+` is the separator (semver build‑metadata delimiter; legal in
  `android:versionName`, which is a free‑form string).
- `sha` = `git rev-parse --short=9 HEAD` — **fixed at 9 chars** so a shallow CI
  clone and a full local clone produce the *same* string for the same commit
  (git's default abbreviation is adaptive and would differ). Validated against
  `^[0-9a-f]{7,40}$`; anything else → `unknown`.
- `.dirty` suffix iff `git status --porcelain` is non‑empty.

**This format is defined once** in `BuildIdentity.render()` (§4). The Gradle
scripts mirror it, and the §5 contract test fails if they drift.

## 2. Shared git computation — root `build.gradle.kts`

Compute the values **once** in the root script and stash them in
`rootProject.extra`. Root configures before subprojects, so both modules can read
them. Use `providers.exec` (works with or without the configuration cache; this
repo has none today but do not regress that).

Add to `build.gradle.kts` (root), after the existing `plugins { … }` block:

```kotlin
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
```

Notes:
- `git status --porcelain` returns `""` on a clean tree → `isNotBlank()` is
  `false` → not dirty. `null` (git failed) → `?: true` → dirty. Correct in both
  directions.
- Keep it **ktlint‑clean** — `ktlintCheck` lints `.gradle.kts` script files.
  Match the surrounding style (4‑space indent, wrapped call chains).

## 3. Module wiring — `:app` and `:beacon`

Do the **same** in both modules. Declare four `val`s near the top of the module
`build.gradle.kts` (after `plugins`, before `android { }`), reading the shared
values:

```kotlin
val versionBase = rootProject.extra["zodiacVersionBase"] as String
val gitSha = rootProject.extra["zodiacGitSha"] as String
val gitDirty = rootProject.extra["zodiacGitDirty"] as Boolean
val commitEpoch = rootProject.extra["zodiacCommitEpoch"] as Long
val versionSuffix = if (gitDirty) "$gitSha.dirty" else gitSha
```

Then inside `defaultConfig { }` **replace** the pinned `versionName` line and add
the four fields:

```kotlin
versionCode = 1                          // UNCHANGED — see below
versionName = "$versionBase+$versionSuffix"

buildConfigField("String", "VERSION_BASE", "\"$versionBase\"")
buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
buildConfigField("boolean", "GIT_DIRTY", "$gitDirty")
buildConfigField("long", "GIT_COMMIT_EPOCH_SECONDS", "${commitEpoch}L")
```

- The `L` suffix on the long is required (the value exceeds `Int.MAX` after
  2038; write it now so it is never wrong).
- **`:beacon` has `buildConfig` disabled today** — enable it. It has no
  `buildFeatures` block; add one:

  ```kotlin
  buildFeatures {
      buildConfig = true
  }
  ```

  (`:app` already has `buildConfig = true`; leave it.)

**Decisions, deliberately made — do not "improve" these:**

- **`versionCode` stays `1`.** The fleet sideloads with `adb install -r`; a
  per‑commit `versionCode` bump would make a same‑or‑older reinstall fail as a
  downgrade. `versionCode` is not part of the identity we need — the sha is.
- **No per‑build wall‑clock.** We use the **commit** epoch
  (`git log -1 %ct`), not `System.currentTimeMillis()`, as the "how new is this
  code" signal, for two reasons: (1) a per‑build timestamp changes `BuildConfig`
  on *every* `assembleDebug`, making the build and **every `testDebugUnitTest`
  run non‑cacheable** — unacceptable for a project that runs the full gate
  constantly; (2) it adds no signal the monitor can act on beyond commit epoch +
  the dirty flag. Two devices on the same commit with the same cleanliness are
  running identical code; if one is dirty, `GIT_DIRTY` already says so. If
  FLEET‑1 ever needs to disambiguate same‑commit rebuilds, revisit then. Because
  the identity changes only on a commit or a clean↔dirty transition, `BuildConfig`
  stays stable across repeated builds and the test cache still works.
- **`versionName` deliberately does not carry the epoch**, so it is stable per
  `(commit, cleanliness)` — `adb install -r` stays idempotent and the string
  stays human‑readable. The epoch lives only in `BuildConfig`.

## 4. `BuildIdentity` — the pure contract type (`:app`)

New file: `app/src/main/java/org/pureagave/zodiac/control/core/telemetry/BuildIdentity.kt`

```kotlin
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
        fun parse(versionName: String, commitEpochSeconds: Long = 0L): BuildIdentity {
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
```

Keep it pure (no Android imports). `ReturnCount` is relaxed to 3; `parse` has 2
returns.

## 5. Tests

### 5a. `:app` — `BuildIdentityTest` (pure logic + the contract)

`app/src/test/java/org/pureagave/zodiac/control/core/telemetry/BuildIdentityTest.kt`,
plain JUnit4 (no coroutines, no Robolectric). Cover, at minimum:

1. `render` clean → `"0.1.0+abcdef123"`.
2. `render` dirty → `"0.1.0+abcdef123.dirty"`.
3. **Round‑trip:** for both clean and dirty, `parse(bi.render(), bi.commitEpochSeconds) == bi`.
4. `sanitizeSha`: accepts 7/9/40 lowercase‑hex; rejects `null`, `""`, `"   "`,
   `"unknown"`, uppercase `"ABCDEF1"`, `"HEAD"`, and a value containing a
   non‑hex char (e.g. `"0x1p3abc"`, `"g23456789"`) → `UNKNOWN_SHA`.
5. `dirtyFromPorcelain`: `""` → false; `null` → true; `" M app/build.gradle.kts"`
   → true.
6. `known`: `UNKNOWN_SHA` → false; a real sha → true.
7. `parse` of a string with no `+` → `known == false`, `dirty == true`, `base` is
   the whole string.
8. **Contract test (the one that guards the Gradle wiring):**

   ```kotlin
   val fields = BuildIdentity(
       base = BuildConfig.VERSION_BASE,
       sha = BuildConfig.GIT_SHA,
       dirty = BuildConfig.GIT_DIRTY,
       commitEpochSeconds = BuildConfig.GIT_COMMIT_EPOCH_SECONDS,
   )
   assertEquals(BuildConfig.VERSION_NAME, fields.render())
   assertEquals(fields, BuildIdentity.parse(BuildConfig.VERSION_NAME, BuildConfig.GIT_COMMIT_EPOCH_SECONDS))
   ```

   (`BuildConfig` is on the `testDebugUnitTest` classpath — same as the existing
   `BuildConfig.DEBUG` reads.)

9. **Field shape:** `BuildConfig.GIT_SHA` matches `^([0-9a-f]{7,40}|unknown)$`;
   `BuildConfig.VERSION_NAME.startsWith(BuildConfig.VERSION_BASE + "+")`;
   `BuildConfig.GIT_COMMIT_EPOCH_SECONDS >= 0`.

Tests 8–9 are the ones that make this task's Gradle change *verifiable* — they go
red if a field is dropped, mistyped, or the format drifts.

### 5b. `:beacon` — `BuildIdentityTest`

`beacon/src/test/java/org/pureagave/zodiac/beacon/BuildIdentityTest.kt`, plain
JUnit4. The beacon has no `core/` and does not need the parser yet — just pin its
own wiring (this is what proves enabling `buildConfig` actually produced the
fields):

- `BuildConfig.GIT_SHA` matches `^([0-9a-f]{7,40}|unknown)$`.
- `BuildConfig.VERSION_NAME.startsWith(BuildConfig.VERSION_BASE + "+")`.
- `BuildConfig.VERSION_BASE == "0.1.0"`.
- `BuildConfig.GIT_COMMIT_EPOCH_SECONDS >= 0`.

## 6. Definition of done

- [ ] Root `build.gradle.kts` computes the four values once, via `providers.exec`,
      failing toward unknown/dirty; exposed on `rootProject.extra`.
- [ ] `:app` and `:beacon` both set `versionName = "$base+$suffix"` and emit the
      four `buildConfigField`s. `:beacon` has `buildConfig = true`.
- [ ] `versionCode` unchanged (`1`) in both.
- [ ] `BuildIdentity.kt` added under `:app` `core/telemetry/`, pure.
- [ ] `:app` `BuildIdentityTest` (incl. the contract + field‑shape tests) and
      `:beacon` `BuildIdentityTest` added and green.
- [ ] Full gate green, **unscoped**:
      `./gradlew ktlintCheck detekt lintDebug testDebugUnitTest assembleDebug`
- [ ] Manually confirm the baked values after `assembleDebug`:
      the generated `app/build/generated/source/buildConfig/debug/.../BuildConfig.java`
      shows a real 9‑char `GIT_SHA` and `VERSION_NAME = "0.1.0+<sha>"`, and the
      same for `:beacon`.
- [ ] No unrelated files touched. No `tasks/`, `SYNC.md`, or Jetson edits (the
      spec author handles the SYNC/tasks note and the commit).

## 7. Traps (from this project's own logbook)

- `str.replace`/manual edits silently no‑op when the anchor drifts — **assert the
  edit landed** (grep the file after).
- **Run the gate, don't predict it.** ktlint lints `.gradle.kts`; detekt and lint
  will see the new `.kt`. `python` does not exist on this Mac — irrelevant here,
  but `./gradlew` may need `JAVA_HOME`/`ANDROID_HOME` set inline.
- Do not add a build‑time wall‑clock "because FLEET‑1 mentions build epoch" — see
  §3; commit epoch is the deliberate choice and keeps the build cacheable.
