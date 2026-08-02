package org.soulstone.overwatch.data.targets

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class RavenUuidsTest {

    @Test
    fun standardDeviceInformationUuidIsRecognized() {
        val standard = uuid16("180a")

        assertEquals(1, RavenUuids.countMatches(listOf(standard)))
    }

    @Test
    fun ravenCustomFirmwareUuidsAreCounted() {
        val custom = listOf(uuid16("3100"), uuid16("3200"), uuid16("3300"))

        assertEquals(3, RavenUuids.countMatches(custom))
    }

    @Test
    fun unrelatedUuidsDoNotMatch() {
        assertEquals(
            0,
            RavenUuids.countMatches(
                listOf(UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb"))
            )
        )
    }

    @Test
    fun nullAndEmptyAdvertisementsHaveNoMatches() {
        assertEquals(0, RavenUuids.countMatches(null))
        assertEquals(0, RavenUuids.countMatches(emptyList()))
    }

    private fun uuid16(short: String): UUID =
        UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
}
