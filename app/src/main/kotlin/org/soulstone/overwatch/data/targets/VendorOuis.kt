package org.soulstone.overwatch.data.targets

/**
 * Enterprise / municipal surveillance-vendor address blocks, consulted by both the
 * BLE and the WiFi scanner.
 *
 * Every entry is the vendor's own IEEE registration, so unlike the module-maker
 * prefixes seen on Flock hardware a hit identifies the maker directly, and the
 * drill-down label can say so. 19 were verified against the Wireshark manuf
 * snapshot of 2026-08-28 (Flock Safety's, added in v0.5.19, against hwdata 0.411);
 * the 18 added in v0.6.0 against the IEEE MA-L, MA-M and MA-S CSVs of 2026-11-01.
 *
 * **Block sizes.** An MA-L block is 24 bits ("xx:xx:xx"); MA-M is 28 bits and MA-S
 * 36, written here with the extra nibble(s) after a colon ("58:e8:76:c",
 * "70:b3:d5:52:1"). [lookup] tries the longest form first, so a small block inside
 * a shared MA-L range names only its own holder.
 *
 * Radio reality (what can actually show up in a phone-side scan):
 *  - Flock Safety: its own registration. The Flock lists in [BleOuis] and
 *    [WifiOuis] are radio-module makers' prefixes and never carried it. Which
 *    Flock products transmit with it isn't documented; listed so any that do are
 *    named. Not police-exclusive (Flock also sells to HOAs and businesses), so it
 *    scores like any vendor OUI.
 *  - ShotSpotter: acoustic gunshot sensors; patents cite WiFi/BT/Zigbee/LPWAN
 *    backhaul. Police-exclusive product line.
 *  - WatchGuard Video (Motorola): V300/V700 body cams (BT 5.0 + WiFi) and 4RE
 *    in-car systems that run cruiser-local WiFi APs. Police-exclusive. Not
 *    WatchGuard Technologies, the firewall maker (00:01:21, 00:90:7f).
 *  - Digital Ally, Utility Inc. (BodyWorn), Kustom Signals: in-car video, body cams
 *    and police radar. Police-exclusive. Utility's 00:09:bc carries the BodyWorn
 *    remotes that all-cameras-are-beacons' source captured (10 MACs, 2025-08).
 *  - Applied Concepts (Stalker) and Decatur: police radar and lidar, but both also
 *    sell sports radar guns, so they score like any vendor.
 *  - Motorola Solutions: radios, Avigilon cameras, Vigilant ALPR. Its blocks show
 *    up mostly on fixed building hardware (27 of 27 indoor hits in
 *    all-cameras-are-beacons' logs), so they are not police-exclusive either.
 *  - Federal Signal SSG (lightbars, sirens), Fusus (Axon's real-time-crime-center
 *    camera hubs), Leonardo ELSAG / Selex ES, Neology and Genetec (ALPR makers):
 *    registry entries with no field sighting yet, listed so a radio that uses one
 *    is named.
 *  - Verkada: AD33/AD34 access readers advertise BLE for app unlock; SV-series
 *    sensors also carry BLE. Cameras are PoE.
 *  - Avigilon Alta (Openpath): access readers use BLE + WiFi (+ UWB) for
 *    mobile credentials — constant advertisers.
 *  - Axis: W110/W120 body cams have BLE 5.1 + dual-band WiFi (worn by police);
 *    fixed cameras are wired.
 *  - FLIR: handhelds carry Bluetooth (METERLiNK) + WiFi; fixed thermal is wired.
 *  - Hanwha / March Networks / GeoVision / Mobotix / Sunell: predominantly
 *    wired IP video — listed so any WiFi-capable model, transit recorder, or
 *    setup AP still flags. Effectively inert on BLE today.
 */
object VendorOuis {

