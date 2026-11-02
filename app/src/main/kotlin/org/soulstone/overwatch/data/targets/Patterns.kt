package org.soulstone.overwatch.data.targets

/**
 * String-pattern signatures: BLE local names + WiFi SSIDs.
 *
 * Sources: flock-detection (BLE names, generic SSIDs), flock-you (hotspot format and
 * its WiGLE datasets), all-cameras-are-beacons (field captures). Since v0.6.0 every
 * Flock pattern is anchored to the form real devices were seen to use, because the
 * loose substrings it replaced matched home networks and missed real hotspots:
 *
 *  - flock-you's WiGLE set holds 803 Flock hotspot SSIDs. Every one is `Flock-` + six
 *    hex digits (`FLOCK-` in a few), and 780 end in the BSSID's last three bytes. The
 *    old `Flock-` + four-hex regex matched none of them.
 *  - Its 17,410 Penguin records are all exactly `Penguin-` + ten digits.
 *  - No capture anywhere shows a device named `Pigvision`; flock-you's "Pigvision"
 *    file lists camera locations, not radio sightings. Dropped.
 */
object Patterns {

    /** Flock's management hotspot: "Flock-" plus the BSSID's last three bytes in hex.
     *  Flock turned it off around December 2025 (flock-you README), so it mostly
     *  finds older units. */
    private val SSID_FLOCK_REGEX = Regex("^flock-([0-9a-f]{6})$", RegexOption.IGNORE_CASE)

    /** A camera's own field hotspot, "Flock Camera net." (flock-you issue #43). */
    private const val SSID_FLOCK_HOTSPOT_PREFIX = "Flock Camera"

    /** The network Raven units join, per GainSec's white paper. Exact match only, so
     *  "Flockhart Family WiFi" is safe. ESP32 Marauder's wardrive mode beacons this
     *  same name from an Espressif address; [HackerTargets.isMarauderFlockDecoy]
     *  claims those first. */
    private const val SSID_FLOCK_EXACT = "Flock"

    /** Other Flock tokens from flock-detection, matched case-insensitively. */
    private val SSID_GENERIC_TOKENS: List<String> = listOf("flocksafety", "flockos", "fs ext battery")

    /** Generic prefixes from flock-detection, matched case-insensitively at the start only. */
    private val SSID_GENERIC_PREFIXES: List<String> = listOf("flock-", "flock_", "fs_")

    private val PENGUIN_REGEX = Regex("^Penguin-\\d{10}$")

    /** A Flock accessory: "FS-" and 4–12 hex digits (field capture at Flock sites,
     *  via SquachWatch and all-cameras-are-beacons). The old bare "FS-" substring
     *  matched any name containing it. */
    private val FS_NAME_REGEX = Regex("^FS-[0-9A-Fa-f]{4,12}$")

    /** Case-sensitive substring matches on a BLE local name. Raven units are
     *  discovered by a name containing "Flock" (GainSec BirdShot). */
    private val BLE_NAME_SUBSTRINGS: List<String> = listOf("Flock")

    /** Exact BLE local names. */
    private val BLE_NAME_EXACT: Set<String> = setOf("FS Ext Battery")

    fun bleNameMatch(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        if (name in BLE_NAME_EXACT) return true
        if (PENGUIN_REGEX.matches(name) || FS_NAME_REGEX.matches(name)) return true
        return BLE_NAME_SUBSTRINGS.any { name.contains(it, ignoreCase = false) }
    }

    /** A bare ten-digit name, the form Penguin packs reportedly took after March
     *  2025. Any gadget can carry one (a phone advertising `0102000000` matched in
     *  all-cameras-are-beacons' logs), so it stays a weak signal. Narrowed from
     *  8–12 digits to the ten that `Penguin-` names carry. */
    fun isPenguinNumeric(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        return name.length == 10 && name.all { it in '0'..'9' }
    }

    fun ssidGenericMatch(ssid: String?): Boolean {
        if (ssid.isNullOrBlank()) return false
        if (ssid.equals(SSID_FLOCK_EXACT, ignoreCase = true)) return true
        if (PENGUIN_REGEX.matches(ssid)) return true
        val lower = ssid.lowercase()
        if (SSID_GENERIC_PREFIXES.any { lower.startsWith(it) }) return true
        return SSID_GENERIC_TOKENS.any { lower.contains(it) }
    }

    /** True for the hotspot format: `Flock-` and six hex digits. */
    fun ssidFlockFormat(ssid: String?): Boolean {
        if (ssid.isNullOrBlank()) return false
        return SSID_FLOCK_REGEX.matches(ssid)
    }

    /** True when a `Flock-XXXXXX` SSID's hex is the BSSID's last three bytes, as it
     *  was for 780 of flock-you's 803 records. */
    fun ssidFlockMatchesBssid(ssid: String?, bssid: String?): Boolean {
        if (ssid.isNullOrBlank() || bssid.isNullOrBlank()) return false
        val hex = SSID_FLOCK_REGEX.matchEntire(ssid)?.groupValues?.get(1) ?: return false
        val tail = bssid.replace(":", "").takeLast(6)
        return tail.equals(hex, ignoreCase = true)
    }

    /** A Flock camera's own hotspot, "Flock Camera net." and variants. */
    fun ssidFlockHotspot(ssid: String?): Boolean {
        if (ssid.isNullOrBlank()) return false
        return ssid.startsWith(SSID_FLOCK_HOTSPOT_PREFIX, ignoreCase = true)
    }

    /** Exactly "Flock", any case. */
    fun ssidIsBareFlock(ssid: String?): Boolean =
        ssid != null && ssid.equals(SSID_FLOCK_EXACT, ignoreCase = true)
}
