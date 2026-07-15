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
 * Detection vectors collected from public OUI registries (Wireshark/IEEE)
 * and device-setup advertisement docs.
 */
object MicTargets {

    enum class Family { ECHO, RING, GOOGLE, HIDDEN_CAM, GLASSES }

    /** Bluetooth SIG company identifiers for "voice/smart-home" device families. */
    private val MFG_GOOGLE = 0x00E0
    private val MFG_AMAZON = 0x0171
    /** Yingxin / cheap-spy-cam mfg id seen in field reports. */
    private val MFG_YINGXIN = 0x05A7

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
     */
    private val MFG_META = 0x01AB          // Meta Platforms, Inc. (ex-Facebook)
    private val MFG_META_TECH = 0x058E     // Meta Platforms Technologies (Reality Labs; also Quest)
    private val MFG_LUXOTTICA = 0x0D53     // Luxottica — Ray-Ban / Oakley Meta frames
    private val MFG_SNAP = 0x03C2          // Snap Inc. — Spectacles
    private val MFG_VUZIX = 0x060C         // Vuzix — enterprise / AR smart glasses

    /** Echo/Alexa Voice Service GATT (FE03 — assigned to Amazon Lab126). */
    private val UUID_AVS = UUID.fromString("0000fe03-0000-1000-8000-00805f9b34fb")

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

    fun matchManufacturer(companyId: Int?): Family? = when (companyId) {
        MFG_AMAZON -> Family.ECHO
        MFG_GOOGLE -> Family.GOOGLE
        MFG_YINGXIN -> Family.HIDDEN_CAM
        MFG_META, MFG_META_TECH, MFG_LUXOTTICA, MFG_SNAP, MFG_VUZIX -> Family.GLASSES
        else -> null
    }

    fun matchAvsService(advertisedUuids: List<UUID>?): Boolean {
        if (advertisedUuids.isNullOrEmpty()) return false
        return advertisedUuids.contains(UUID_AVS)
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
        if (matchAvsService(advertisedUuids)) return true
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
        Family.HIDDEN_CAM -> "Possible hidden mic / cam"
        Family.GLASSES -> "Smart glasses"
    }
}
