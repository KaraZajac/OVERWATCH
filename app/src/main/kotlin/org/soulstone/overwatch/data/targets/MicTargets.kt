package org.soulstone.overwatch.data.targets

import java.util.UUID

/**
 * Curated targets for "device with a microphone in your space" detection.
 *
 * Scope is intentionally narrow — well-known smart-home OEMs whose devices stay
 * in fixed locations and continuously listen, plus body-worn smart glasses
 * (Meta Ray-Ban, Snap Spectacles) that record the space around them. Apple
 * manufacturer id 0x004C is deliberately excluded because every iPhone, AirPod,
 * and Apple Watch advertises it; a coffee shop full of phones must not light up
 * the alarm.
 *
 * Detection vectors collected from public OUI registries (Wireshark/IEEE),
 * device-setup advertisement docs, and — for the smart-glasses family — the
 * identifier set curated by **Nearby Glasses** (Yves Jeanrenaud,
 * https://github.com/yjeanrenaud/yj_nearbyglasses, AGPL-3.0). What is taken
 * from that project is its published list of *identifiers* (company ids, the
 * HeyCyan service UUID, name tokens), i.e. facts about the radio protocol,
 * not its code; every company id below was re-verified against the Bluetooth
 * SIG assigned-numbers registry on 2026-09-21.
 */
object MicTargets {

    enum class Family { ECHO, RING, BLINK, GOOGLE, SONOS, HIDDEN_CAM, GLASSES }

    /** Bluetooth SIG company identifiers for "voice/smart-home" device families. */
    private val MFG_GOOGLE = 0x00E0
    private val MFG_AMAZON = 0x0171
    /**
     * 0x05A7 is **Sonos Inc** per the Bluetooth SIG registry — earlier
     * revisions labelled it "Yingxin / cheap-spy-cam", which would have tagged
     * a Sonos speaker as a hidden camera. Sonos does belong here (the Era/One
     * lines carry always-on microphones) but under an honest label.
     */
    private val MFG_SONOS = 0x05A7

    /**
     * Bluetooth SIG company identifiers for camera-bearing smart glasses. The
     * manufacturer-id field is the reliable vector — device names and service
     * UUIDs are inconsistent across advertising frames (per the open-source
     * Nearby Glasses project, which this mirrors). Reliable to identify, but weak
     * to alert on: alone it scores 30, under the 40 a hit needs to be shown, so a
     * frame carrying only the company id shows only very close (above −50 dBm)
     * with a rise-peak-fall signal: 30 + 5 + 8. A name token, the HeyCyan
     * service UUID or Ray-Ban Meta's own 0xFD5F shows on its own. Caveat: these
     * ids are manufacturer-wide, so Meta's 0x01AB also matches other Meta radios.
     * 0x058E (Meta Platforms Technologies) was here until v0.6.0: it is what Quest
     * headsets send, and SquachWatch's two captures of real Ray-Ban Meta glasses
     * (2026-07 and 2026-10) sent 0x01AB with 0xFD5F instead. Luxottica in BLE
     * mfg data is essentially only their *smart* eyewear — plain sunglasses
     * have no radio. Brands without a dedicated SIG company id (RayNeo — which
     * rides under TCL's 0x0BC6, too broad to use — plus XREAL, Rokid) advertise
     * under a chipset vendor's id, so those are caught by distinctive BLE-name
     * hints below instead.
     *
     * Deliberately NOT matched: 0x05D6 (Zhuhai Jieli). Nearby Glasses lists it
     * for the Rogbird VisionPro and Rollme VistaView, and its own notes say
     * why it is a problem: it is the id of the *Jieli JL70xx Bluetooth
     * chipset*, which sits inside an enormous share of cheap TWS earbuds,
     * speakers and toys. Matching it would label every one of those "Smart
     * glasses" — the same reason TCL's 0x0BC6 is excluded. If distinctive
     * name strings for those two products surface (Nearby Glasses issue #56
     * is collecting them), they belong in the name hints, not here.
     */
    private val MFG_META = 0x01AB          // Meta Platforms, Inc. (ex-Facebook)
    private val MFG_LUXOTTICA = 0x0D53     // Luxottica — Ray-Ban / Oakley Meta frames
    private val MFG_SNAP = 0x03C2          // Snap Inc. — Spectacles
    private val MFG_VUZIX = 0x060C         // Vuzix — enterprise / AR smart glasses

    /** Echo/Alexa Voice Service GATT (FE03 — assigned to Amazon Lab126). */
    private val UUID_AVS = UUID.fromString("0000fe03-0000-1000-8000-00805f9b34fb")

