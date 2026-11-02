package org.soulstone.overwatch.data.targets

/**
 * Known BLE-bearing surveillance equipment OUI prefixes (first 3 octets of MAC).
 *
 * Sources:
 *  - flock-detection (24 prefixes seen on Flock hardware, Penguin BLE included). None
 *    is registered to Flock: the IEEE registry gives 10 to Silicon Labs, 9 to Liteon,
 *    3 to Espressif and 1 to USI, all radio-module makers, and leaves d8:a0:d8
 *    unassigned. So a hit is a module that vendor also sells to everyone else.
 *    Since v0.6.0 the three Espressif blocks are gone (every ESP32 gadget carries
 *    them, SquachWatch's and all-cameras-are-beacons' field work retired them too)
 *    and the rest score below the display floor on their own ([MODULE]).
 *  - AxonCadabra (Axon body cam manufacturer prefix 00:25:DF), plus D8:1F:65, which
 *    the IEEE lists as "Private" but all-cameras-are-beacons attributed to Axon from
 *    the field: 10 MACs, five confirmed by eye, and no other device among ~21,000.
 *  - [VendorOuis] (IEEE-verified surveillance-vendor registrations), checked
 *    separately because some of its blocks are longer than three octets.
 *
 * Match by lowercased "xx:xx:xx" prefix on the device MAC.
 */
object BleOuis {

    /** Axon body cameras / dash cams (high-confidence target — flagged separately). */
    const val AXON = "00:25:df"

    /** Axon's second block, field-attributed (see the class KDoc). */
    const val AXON_FIELD = "d8:1f:65"

    /** Radio-module makers' prefixes seen on Flock hardware. Weak on their own. */
    val MODULE: Set<String> = setOf(
        // From flock-detection (24 prefixes, less the 3 Espressif blocks)
        "58:8e:81", "cc:cc:cc", "ec:1b:bd", "90:35:ea", "f0:82:c0",
        "1c:34:f1", "38:5b:44", "94:34:69", "b4:e3:f9", "3c:91:80",
        "d8:f3:bc", "80:30:49", "14:5a:fc", "9c:2f:9d", "94:08:53",
        "e4:aa:ea", "74:4c:a1", "70:c9:4e", "04:0d:84", "08:3a:88",
        "d8:a0:d8"
    )

    private fun prefix(mac: String): String? {
        val lower = mac.lowercase()
        return if (lower.length < 8) null else lower.substring(0, 8)
    }

    /** Any BLE OUI signal at all: Axon, a vendor block, or a module prefix. */
    fun matches(mac: String): Boolean =
        isAxon(mac) || VendorOuis.matches(mac) || isModule(mac)

    fun isModule(mac: String): Boolean = prefix(mac) in MODULE

    fun isAxon(mac: String): Boolean = prefix(mac).let { it == AXON || it == AXON_FIELD }
}
