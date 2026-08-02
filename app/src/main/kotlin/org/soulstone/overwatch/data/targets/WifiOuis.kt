package org.soulstone.overwatch.data.targets

/**
 * Globally assigned WiFi BSSID OUI prefixes for Flock Safety infrastructure.
 *
 * Vendor-prefix subset from flock-you (research by NitekryDPaul + DeFlockJoplin),
 * plus the overlap with flock-detection's 24-prefix list. Locally administered
 * prefixes from that research are kept separately in [KnownLocalWifiPrefixes].
 *
 * Note: Android's WifiManager only exposes BSSID; the addr1 / wildcard-probe
 * tricks from flock-you's promiscuous mode aren't accessible — match BSSID only.
 */
object WifiOuis {

    val ALL: Set<String> = setOf(
        "70:c9:4e", "3c:91:80", "d8:f3:bc", "80:30:49", "b8:35:32",
        "14:5a:fc", "74:4c:a1", "08:3a:88", "9c:2f:9d", "c0:35:32",
        "94:08:53", "e4:aa:ea", "f4:6a:dd", "f8:a2:d6", "24:b2:b9",
        "00:f4:8d", "d0:39:57", "e8:d0:fc", "e0:4f:43", "b8:1e:a4",
        "70:08:94", "58:8e:81", "ec:1b:bd", "3c:71:bf", "58:00:e3",
        "90:35:ea", "5c:93:a2", "64:6e:69", "48:27:ea", "a4:cf:12"
    )

    fun matches(bssid: String): Boolean = matchesPrefix(bssid, ALL)
}

/**
 * Target-associated prefixes that set the locally administered bit.
 *
 * These are weaker research signals, not globally assigned vendor identities.
 * The prefix below comes from the flock-you set attributed to research by
 * NitekryDPaul and DeFlockJoplin.
 */
object KnownLocalWifiPrefixes {

    val ALL: Set<String> = setOf(
        // Locally administered; associated with the research target, not a vendor OUI.
        "82:6b:f2"
    )

    fun matches(bssid: String): Boolean = matchesPrefix(bssid, ALL)
}

/** Classifies the locally administered bit without changing OUI match policy. */
internal fun isLocallyAdministered(mac: String): Boolean {
    val firstOctet = mac.substringBefore(':').toIntOrNull(16) ?: return false
    return firstOctet and 0x02 != 0
}

private fun matchesPrefix(address: String, prefixes: Set<String>): Boolean {
    val lower = address.lowercase()
    if (lower.length < 8) return false
    return lower.substring(0, 8) in prefixes
}