    /** Block → short human label shown in the drill-down. */
    val LABELS: Map<String, String> = mapOf(
        "d4:11:d6" to "ShotSpotter sensor",       // ShotSpotter, Inc. (SoundThinking)
        "00:1d:96" to "WatchGuard police video",  // WatchGuard Video (Motorola)
        "e0:a7:00" to "Verkada device",           // Verkada Inc
        "70:1a:d5" to "Avigilon Alta device",     // Avigilon Alta (Openpath)
        "00:40:8c" to "Axis device",              // Axis Communications AB
        "ac:cc:8e" to "Axis device",              // Axis Communications AB
        "b8:a4:4f" to "Axis device",              // Axis Communications AB
        "e8:27:25" to "Axis device",              // Axis Communications AB
        "00:40:7f" to "FLIR device",              // FLIR Systems
        "00:1b:d8" to "FLIR device",              // FLIR Systems Inc
        "44:b4:23" to "Hanwha camera",            // Hanwha Vision Vietnam
        "8c:1d:55" to "Hanwha camera",            // Hanwha NxMD (Thailand)
        "e4:30:22" to "Hanwha camera",            // Hanwha Vision Vietnam
        "00:10:be" to "March Networks video",     // March Networks Corporation
        "00:12:81" to "March Networks video",     // March Networks S.p.A.
        "00:13:e2" to "GeoVision camera",         // GeoVision Inc.
        "00:03:c5" to "Mobotix camera",           // Mobotix AG
        "00:1c:27" to "Sunell camera",            // Sunell Electronics Co.
        "b4:1e:52" to "Flock Safety device",      // Flock Safety
        // v0.6.0 additions (IEEE CSVs, 2026-11-01)
        "00:23:bd" to "Digital Ally police video",     // Digital Ally, Inc.
        "00:09:bc" to "Utility BodyWorn police video", // Utility, Inc
        "00:16:ed" to "Utility BodyWorn police video", // Utility, Inc
        "58:e8:76:c" to "Kustom Signals police radar", // KUSTOM SIGNALS INC (MA-M)
        "1c:82:59:d" to "Stalker radar",               // Applied Concepts, Inc. (MA-M)
        "6c:18:11" to "Decatur radar",                 // Decatur Electronics
        "00:04:7d" to "Motorola Solutions device",     // Motorola Solutions Inc.
        "00:18:85" to "Motorola Solutions device",     // Motorola Solutions Inc.
        "00:1f:92" to "Motorola Solutions device",     // Motorola Solutions Inc.
        "4c:cc:34" to "Motorola Solutions device",     // Motorola Solutions Inc.
        "10:74:6f" to "Motorola Solutions device",     // Motorola Solutions Malaysia
        "9c:86:2b" to "Motorola Solutions device",     // Motorola Solutions Malaysia
        "b8:e2:8c" to "Motorola Solutions device",     // Motorola Solutions Malaysia
        "08:3c:03:0" to "Federal Signal emergency-vehicle device", // Federal Signal SSG (MA-M)
        "7c:83:34:4" to "Fusus police camera hub",     // Fusus (MA-M)
        "84:b3:86:5" to "Fusus police camera hub",     // Fusus (MA-M)
        "70:b3:d5:52:1" to "ELSAG / Selex ALPR device", // Selex ES Inc. (MA-S)
        "70:b3:d5:f5:e" to "ELSAG / Selex ALPR device", // Selex ES Inc. (MA-S)
        "70:b3:d5:1c:5" to "ELSAG / Selex ALPR device", // ELSAG (MA-S)
        "00:17:3d" to "Neology ALPR / tolling device", // Neology
        "00:bf:15" to "Genetec ALPR / video device",   // Genetec Inc.
        "0c:bf:15" to "Genetec ALPR / video device"    // Genetec Inc.
    )

    /** Vendors whose product line is exclusively police equipment — an
     *  over-the-air hit is ORANGE-grade on its own (same rationale as the Axon
     *  OUI): these prefixes appear on nothing consumer. */
    val POLICE_EXCLUSIVE: Set<String> = setOf(
        "d4:11:d6",   // ShotSpotter
        "00:1d:96",   // WatchGuard Video
        "00:23:bd",   // Digital Ally
        "00:09:bc",   // Utility (BodyWorn)
        "00:16:ed",   // Utility (BodyWorn)
        "58:e8:76:c"  // Kustom Signals
    )

    private val byHex: Map<String, String> = LABELS.mapKeys { it.key.replace(":", "") }
    private val policeHex: Set<String> = POLICE_EXCLUSIVE.map { it.replace(":", "") }.toSet()

    /** The registered block a MAC/BSSID falls in, as written in [LABELS], or null. */
    private fun block(mac: String): String? {
        val hex = mac.lowercase().replace(":", "")
        if (hex.length < 6) return null
        for (len in intArrayOf(9, 7, 6)) {
            if (hex.length >= len && byHex.containsKey(hex.substring(0, len))) return hex.substring(0, len)
        }
        return null
    }

    /** Vendor label for a MAC/BSSID, or null if it isn't in one of these blocks. */
    fun label(mac: String): String? = block(mac)?.let { byHex[it] }

    fun matches(mac: String): Boolean = block(mac) != null

    fun isPoliceExclusive(mac: String): Boolean = block(mac)?.let { it in policeHex } == true
}
