package org.soulstone.overwatch.data.targets

import java.util.UUID

/**
 * Raven gunshot detector BLE service UUIDs across firmware revisions.
 * Source: flock-detection (8 UUIDs spanning FW 1.1.x, 1.2.x, 1.3.x).
 *
 * Five are Raven's own and count on sight. The other three are standard Bluetooth
 * SIG services that FW 1.1.x happens to advertise — Device Information, Health
 * Thermometer, Location & Navigation — and plenty of ordinary gear advertises one
 * of them on its own: a Bluetooth thermometer sends 1809. Counting those singly
 * labelled thermometers "Raven gunshot detector" at ORANGE, so since v0.5.17 they
 * count only as the full set of three, which is FW 1.1.x's signature.
 *
 * 1–2 counted UUIDs: confidence 70.  3+: confidence 90.
 */
object RavenUuids {

    /** Raven's own service UUIDs. Any one of them is evidence. */
    val SPECIFIC: Set<UUID> = setOf(
        uuid16("3100"), // GPS Location (1.2.x+)
        uuid16("3200"), // Power Management (1.2.x+)
        uuid16("3300"), // Network Status (1.2.x+)
        uuid16("3400"), // Upload Statistics (1.3.x)
        uuid16("3500")  // Error Diagnostics (1.3.x)
    )

    /** Generic SIG services FW 1.1.x advertises. Evidence only as a complete set. */
    val GENERIC_FW_1_1: Set<UUID> = setOf(
        uuid16("180a"), // Device Information (all FW)
        uuid16("1809"), // Health Thermometer (1.1.x)
        uuid16("1819")  // Location & Navigation (1.1.x)
    )

    /** All eight, for the screen-off scan filters: a filter only decides what gets
     *  delivered; [countMatches] decides what counts. */
    val ALL: Set<UUID> = SPECIFIC + GENERIC_FW_1_1

    /**
     * How many advertised UUIDs count as Raven evidence: each Raven-specific one,
     * plus the three generic ones only when all three are present together.
     */
    fun countMatches(advertisedUuids: List<UUID>?): Int {
        if (advertisedUuids.isNullOrEmpty()) return 0
        val advertised = advertisedUuids.toSet()
        val specific = advertised.count { it in SPECIFIC }
        val generic = if (advertised.containsAll(GENERIC_FW_1_1)) GENERIC_FW_1_1.size else 0
        return specific + generic
    }

    private fun uuid16(short: String): UUID =
        UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
}
