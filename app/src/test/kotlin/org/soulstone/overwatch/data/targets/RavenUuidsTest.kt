package org.soulstone.overwatch.data.targets

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RavenUuidsTest {

    @Test
    fun standardContextualUuidsDoNotCountAsRavenMatches() {
        listOf("180a", "1809", "1819").forEach { shortUuid ->
            assertEquals(0, RavenUuids.countRavenSpecificMatches(listOf(uuid16(shortUuid))))
        }
    }

    @Test
    fun ravenCustomFirmwareUuidsAreCounted() {
        val custom = listOf(uuid16("3100"), uuid16("3200"), uuid16("3300"))

        assertEquals(3, RavenUuids.countRavenSpecificMatches(custom))
    }

    @Test
    fun standardOnlyAdvertisementDoesNotQualifyAsRavenCandidate() {
        val standardOnly = listOf(uuid16("180a"), uuid16("1809"), uuid16("1819"))

        assertFalse(RavenUuids.countRavenSpecificMatches(standardOnly) > 0)
    }

    @Test
    fun standardUuidsDoNotIncreaseCustomMatchCount() {
        val mixed = listOf(uuid16("3100"), uuid16("3200"), uuid16("180a"))

        assertEquals(2, RavenUuids.countRavenSpecificMatches(mixed))
    }

    @Test
    fun duplicateCustomUuidsAreCountedOnce() {
        val duplicate = uuid16("3100")

        assertEquals(1, RavenUuids.countRavenSpecificMatches(listOf(duplicate, duplicate)))
    }

    @Test
    fun unrelatedUuidsDoNotMatch() {
        assertEquals(
            0,
            RavenUuids.countRavenSpecificMatches(
                listOf(UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb"))
            )
        )
    }

    @Test
    fun nullAndEmptyAdvertisementsHaveNoMatches() {
        assertEquals(0, RavenUuids.countRavenSpecificMatches(null))
        assertEquals(0, RavenUuids.countRavenSpecificMatches(emptyList()))
    }

    private fun uuid16(short: String): UUID =
        UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
}
