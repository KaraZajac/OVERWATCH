package org.soulstone.overwatch.data.targets

/**
 * String-pattern signatures: BLE local names + WiFi SSIDs.
 * Sources: flock-detection (BLE names + SSID generic), flock-you (specific Flock-XXXX format).
 */
object Patterns {

    /** BLE advertised local names that flag a target. Case-sensitive substring match. */
    val BLE_NAME_PATTERNS: List<String> = listOf(
        "FS Ext Battery",
        "Penguin",
        "Flock",
        "Pigvision",
        "FlockCam",
        "FS-"
    )

    /** Generic SSID substrings (case-insensitive). */
    val SSID_GENERIC: List<String> = listOf(
        "flock", "FS_", "Penguin", "Pigvision", "FlockOS", "flocksafety", "FS Ext Battery"
    )

    /** Specific Flock SSID format: "Flock-" followed by exactly 4 hex digits. */
    val SSID_FLOCK_REGEX = Regex("^Flock-[0-9A-Fa-f]{4}$")

    fun bleNameMatch(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        return BLE_NAME_PATTERNS.any { name.contains(it, ignoreCase = false) }
    }

    /** Penguin post-March-2025 firmware advertises a bare 8-12 digit decimal ID. */
    fun isPenguinNumeric(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        if (name.length !in 8..12) return false
        return name.all { it.isDigit() }
    }

    fun ssidGenericMatch(ssid: String?): Boolean {
        if (ssid.isNullOrBlank()) return false
        return SSID_GENERIC.any { ssid.contains(it, ignoreCase = true) }
    }

    fun ssidFlockFormat(ssid: String?): Boolean {
        if (ssid.isNullOrBlank()) return false
        return SSID_FLOCK_REGEX.matches(ssid)
    }
}
