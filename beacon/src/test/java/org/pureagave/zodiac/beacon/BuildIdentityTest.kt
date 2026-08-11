package org.pureagave.zodiac.beacon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The beacon has no `core/` module and does not need [BuildIdentity]'s parser
 * (that lives in `:app` — see `core/telemetry/BuildIdentityTest`). This just
 * pins the beacon's own FLEET-2 Gradle wiring: that `buildConfig = true` was
 * actually turned on and the four fields it emits have the shapes the wire
 * contract (and the eventual FLEET-1 monitor) require. Every assertion here
 * would fail if `beacon/build.gradle.kts` regressed to the old pinned
 * `versionName = "0.1.0"` / `buildConfig = false` state.
 */
class BuildIdentityTest {
    @Test
    fun git_sha_is_a_lowercase_hex_short_sha_or_the_unknown_fallback() {
        assertTrue(
            "GIT_SHA was '${BuildConfig.GIT_SHA}'",
            Regex("^([0-9a-f]{7,40}|unknown)$").matches(BuildConfig.GIT_SHA),
        )
    }

    @Test
    fun version_name_is_the_base_plus_a_build_suffix() {
        assertTrue(
            "VERSION_NAME was '${BuildConfig.VERSION_NAME}'",
            BuildConfig.VERSION_NAME.startsWith(BuildConfig.VERSION_BASE + "+"),
        )
    }

    @Test
    fun version_base_is_the_pinned_semantic_version() {
        assertEquals("0.1.0", BuildConfig.VERSION_BASE)
    }

    @Test
    fun commit_epoch_is_never_negative() {
        assertTrue(BuildConfig.GIT_COMMIT_EPOCH_SECONDS >= 0)
    }

    @Test
    fun dirty_flag_and_version_name_dirty_suffix_agree() {
        // The wiring derives versionName's ".dirty" suffix from the same
        // GIT_DIRTY boolean that is baked as its own field — if either the
        // suffix logic or the field emission drifted from the other, this
        // catches it without duplicating the `:app` contract test's full
        // render()/parse() round trip (the beacon has no BuildIdentity type).
        assertEquals(BuildConfig.GIT_DIRTY, BuildConfig.VERSION_NAME.endsWith(".dirty"))
    }

    @Test
    fun version_code_is_unchanged_by_the_fleet_2_wiring() {
        // versionCode intentionally stays pinned at 1 (see spec §3) — a
        // per-commit bump would make adb install -r fail as a downgrade on a
        // same-or-older reinstall across the fleet.
        assertEquals(1, BuildConfig.VERSION_CODE)
    }
}
