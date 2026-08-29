package org.soulstone.overwatch.data.targets

/**
 * Enterprise / municipal surveillance-vendor OUI prefixes, shared by [BleOuis]
 * and [WifiOuis] (both merge [ALL] into their match sets).
 *
 * All 18 verified against the IEEE registry (Wireshark manuf snapshot,
 * 2026-08-28) — every prefix is the vendor's own registration, so unlike the
 * Flock supply-chain OUIs a hit identifies the maker directly, and the
 * drill-down label can say so.
 *
 * Radio reality (what can actually show up in a phone-side scan):
 *  - ShotSpotter: acoustic gunshot sensors; patents cite WiFi/BT/Zigbee/LPWAN
 *    backhaul. Police-exclusive product line.
 *  - WatchGuard Video (Motorola): V300/V700 body cams (BT 5.0 + WiFi) and 4RE
 *    in-car systems that run cruiser-local WiFi APs. Police-exclusive.
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

    /** OUI prefix → short human label shown in the drill-down. */
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
        "00:1c:27" to "Sunell camera"             // Sunell Electronics Co.
    )

    /** Vendors whose product line is exclusively police equipment — an
     *  over-the-air hit is ORANGE-grade on its own (same rationale as the Axon
     *  OUI): these prefixes appear on nothing consumer. */
    val POLICE_EXCLUSIVE: Set<String> = setOf(
        "d4:11:d6",  // ShotSpotter
        "00:1d:96"   // WatchGuard Video
    )

    val ALL: Set<String> = LABELS.keys

    /** Vendor label for a MAC/BSSID, or null if its OUI isn't in this table. */
    fun label(mac: String): String? {
        val lower = mac.lowercase()
        if (lower.length < 8) return null
        return LABELS[lower.substring(0, 8)]
    }

    fun isPoliceExclusive(mac: String): Boolean {
        val lower = mac.lowercase()
        if (lower.length < 8) return false
        return lower.substring(0, 8) in POLICE_EXCLUSIVE
    }
}
