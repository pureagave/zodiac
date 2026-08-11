package org.pureagave.zodiac.control.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.BuildConfig

/**
 * [BuildIdentity] defines the `versionName` format as a contract between the
 * FLEET-2 Gradle wiring (root + `:app` `build.gradle.kts`) and the fleet
 * version monitor (FLEET-1) that will eventually parse it. Tests 8/9 below are
 * the ones that actually exercise the Gradle side — everything else is pure
 * logic on [BuildIdentity] itself and would pass even if the Gradle wiring were
 * deleted entirely.
 */
class BuildIdentityTest {
    private val cleanSample = BuildIdentity(base = "0.1.0", sha = "abcdef123", dirty = false, commitEpochSeconds = 1_723_000_000L)
    private val dirtySample = cleanSample.copy(dirty = true)

    @Test
    fun render_clean_has_no_dirty_suffix() {
        assertEquals("0.1.0+abcdef123", cleanSample.render())
    }

    @Test
    fun render_dirty_appends_dirty_suffix() {
        assertEquals("0.1.0+abcdef123.dirty", dirtySample.render())
    }

    @Test
    fun round_trip_clean() {
        val rendered = cleanSample.render()
        assertEquals(cleanSample, BuildIdentity.parse(rendered, cleanSample.commitEpochSeconds))
    }

    @Test
    fun round_trip_dirty() {
        val rendered = dirtySample.render()
        assertEquals(dirtySample, BuildIdentity.parse(rendered, dirtySample.commitEpochSeconds))
    }

    @Test
    fun sanitizeSha_accepts_valid_lowercase_hex_at_the_pinned_lengths() {
        // 7, 9 (the length this project's Gradle wiring actually emits) and 40
        // (a full un-abbreviated sha) are all legal git short/full sha lengths.
        assertEquals("abcdef1", BuildIdentity.sanitizeSha("abcdef1"))
        assertEquals("abcdef123", BuildIdentity.sanitizeSha("abcdef123"))
        val full40 = "0123456789abcdef0123456789abcdef01234567"
        assertEquals(40, full40.length)
        assertEquals(full40, BuildIdentity.sanitizeSha(full40))
    }

    @Test
    fun sanitizeSha_rejects_garbage_and_falls_back_to_unknown() {
        assertEquals(BuildIdentity.UNKNOWN_SHA, BuildIdentity.sanitizeSha(null))
        assertEquals(BuildIdentity.UNKNOWN_SHA, BuildIdentity.sanitizeSha(""))
        assertEquals(BuildIdentity.UNKNOWN_SHA, BuildIdentity.sanitizeSha("   "))
        assertEquals(BuildIdentity.UNKNOWN_SHA, BuildIdentity.sanitizeSha("unknown"))
        assertEquals(BuildIdentity.UNKNOWN_SHA, BuildIdentity.sanitizeSha("ABCDEF1"))
        assertEquals(BuildIdentity.UNKNOWN_SHA, BuildIdentity.sanitizeSha("HEAD"))
        assertEquals(BuildIdentity.UNKNOWN_SHA, BuildIdentity.sanitizeSha("0x1p3abc"))
        assertEquals(BuildIdentity.UNKNOWN_SHA, BuildIdentity.sanitizeSha("g23456789"))
    }

    @Test
    fun sanitizeSha_boundary_lengths() {
        // 6 hex chars is one below git's shortest legal abbreviation — must be
        // rejected, not silently accepted by a loosened regex.
        assertEquals(BuildIdentity.UNKNOWN_SHA, BuildIdentity.sanitizeSha("abcdef"))
        // 41 hex chars is one past a full sha1 — must be rejected too, so the
        // regex can't be satisfied by "40-or-more".
        val oneTooLong = "0123456789abcdef0123456789abcdef012345678"
        assertEquals(41, oneTooLong.length)
        assertEquals(BuildIdentity.UNKNOWN_SHA, BuildIdentity.sanitizeSha(oneTooLong))
    }

    @Test
    fun dirtyFromPorcelain_empty_string_is_clean() {
        assertFalse(BuildIdentity.dirtyFromPorcelain(""))
    }

    @Test
    fun dirtyFromPorcelain_null_fails_toward_dirty() {
        assertTrue(BuildIdentity.dirtyFromPorcelain(null))
    }

    @Test
    fun dirtyFromPorcelain_nonblank_output_is_dirty() {
        assertTrue(BuildIdentity.dirtyFromPorcelain(" M app/build.gradle.kts"))
    }

