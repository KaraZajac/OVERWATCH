package org.soulstone.overwatch.data.targets

/**
 * Known BLE-bearing surveillance equipment OUI prefixes (first 3 octets of MAC).
 *
 * Sources:
 *  - flock-detection (24 prefixes — Flock, LiteOn, Cradlepoint, Murata, Espressif, Penguin BLE)
 *  - AxonCadabra (Axon body cam manufacturer prefix 00:25:DF)
 *
 * Match by lowercased "xx:xx:xx" prefix on the device MAC.
 */
object BleOuis {

    /** Axon body cameras / dash cams (high-confidence target — flagged separately). */
    const val AXON = "00:25:df"

    /** All known BLE-emitting surveillance OUIs, including Axon. */
    val ALL: Set<String> = setOf(
        AXON,
        // From flock-detection (24 prefixes — covers Flock direct + supply chain vendors)
        "58:8e:81", "cc:cc:cc", "ec:1b:bd", "90:35:ea", "f0:82:c0",
        "1c:34:f1", "38:5b:44", "94:34:69", "b4:e3:f9", "3c:91:80",
        "d8:f3:bc", "80:30:49", "14:5a:fc", "9c:2f:9d", "94:08:53",
        "e4:aa:ea", "48:e7:29", "c8:c9:a3", "74:4c:a1", "70:c9:4e",
        "04:0d:84", "08:3a:88", "a4:cf:12", "d8:a0:d8"
    )

    fun matches(mac: String): Boolean {
        val lower = mac.lowercase()
        if (lower.length < 8) return false
        val prefix = lower.substring(0, 8)
        return prefix in ALL
    }

    fun isAxon(mac: String): Boolean = mac.lowercase().startsWith(AXON)
}