    /**
     * HeyCyan smart-glasses SDK primary service. Fixed on the software side of
     * every HeyCyan-based frame — notably the Nilox Smart AI Glasses sold by
     * ALDI/Hofer — and the one signature those glasses expose that is not a
     * broad chipset-vendor company id. Credit: Nearby Glasses, which traced it
     * via the HeyCyan SDK (github.com/ebowwa/HeyCyanSmartGlassesSDK). It can
     * appear either in the advertised service list or as a service-data key,
     * so BleScanner feeds both into the UUID list this is checked against.
     */
    private val UUID_HEYCYAN = UUID.fromString("7905fff0-b5ce-4e99-a40f-4b1e122d00d0")

    /** Ray-Ban Meta's own service UUID, 16-bit 0xFD5F (SIG: Meta Platforms
     *  Technologies). SquachWatch: the one signature specific to the glasses. */
    private val UUID_RAYBAN_META = UUID.fromString("0000fd5f-0000-1000-8000-00805f9b34fb")

    /** Meta Platforms' other two SIG member UUIDs (FEB7, FEB8) and Snap's (FE45).
     *  Company-wide rather than product-specific, so they weigh like a company id. */
    private val UUIDS_GLASSES_WEAK: Set<UUID> = setOf(
        UUID.fromString("0000feb7-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("0000feb8-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("0000fe45-0000-1000-8000-00805f9b34fb")
    )

    /** Every exact service UUID this object recognises, for screen-off ScanFilters. */
    val SERVICE_UUIDS: Set<UUID> = setOf(UUID_RAYBAN_META, UUID_HEYCYAN, UUID_AVS)

    /** Lab126 (Amazon — Echo, Ring, Fire TV) WiFi/BLE OUIs. All registered to
     *  Amazon except a8:51:5b, which is Samsung's; it is kept, so a Samsung
     *  device with it can be labelled Amazon gear. A lone OUI hit (30) shows only
     *  very close with a rise-peak-fall signal. */
    private val OUIS_AMAZON: Set<String> = setOf(
        "0c:47:c9", "38:f7:3d", "44:65:0d", "50:dc:e7", "78:e1:03",
        "a8:51:5b", "f0:27:2d", "f0:81:73", "f0:d2:f1",
        "fc:65:de", "fc:a1:83", "ac:63:be", "00:bb:3a"
    )

    /** Google (Nest, Home, Chromecast) WiFi/BLE OUIs. Five are not Google's or
     *  Nest's in the IEEE registry: 94:eb:cd (BlackBerry), 6c:ad:f8 (AzureWave, a
     *  WiFi-module maker), 94:c9:60 (Zhongshan B&T), f4:f1:9e (Wistron, a contract
     *  manufacturer), and fc:9f:e9 is unassigned. They are kept, so other devices
     *  with them can be labelled Google gear; a lone OUI hit (30) shows only very
     *  close with a rise-peak-fall signal. */
    private val OUIS_GOOGLE: Set<String> = setOf(
        "f8:8f:ca", "f4:f5:e8", "94:eb:cd", "64:16:66", "fc:9f:e9",
        "1c:f2:9a", "08:9e:08", "20:df:b9", "30:fd:38", "48:d6:d5",
        "54:60:09", "6c:ad:f8", "70:3a:cb", "94:c9:60", "f4:f1:9e"
    )

    /** Radio-module makers' OUIs, common in cheap cameras and mics but also in
     *  countless other gadgets: high-noise, and a lone hit (30) shows only very
     *  close with a rise-peak-fall signal. Owners per the IEEE registry; earlier
     *  comments named other companies. */
    private val OUIS_HIDDEN_CAM: Set<String> = setOf(
        "fc:b4:67",   // Espressif
        "00:e0:4c",   // Realtek (used in many cheap cams)
        "dc:4f:22",   // Espressif
        "a4:c1:38",   // Telink (often inside cheap BLE mics)
        "8c:ce:4e"    // Espressif
    )

    /** Ring's own blocks: the 13 MA-L registrations of Ring LLC, plus "Ring
     *  Solutions" b0:09:da, which sat in the Amazon set until v0.6.0 (IEEE CSV,
     *  2026-11-01). Ring doorbells and cameras carry microphones. */
    private val OUIS_RING: Set<String> = setOf(
        "00:b4:63", "18:7f:88", "24:2b:d6", "34:3e:a4", "50:e4:67",
        "54:e0:19", "5c:47:5e", "64:9a:63", "90:48:6c", "9c:76:13",
        "ac:9f:c3", "c4:db:ad", "cc:3b:fb", "b0:09:da"
    )

    /** "Blink by Amazon" — all six of its MA-L registrations. */
    private val OUIS_BLINK: Set<String> = setOf(
        "3c:a0:70", "70:ad:43", "74:13:48", "74:ab:93", "c8:19:d8", "f0:74:c1"
    )

    /** Glasses makers' own Wi-Fi blocks: Luxottica Group (Ray-Ban / Oakley Meta)
     *  and Snap, Inc. (Spectacles). */
    private val OUIS_GLASSES: Set<String> = setOf("98:59:49", "98:a4:0e")

    private val ALL_OUIS: Set<String> =
        OUIS_AMAZON + OUIS_GOOGLE + OUIS_HIDDEN_CAM + OUIS_RING + OUIS_BLINK + OUIS_GLASSES

    /**
     * Short everyday words. They count only as a word of their own ("Echo Dot",
     * "Amazon Echo", "Google Nest Hub"), never inside a longer one: as plain
     * substrings they named "Nestor's Buds" a Google Nest and an "AmazonBasics
     * Mouse" an Echo. Case-sensitive.
     */
    private val BLE_NAME_WORDS: List<Pair<String, Family>> = listOf(
        "Echo" to Family.ECHO,
        "Amazon" to Family.ECHO,
        "Nest" to Family.GOOGLE
    )

    /** "Ring" has to start the name as well: a "Galaxy Ring" is jewellery. */
    private const val BLE_NAME_RING = "Ring"

    /** Case-sensitive substrings — distinct enough to avoid false positives. */
    private val BLE_NAME_HINTS: List<Pair<String, Family>> = listOf(
        "echo-" to Family.ECHO,
        "FireTV" to Family.ECHO,
        "Doorbell" to Family.RING,
        "GoogleHome" to Family.GOOGLE,
        "Chromecast" to Family.GOOGLE,
        "Google-Home" to Family.GOOGLE,
        "Spectacles" to Family.GLASSES,
        "Ray-Ban" to Family.GLASSES,
        "RayNeo" to Family.GLASSES,
        "Vuzix" to Family.GLASSES,
        "XREAL" to Family.GLASSES,
        "Rokid" to Family.GLASSES
    )

    /**
     * Case-insensitive glasses tokens, matched against the lowercased name.
     * Mirrors Nearby Glasses' name check. Kept separate from BLE_NAME_HINTS,
     * which is case-sensitive on purpose so short generic words ("echo") don't
     * fire on unrelated names; none of these can plausibly collide.
     */
    private val GLASSES_NAME_TOKENS_CI: List<String> = listOf(
        "rayban", "ray-ban", "ray ban", "heycyan"
    )

    /** Set-up network names, matched at the start of the SSID in any case. Matched
     *  anywhere, "Ring-" was in "Spring-Guest" and "Engineering-5G", "Ring_" in
     *  "Catering_WiFi" and "Nest_" in "Honest_WiFi". */
    private val SSID_HINTS: List<Pair<String, Family>> = listOf(
        "Amazon-" to Family.ECHO,
        "Echo-" to Family.ECHO,
        "Ring-" to Family.RING,
        "Ring_" to Family.RING,
        "Nest_" to Family.GOOGLE,
        "GoogleHome" to Family.GOOGLE,
        "Chromecast" to Family.GOOGLE
    )

    data class Match(val family: Family, val reason: String)

    fun matchOui(mac: String?): Family? {
        if (mac.isNullOrBlank() || mac.length < 8) return null
        val prefix = mac.lowercase().substring(0, 8)
        return when (prefix) {
            in OUIS_RING -> Family.RING
            in OUIS_BLINK -> Family.BLINK
            in OUIS_GLASSES -> Family.GLASSES
            in OUIS_AMAZON -> Family.ECHO    // Amazon OUIs cover both Echo and Ring
            in OUIS_GOOGLE -> Family.GOOGLE
            in OUIS_HIDDEN_CAM -> Family.HIDDEN_CAM
            else -> null
        }
    }

    fun isMicOui(mac: String?): Boolean = matchOui(mac) != null

    fun matchBleName(name: String?): Match? {
        if (name.isNullOrBlank()) return null
        for ((word, family) in BLE_NAME_WORDS) {
            if (hasWord(name, word)) return Match(family, "name:$word")
        }
        if (hasWord(name, BLE_NAME_RING, atStartOnly = true)) {
            return Match(Family.RING, "name:$BLE_NAME_RING")
        }
        for ((needle, family) in BLE_NAME_HINTS) {
            if (name.contains(needle, ignoreCase = false)) {
                return Match(family, "name:$needle")
            }
        }
        val lower = name.lowercase()
        for (token in GLASSES_NAME_TOKENS_CI) {
            if (lower.contains(token)) return Match(Family.GLASSES, "name:$token")
        }
        return null
    }

    fun matchSsid(ssid: String?): Match? {
        if (ssid.isNullOrBlank()) return null
        for ((needle, family) in SSID_HINTS) {
            if (ssid.startsWith(needle, ignoreCase = true)) {
                return Match(family, "ssid:$needle")
            }
        }
        return null
    }

    /** True when [word] stands in [name] with no letter or digit before it and no
     *  letter after it ("Nest Cam", "Nest-1A2B", "Nest1234"; not "Nestor"). */
    private fun hasWord(name: String, word: String, atStartOnly: Boolean = false): Boolean {
        var i = name.indexOf(word)
        while (i >= 0) {
            val end = i + word.length
            val clearBefore = i == 0 || !name[i - 1].isLetterOrDigit()
            val clearAfter = end == name.length || !name[end].isLetter()
            if (clearBefore && clearAfter && (i == 0 || !atStartOnly)) return true
            if (atStartOnly) return false
            i = name.indexOf(word, i + 1)
        }
        return false
    }

    /**
     * Every company id this object recognises. Exposed so BleScanner can turn
     * them into ScanFilters for screen-off scanning, where an unfiltered scan
     * is silently suspended by the Bluetooth stack. **Order matters**: the
     * filter list is capped at 16 hardware slots and this set is consumed
     * last, so what falls off the end is what is listed last. Body-worn
     * cameras (glasses) go first — they are the thing you want a pocket alert
     * for; a fixed Echo or Sonos in your own home is not.
     */
    val COMPANY_IDS: Set<Int> = setOf(
        MFG_META, MFG_LUXOTTICA, MFG_SNAP, MFG_VUZIX,
        MFG_AMAZON, MFG_GOOGLE, MFG_SONOS
    )

    fun matchManufacturer(companyId: Int?): Family? = when (companyId) {
        MFG_AMAZON -> Family.ECHO
        MFG_GOOGLE -> Family.GOOGLE
        MFG_SONOS -> Family.SONOS
        MFG_META, MFG_LUXOTTICA, MFG_SNAP, MFG_VUZIX -> Family.GLASSES
        else -> null
    }

    /** Family implied by an advertised service UUID, or null. */
    fun matchService(advertisedUuids: List<UUID>?): Family? {
        if (advertisedUuids.isNullOrEmpty()) return null
        return when {
            advertisedUuids.contains(UUID_RAYBAN_META) -> Family.GLASSES
            advertisedUuids.contains(UUID_HEYCYAN) -> Family.GLASSES
            advertisedUuids.contains(UUID_AVS) -> Family.ECHO
            else -> null
        }
    }

    /** Family implied by a company-wide glasses-maker UUID (Meta FEB7/FEB8, Snap
     *  FE45), or null. Weighs like a company id, not like [matchService]. */
    fun matchWeakService(advertisedUuids: List<UUID>?): Family? {
        if (advertisedUuids.isNullOrEmpty()) return null
        return if (advertisedUuids.any { it in UUIDS_GLASSES_WEAK }) Family.GLASSES else null
    }

    /** Cheap pre-filter for the BLE scanner — true if any mic signal could match. */
    fun couldBeMicBle(
        mac: String?,
        name: String?,
        advertisedUuids: List<UUID>?,
        companyId: Int?
    ): Boolean {
        if (isMicOui(mac)) return true
        if (matchBleName(name) != null) return true
        if (matchManufacturer(companyId) != null) return true
        if (matchService(advertisedUuids) != null) return true
        if (matchWeakService(advertisedUuids) != null) return true
        return false
    }

    /** Cheap pre-filter for the WiFi scanner. */
    fun couldBeMicWifi(bssid: String?, ssid: String?): Boolean {
        if (isMicOui(bssid)) return true
        if (matchSsid(ssid) != null) return true
        return false
    }

    fun familyLabel(f: Family): String = when (f) {
        Family.ECHO -> "Amazon Echo / Ring"
        Family.RING -> "Ring"
        Family.GOOGLE -> "Google Nest / Home"
        Family.SONOS -> "Sonos speaker"
        Family.BLINK -> "Blink camera"
        Family.HIDDEN_CAM -> "Possible hidden mic / cam"
        Family.GLASSES -> "Smart glasses"
    }
}