    @Test
    fun known_is_false_for_unknown_sha() {
        assertFalse(cleanSample.copy(sha = BuildIdentity.UNKNOWN_SHA).known)
    }

    @Test
    fun known_is_true_for_a_real_sha() {
        assertTrue(cleanSample.known)
    }

    @Test
    fun known_is_false_for_a_blank_sha_even_if_not_literally_the_unknown_constant() {
        // known checks isNotBlank() too, not just `!= UNKNOWN_SHA` — an empty
        // string must not read as a known build just because it isn't the
        // literal word "unknown".
        assertFalse(cleanSample.copy(sha = "").known)
        assertFalse(cleanSample.copy(sha = "   ").known)
    }

    @Test
    fun parse_with_no_separator_treats_the_whole_string_as_base_and_is_unknown() {
        val identity = BuildIdentity.parse("garbage-string-no-plus")
        assertFalse(identity.known)
        assertTrue(identity.dirty)
        assertEquals("garbage-string-no-plus", identity.base)
    }

    @Test
    fun parse_tolerates_an_empty_sha_between_separator_and_dirty_suffix() {
        // "0.1.0+.dirty" — no sha at all, just the dirty marker. Must not throw,
        // and must not accidentally read as known.
        val identity = BuildIdentity.parse("0.1.0+.dirty")
        assertEquals("0.1.0", identity.base)
        assertFalse(identity.known)
        assertTrue(identity.dirty)
    }

    @Test
    fun parse_tolerates_a_malformed_sha_segment_containing_extra_separators() {
        // A second "+" inside the sha segment is not valid hex, so this must
        // degrade to unknown rather than throwing or silently truncating.
        val identity = BuildIdentity.parse("0.1.0+abc+def")
        assertEquals("0.1.0", identity.base)
        assertFalse(identity.known)
    }

    @Test
    fun an_unidentifiable_build_round_trips_to_an_unknown_identity() {
        // The exact string the Gradle wiring emits when git is unavailable at
        // build time: base + "unknown" + ".dirty" (fail-closed). It must render
        // to that string AND parse back to a known == false identity — a device
        // that could not identify itself must never round-trip into looking
        // current. This is the single most operationally important case: it is
        // what the fleet monitor sees for a build it cannot place.
        val unidentifiable =
            BuildIdentity(
                base = "0.1.0",
                sha = BuildIdentity.UNKNOWN_SHA,
                dirty = true,
                commitEpochSeconds = 0L,
            )
        assertEquals("0.1.0+unknown.dirty", unidentifiable.render())
        val parsed = BuildIdentity.parse(unidentifiable.render(), 0L)
        assertEquals(unidentifiable, parsed)
        assertFalse(parsed.known)
    }

    // --- Contract test: guards the Gradle wiring in root + app build.gradle.kts ---

    @Test
    fun buildConfig_version_name_matches_render_of_its_own_structured_fields() {
        val fields =
            BuildIdentity(
                base = BuildConfig.VERSION_BASE,
                sha = BuildConfig.GIT_SHA,
                dirty = BuildConfig.GIT_DIRTY,
                commitEpochSeconds = BuildConfig.GIT_COMMIT_EPOCH_SECONDS,
            )
        assertEquals(BuildConfig.VERSION_NAME, fields.render())
        assertEquals(fields, BuildIdentity.parse(BuildConfig.VERSION_NAME, BuildConfig.GIT_COMMIT_EPOCH_SECONDS))
    }

    @Test
    fun buildConfig_fields_have_the_shapes_the_wire_contract_requires() {
        assertTrue(
            "GIT_SHA was '${BuildConfig.GIT_SHA}'",
            Regex("^([0-9a-f]{7,40}|unknown)$").matches(BuildConfig.GIT_SHA),
        )
        assertTrue(
            "VERSION_NAME was '${BuildConfig.VERSION_NAME}'",
            BuildConfig.VERSION_NAME.startsWith(BuildConfig.VERSION_BASE + "+"),
        )
        assertTrue(BuildConfig.GIT_COMMIT_EPOCH_SECONDS >= 0)
    }

    @Test
    fun buildConfig_version_base_is_the_pinned_semantic_version() {
        // This build is run from a git checkout of this repo, so the base is
        // known ahead of time, unlike sha/dirty/epoch which vary with HEAD.
        assertEquals("0.1.0", BuildConfig.VERSION_BASE)
    }
}
