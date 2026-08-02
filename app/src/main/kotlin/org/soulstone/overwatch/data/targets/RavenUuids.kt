package org.soulstone.overwatch.data.targets

import java.util.UUID

/**
 * Raven gunshot detector BLE service UUIDs across firmware revisions.
 * Source: flock-detection (8 UUIDs spanning FW 1.1.x, 1.2.x, 1.3.x).
 *
 * Only Raven-specific UUIDs contribute to confidence: one match scores 70 and
 * three or more score 90. Standard Bluetooth services are contextual only.
 */
object RavenUuids {

    /** Raven-specific 16-bit service UUIDs expanded to full 128-bit form. */
    val RAVEN_SPECIFIC: Set<UUID> = setOf(
        uuid16("3100"), // GPS Location (1.2.x+)
        uuid16("3200"), // Power Management (1.2.x+)
        uuid16("3300"), // Network Status (1.2.x+)
        uuid16("3400"), // Upload Statistics (1.3.x)
        uuid16("3500")  // Error Diagnostics (1.3.x)
    )

    /** Standard services observed across Raven firmware; never identifying by themselves. */
    val STANDARD_CONTEXTUAL: Set<UUID> = setOf(
        uuid16("180a"), // Device Information (all FW)
        uuid16("1809"), // Health Thermometer (1.1.x)
        uuid16("1819")  // Location & Navigation (1.1.x)
    )

    fun countRavenSpecificMatches(advertisedUuids: List<UUID>?): Int {
        if (advertisedUuids.isNullOrEmpty()) return 0
        return advertisedUuids.toSet().count { it in RAVEN_SPECIFIC }
    }

    private fun uuid16(short: String): UUID =
        UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
}
