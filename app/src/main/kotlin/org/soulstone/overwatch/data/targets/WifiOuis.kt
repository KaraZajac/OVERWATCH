package org.soulstone.overwatch.data.targets

/**
 * WiFi BSSID OUI prefixes seen on Flock hardware. None is Flock's own
 * registration: per the IEEE registry they belong to radio-module makers (21
 * Liteon, 3 Silicon Labs, 2 USI), b8:35:32 is unassigned and 82:6b:f2 is locally
 * administered.
 *
 * flock-you's 31-prefix list (research by NitekryDPaul + DeFlockJoplin), less three
 * since v0.6.0: Samsung's 48:27:ea (it would name a Samsung TV's Wi-Fi Direct
 * network a Flock camera, which is why SquachWatch left it out) and Espressif's
 * 3c:71:bf and a4:cf:12, which every ESP32 gadget carries. The rest score below the
 * display floor on their own: real Flock hotspots also carry a `Flock-XXXXXX` SSID
 * (803 of 803 in flock-you's WiGLE set), and that is what lifts them.
 *
 * The vendor registrations in [VendorOuis] (Flock Safety's own, WatchGuard 4RE
 * in-car APs, Openpath/Alta readers, WiFi-capable cameras and transit recorders)
 * are checked separately.
 *
 * Note: Android's WifiManager only exposes BSSID; the addr1 / wildcard-probe
 * tricks from flock-you's promiscuous mode aren't accessible — match BSSID only.
 * Flock cameras also stopped running their own access point around December 2025
 * and now only probe as clients (flock-you README), which no Android app can see.
 */
object WifiOuis {

    val MODULE: Set<String> = setOf(
        "70:c9:4e", "3c:91:80", "d8:f3:bc", "80:30:49", "b8:35:32",
        "14:5a:fc", "74:4c:a1", "08:3a:88", "9c:2f:9d", "c0:35:32",
        "94:08:53", "e4:aa:ea", "f4:6a:dd", "f8:a2:d6", "24:b2:b9",
        "00:f4:8d", "d0:39:57", "e8:d0:fc", "e0:4f:43", "b8:1e:a4",
        "70:08:94", "58:8e:81", "ec:1b:bd", "58:00:e3", "90:35:ea",
        "5c:93:a2", "64:6e:69", "82:6b:f2"
    )

    fun isModule(bssid: String): Boolean {
        val lower = bssid.lowercase()
        if (lower.length < 8) return false
        return lower.substring(0, 8) in MODULE
    }

    /** Any WiFi OUI signal: a module prefix, a vendor block, or Axon's. */
    fun matches(bssid: String): Boolean =
        isModule(bssid) || VendorOuis.matches(bssid) || BleOuis.isAxon(bssid)
}
