package org.soulstone.overwatch.data.targets

import java.util.UUID

/**
 * Raven gunshot detector BLE service UUIDs across firmware revisions.
 * Source: flock-detection (8 UUIDs spanning FW 1.1.x, 1.2.x, 1.3.x).
 *
 * 1+ UUID match: confidence 70.  3+ UUIDs match: confidence 90.
 */
object RavenUuids {

    /** 16-bit service UUIDs expanded to full 128-bit form. */
    val ALL: Set<UUID> = setOf(
        uuid16("180a"), // Device Information (all FW)
        uuid16("3100"), // GPS Location (1.2.x+)
        uuid16("3200"), // Power Management (1.2.x+)
        uuid16("3300"), // Network Status (1.2.x+)
        uuid16("3400"), // Upload Statistics (1.3.x)
        uuid16("3500"), // Error Diagnostics (1.3.x)
        uuid16("1809"), // Health Thermometer (1.1.x)
        uuid16("1819")  // Location & Navigation (1.1.x)
    )

    fun countMatches(advertisedUuids: List<UUID>?): Int {
        if (advertisedUuids.isNullOrEmpty()) return 0
        return advertisedUuids.count { it in ALL }
    }

    private fun uuid16(short: String): UUID =
        UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
}
