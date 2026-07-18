package org.pureagave.zodiac.control.data.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.sensor.LocationSourceType

class LocationSourceRegistryTest {
    @Test
    fun sourceFor_returns_source_with_matching_type() {
        val fake = StubLocationSource(LocationSourceType.FAKE)
        val system = StubLocationSource(LocationSourceType.SYSTEM)
        val registry = LocationSourceRegistry(listOf(fake, system))

        assertSame(fake, registry.sourceFor(LocationSourceType.FAKE))
        assertSame(system, registry.sourceFor(LocationSourceType.SYSTEM))
    }

    @Test
    fun sourceFor_unregistered_type_throws_illegal_state() {
        val registry = LocationSourceRegistry(listOf(StubLocationSource(LocationSourceType.FAKE)))

        val error =
            assertThrows(IllegalStateException::class.java) {
                registry.sourceFor(LocationSourceType.USB)
            }
        assertTrue(error.message!!.contains("USB"))
    }

    @Test
    fun available_reflects_exactly_the_registered_types() {
        val registry =
            LocationSourceRegistry(
                listOf(
                    StubLocationSource(LocationSourceType.FAKE),
                    StubLocationSource(LocationSourceType.BLE),
                ),
            )

        assertEquals(setOf(LocationSourceType.FAKE, LocationSourceType.BLE), registry.available)
    }

    @Test
    fun duplicate_types_collapse_to_last_registered_in_available_and_lookup() {
        val firstFake = StubLocationSource(LocationSourceType.FAKE)
        val secondFake = StubLocationSource(LocationSourceType.FAKE)
        val registry = LocationSourceRegistry(listOf(firstFake, secondFake))

        // associateBy keeps the last entry for a duplicate key.
        assertSame(secondFake, registry.sourceFor(LocationSourceType.FAKE))
        assertEquals(setOf(LocationSourceType.FAKE), registry.available)
    }
}
