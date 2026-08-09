package org.pureagave.zodiac.beacon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [safeForegroundTypes] has zero Android imports specifically so this suite can
 * assert the platform contract with literal bit values — never the
 * `ServiceInfo.FOREGROUND_SERVICE_TYPE_*` constants, and never the
 * implementation's own private mirrors of them — so a wrong constant on either
 * side of the implementation cannot make a test agree with a bug (the project's
 * named anti-pattern; see AUDIT-2026-08-09 "Tests that agree with the code they
 * test"). Literal values per the Android SDK: location = 8, microphone = 128,
 * specialUse = 0x40000000.
 */
class ForegroundTypesTest {
    @Test
    fun mic_bit_never_present_on_a_background_start_from_30_up() {
        // Mutation target: delete the `!fromBackground` conjunct in the mic
        // clause. That re-creates the exact shipped bug — a background/boot start
        // (API 35 explicitly bans this from BOOT_COMPLETED) requesting the
        // microphone type it isn't allowed to have.
        for (sdk in listOf(30, 33, 34, 35)) {
            val types = safeForegroundTypes(sdk, hasLocationPermission = true, hasRecordAudio = true, fromBackground = true)
            assertNotNull("sdk $sdk should use the typed call", types)
            assertEquals("sdk $sdk background start must not carry the mic bit (128)", 0, types!! and 128)
        }
    }

    @Test
    fun mic_bit_requires_record_audio() {
        // Mutation target: replace `hasRecordAudio && !fromBackground` with `true`.
        // On API 34+ that recreates the SecurityException-on-launch bug: the mic
        // type is requested even though RECORD_AUDIO was never granted.
        val types = safeForegroundTypes(34, hasLocationPermission = true, hasRecordAudio = false, fromBackground = false)
        assertNotNull(types)
        assertEquals("no RECORD_AUDIO must never carry the mic bit (128)", 0, types!! and 128)
    }

    @Test
    fun mic_bit_present_when_actually_earned() {
        // Positive control for the two mutations above: with both RECORD_AUDIO
        // granted and a foreground start, the mic bit must be set — otherwise a
        // constant-false mutation of either conjunct would also pass the two
        // negative tests above by coincidence.
        val types = safeForegroundTypes(34, hasLocationPermission = true, hasRecordAudio = true, fromBackground = false)
        assertNotNull(types)
        assertEquals(128, types!! and 128)
    }

    @Test
    fun no_permissions_on_34_plus_yields_special_use_never_zero() {
        // Mutation target: delete the `types == 0` floor. Without it this call
        // returns 0, and starting a foreground service with type 0 throws on
        // API 34+ — the exact "beacon with zero permissions can't even start
        // degraded" failure this floor exists to prevent.
        val types = safeForegroundTypes(34, hasLocationPermission = false, hasRecordAudio = false, fromBackground = true)
        assertEquals(0x40000000, types)
        val types35 = safeForegroundTypes(35, hasLocationPermission = false, hasRecordAudio = false, fromBackground = false)
        assertEquals(0x40000000, types35)
    }

    @Test
    fun location_bit_requires_permission_only_from_34() {
        // Below 34, the location type may be declared even without the permission
        // held yet (matches platform behaviour pre-34); mutation target: drop the
        // `sdkInt < 34 ||` disjunct, which would also gate 29/30/33 on the
        // permission and fail this half of the assertion.
        for (sdk in listOf(29, 30, 33)) {
            val types = safeForegroundTypes(sdk, hasLocationPermission = false, hasRecordAudio = false, fromBackground = false)
            if (sdk == 29) {
                assertNull("api 29 uses the legacy call", types)
            } else {
                assertNotNull(types)
                assertEquals("sdk $sdk below 34 must carry the location bit (8) regardless of permission", 8, types!! and 8)
            }
        }
        // From 34, no permission → no location bit.
        for (sdk in listOf(34, 35)) {
            val types = safeForegroundTypes(sdk, hasLocationPermission = false, hasRecordAudio = false, fromBackground = false)
            assertNotNull(types)
            assertEquals("sdk $sdk with no location permission must not carry bit 8", 0, types!! and 8)
        }
        // From 34, permission held → location bit present.
        val withPermission = safeForegroundTypes(34, hasLocationPermission = true, hasRecordAudio = false, fromBackground = false)
        assertEquals(8, withPermission!! and 8)
    }

    @Test
    fun api_29_uses_the_legacy_call() {
        // Mutation target: change the `sdkInt < 30` threshold (e.g. to `< 29`),
        // which would make sdk 29 take the typed branch instead of returning null
        // for the legacy 2-arg startForeground call.
        for (hasLoc in listOf(false, true)) {
            for (hasMic in listOf(false, true)) {
                for (bg in listOf(false, true)) {
                    assertNull(safeForegroundTypes(29, hasLoc, hasMic, bg))
                }
            }
        }
    }

    @Test
    fun exhaustive_matrix_from_30_up_never_throws_and_never_returns_zero() {
        // Full cross product across the SDKs named in the audit and all 8
        // boolean combinations (encoded as a 3-bit mask to keep this a flat
        // two-level loop). No assertion beyond "always typed, never the
        // illegal zero type" — the six tests above pin the specific bit
        // semantics.
        for (sdk in listOf(30, 33, 34, 35)) {
            for (mask in 0 until 8) {
                val hasLoc = (mask and 1) != 0
                val hasMic = (mask and 2) != 0
                val bg = (mask and 4) != 0
                val types = safeForegroundTypes(sdk, hasLoc, hasMic, bg)
                assertNotNull("sdk=$sdk loc=$hasLoc mic=$hasMic bg=$bg", types)
                assertTrue("type bitmask must never be 0 on 30+", types!! != 0)
            }
        }
    }
}
