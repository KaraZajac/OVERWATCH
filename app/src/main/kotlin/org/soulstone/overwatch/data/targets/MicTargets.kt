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

    enum class Family { ECHO, RING, GOOGLE, SONOS, HIDDEN_CAM, GLASSES }

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
     * Nearby Glasses project, which this mirrors). Caveat: these ids are
     * manufacturer-wide, so Meta's also match Quest VR headsets (still a
     * camera/mic device, so an acceptable secondary signal). Luxottica in BLE
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
    private val MFG_META_TECH = 0x058E     // Meta Platforms Technologies (Reality Labs; also Quest)
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

    /** Every advertised-service UUID this object recognises, for screen-off ScanFilters. */
    val SERVICE_UUIDS: Set<UUID> = setOf(UUID_AVS, UUID_HEYCYAN)

    /** Lab126 (Amazon — Echo, Ring, Fire TV) WiFi/BLE OUIs. */
    private val OUIS_AMAZON: Set<String> = setOf(
        "0c:47:c9", "38:f7:3d", "44:65:0d", "50:dc:e7", "78:e1:03",
        "a8:51:5b", "b0:09:da", "f0:27:2d", "f0:81:73", "f0:d2:f1",
        "fc:65:de", "fc:a1:83", "ac:63:be", "00:bb:3a"
    )

    /** Google (Nest, Home, Chromecast) WiFi/BLE OUIs. */
    private val OUIS_GOOGLE: Set<String> = setOf(
        "f8:8f:ca", "f4:f5:e8", "94:eb:cd", "64:16:66", "fc:9f:e9",
        "1c:f2:9a", "08:9e:08", "20:df:b9", "30:fd:38", "48:d6:d5",
        "54:60:09", "6c:ad:f8", "70:3a:cb", "94:c9:60", "f4:f1:9e"
    )

    /** Generic Chinese hidden-cam / smart-mic vendor OUIs (high-noise; opt-in). */
    private val OUIS_HIDDEN_CAM: Set<String> = setOf(
        "fc:b4:67",   // Yingxin / SmartLife mini cams
        "00:e0:4c",   // Realtek (used in many cheap cams)
        "dc:4f:22",   // Tuya-affiliated module vendors
        "a4:c1:38",   // Telink (often inside cheap BLE mics)
        "8c:ce:4e"    // Shenzhen iComm — frequent in spy-cam BOMs
    )

    private val ALL_OUIS: Set<String> = OUIS_AMAZON + OUIS_GOOGLE + OUIS_HIDDEN_CAM

    /** Case-sensitive substrings — distinct enough to avoid false positives. */
    private val BLE_NAME_HINTS: List<Pair<String, Family>> = listOf(
        "Echo" to Family.ECHO,
        "echo-" to Family.ECHO,
        "FireTV" to Family.ECHO,
        "Amazon" to Family.ECHO,
        "Ring-" to Family.RING,
        "Ring " to Family.RING,
        "Doorbell" to Family.RING,
        "Nest" to Family.GOOGLE,
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
            in OUIS_AMAZON -> Family.ECHO    // Amazon OUIs cover both Echo and Ring
            in OUIS_GOOGLE -> Family.GOOGLE
            in OUIS_HIDDEN_CAM -> Family.HIDDEN_CAM
            else -> null
        }
    }

    fun isMicOui(mac: String?): Boolean = matchOui(mac) != null

    fun matchBleName(name: String?): Match? {
        if (name.isNullOrBlank()) return null
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
            if (ssid.contains(needle, ignoreCase = true)) {
                return Match(family, "ssid:$needle")
            }
        }
        return null
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
        MFG_META, MFG_META_TECH, MFG_LUXOTTICA, MFG_SNAP, MFG_VUZIX,
        MFG_AMAZON, MFG_GOOGLE, MFG_SONOS
    )

    fun matchManufacturer(companyId: Int?): Family? = when (companyId) {
        MFG_AMAZON -> Family.ECHO
        MFG_GOOGLE -> Family.GOOGLE
        MFG_SONOS -> Family.SONOS
        MFG_META, MFG_META_TECH, MFG_LUXOTTICA, MFG_SNAP, MFG_VUZIX -> Family.GLASSES
        else -> null
    }

    /** Family implied by an advertised service UUID, or null. */
    fun matchService(advertisedUuids: List<UUID>?): Family? {
        if (advertisedUuids.isNullOrEmpty()) return null
        return when {
            advertisedUuids.contains(UUID_HEYCYAN) -> Family.GLASSES
            advertisedUuids.contains(UUID_AVS) -> Family.ECHO
            else -> null
        }
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
        Family.HIDDEN_CAM -> "Possible hidden mic / cam"
        Family.GLASSES -> "Smart glasses"
    }
}
