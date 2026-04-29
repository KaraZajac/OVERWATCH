package org.soulstone.overwatch.data.targets

/**
 * WiFi BSSID OUI prefixes for Flock Safety infrastructure.
 *
 * 31-prefix superset from flock-you (research by NitekryDPaul + DeFlockJoplin),
 * plus the overlap with flock-detection's 24-prefix list.
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
        "90:35:ea", "5c:93:a2", "64:6e:69", "48:27:ea", "a4:cf:12",
        "82:6b:f2"
    )

    fun matches(bssid: String): Boolean {
        val lower = bssid.lowercase()
        if (lower.length < 8) return false
        return lower.substring(0, 8) in ALL
    }
}
