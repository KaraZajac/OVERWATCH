package org.soulstone.overwatch.fusion

import kotlin.math.roundToInt
import org.soulstone.overwatch.scan.DeflockClient

/**
 * Confidence scoring — port of flock-detection's algorithm with weights from the OVERWATCH plan.
 *
 * One [BleObservation] (a single ScanResult) → one score. Multi-method bonus and RSSI bonuses
 * apply within a single observation. Cross-source corroboration is handled at the [DetectionStore]
 * level (multiple sources hitting the same area push the global max upward).
 */
object ConfidenceEngine {

    // Single-method base weights (BLE)
    const val W_BLE_OUI = 40
    const val W_BLE_OUI_AXON = 80
    // Police-exclusive vendor OUIs (WatchGuard, ShotSpotter) — same rationale as
    // Axon: these prefixes appear on nothing consumer, so a hit is ORANGE-grade
    // on its own.
    const val W_BLE_OUI_POLICE = 75
    const val W_BLE_NAME = 45
    const val W_BLE_NAME_PENGUIN_NUMERIC = 15
    const val W_BLE_MFG_XUNTONG = 60
    const val W_BLE_TN_SERIAL_BONUS = 20  // added on top of mfg
    const val W_BLE_RAVEN_UUID = 70
    const val W_BLE_RAVEN_UUID_MULTI = 90 // 3+ UUIDs

    // Single-method base weights (WiFi — wired in Phase 2)
    const val W_WIFI_OUI = 40
    const val W_WIFI_OUI_POLICE = 75  // WatchGuard 4RE cruiser APs, ShotSpotter backhaul
    const val W_WIFI_SSID_GENERIC = 50
    const val W_WIFI_SSID_FLOCK_FMT = 65

    // DeFlock + Waze are scored by a continuous distance falloff — see the
    // FALLOFF tables below. A flat "inside the radius" score made the tier
    // meaningless once the radius became user-set up to 5 km: in a city there
    // is always an ALPR within a mile, so the app sat on YELLOW permanently.

    /** Waze freshness window — shared with WazeScanner so the two can't drift. */
    const val WAZE_MAX_AGE_MS = 45L * 60L * 1000L
    /** Points shed across the full freshness window; police move, old reports rot. */
    const val B_WAZE_AGE_DECAY = 12f

    // Bonuses
    const val B_MULTI_METHOD = 20
    const val B_STRONG_RSSI = 10   // > -50 dBm
    const val B_STATIONARY = 15    // RSSI rise-peak-fall

    // MIC channel — smart-home/voice-assistant detection. Capped so a Ring or
    // Echo cluster can't push the global tier above ORANGE; RED stays reserved
    // for ALPR/Axon-grade evidence.
    const val MIC_SCORE_CAP = 84
    const val W_MIC_OUI = 30
    const val W_MIC_NAME = 45
    const val W_MIC_MFG = 30
    const val W_MIC_AVS_UUID = 50
    const val W_MIC_SSID = 45
    const val B_MIC_MULTI = 10
    const val B_MIC_STATIONARY = 8
    const val B_MIC_STRONG_RSSI = 5

    /** What we observed about one BLE device on a single scan callback. */
    data class BleObservation(
        val mac: String,
        val rssi: Int,
        val deviceName: String?,
        val advertisedUuids: List<java.util.UUID>?,
        val manufacturerCompanyId: Int?,
        val manufacturerPayload: ByteArray?,
        val isStationary: Boolean = false
    )

    /** What we observed about one WiFi AP on a single scan result. */
    data class WifiObservation(
        val bssid: String,
        val ssid: String?,
        val rssi: Int,
        val isStationary: Boolean = false
    )

    /** A mapped surveillance node observed within the detection radius. */
    data class DeflockObservation(
        val osmId: Long,
        val distanceMeters: Float,
        val kind: DeflockClient.Kind,
        val operator: String?,
        val manufacturer: String?
    )

    /** An aircraft seen overhead by [org.soulstone.overwatch.scan.AircraftScanner]. */
    data class AircraftObservation(
        val icaoHex: String,
        val distanceMeters: Float,
        val altitudeFt: Int?,
        val isKnownLawEnforcement: Boolean,
        val isLoitering: Boolean,
        val isLadd: Boolean,
        val owner: String?,
        val registration: String?,
        val aircraftType: String?,
        val callsign: String?
    )

    /** A Waze POLICE alert observed within proximity + freshness thresholds. */
    data class WazeObservation(
        val uuid: String,
        val distanceMeters: Float,
        val ageMs: Long,
        val confidence: Int,   // raw 0-5
        val reliability: Int,  // raw 0-10
        val subtype: String?
    )

    data class Scored(
        val score: Int,
        val methods: String,
        val label: String,
        /** True if the BLE OUI specifically matched Axon (drives the "Axon body cam" labeling). */
        val isAxon: Boolean
    )

    fun scoreBle(obs: BleObservation): Scored {
        var score = 0
        val methods = StringBuilder()
        var methodCount = 0
        var ouiHit = false
        var nameHit = false
        var mfgHit = false
        var ravenHit = false
        var isAxon = false

        // OUI prefix
        if (org.soulstone.overwatch.data.targets.BleOuis.isAxon(obs.mac)) {
            score += W_BLE_OUI_AXON
            methods.append("axon_oui ")
            ouiHit = true; isAxon = true
        } else if (org.soulstone.overwatch.data.targets.VendorOuis.isPoliceExclusive(obs.mac)) {
            score += W_BLE_OUI_POLICE
            methods.append("police_oui ")
            ouiHit = true
        } else if (org.soulstone.overwatch.data.targets.BleOuis.matches(obs.mac)) {
            score += W_BLE_OUI
            methods.append("oui ")
            ouiHit = true
        }
        if (ouiHit) methodCount++

        // Device name patterns
        if (org.soulstone.overwatch.data.targets.Patterns.bleNameMatch(obs.deviceName)) {
            score += W_BLE_NAME
            methods.append("name ")
            nameHit = true
        } else if (org.soulstone.overwatch.data.targets.Patterns.isPenguinNumeric(obs.deviceName)) {
            score += W_BLE_NAME_PENGUIN_NUMERIC
            methods.append("penguin_num ")
            nameHit = true
        }
        if (nameHit) methodCount++

        // Manufacturer-data signature
        if (obs.manufacturerCompanyId == org.soulstone.overwatch.data.targets.Manufacturers.XUNTONG_COMPANY_ID) {
            score += W_BLE_MFG_XUNTONG
            methods.append("mfg_0x09C8 ")
            mfgHit = true
            if (org.soulstone.overwatch.data.targets.Manufacturers.hasTnSerial(obs.manufacturerPayload)) {
                score += W_BLE_TN_SERIAL_BONUS
                methods.append("tn_serial ")
            }
        }
        if (mfgHit) methodCount++

        // Raven service UUIDs
        val ravenCount = org.soulstone.overwatch.data.targets.RavenUuids.countMatches(obs.advertisedUuids)
        if (ravenCount > 0) {
            if (ravenCount >= 3) {
                score += W_BLE_RAVEN_UUID_MULTI
                methods.append("raven_multi ")
            } else {
                score += W_BLE_RAVEN_UUID
                methods.append("raven_uuid ")
            }
            ravenHit = true
            methodCount++
        }

        // Multi-method corroboration bonus
        if (methodCount >= 2) {
            score += B_MULTI_METHOD
            methods.append("multi ")
        }

        // Strong RSSI (very close)
        if (obs.rssi > -50) {
            score += B_STRONG_RSSI
            methods.append("strong_rssi ")
        }

        // Stationary RSSI trend
        if (obs.isStationary) {
            score += B_STATIONARY
            methods.append("stationary ")
        }

        score = score.coerceAtMost(100)

        val vendor = org.soulstone.overwatch.data.targets.VendorOuis.label(obs.mac)
        val label = when {
            isAxon -> "Axon body cam (${obs.mac})"
            ravenHit -> "Raven gunshot detector (${obs.mac})"
            vendor != null && !obs.deviceName.isNullOrBlank() ->
                "$vendor — ${obs.deviceName} (${obs.mac})"
            vendor != null -> "$vendor (${obs.mac})"
            !obs.deviceName.isNullOrBlank() -> "${obs.deviceName} (${obs.mac})"
            else -> "Surveillance BLE (${obs.mac})"
        }

        return Scored(score, methods.toString().trim(), label, isAxon)
    }

    fun scoreAircraft(obs: AircraftObservation): Scored {
        var score = falloff(obs.distanceMeters, AIRCRAFT_FALLOFF)
        val tags = StringBuilder("aircraft ")

        // Altitude is the strongest discriminator after identity: a known
        // police airframe at 30,000 ft is an airliner's neighbour, not an
        // observer. Penalise height rather than filtering, so a high orbit
        // still shows up quietly.
        val alt = obs.altitudeFt
        when {
            alt == null -> Unit
            alt <= 3_000 -> { score += 6f; tags.append("very_low ") }
            alt <= 8_000 -> tags.append("low ")
            alt <= 15_000 -> { score -= 12f; tags.append("mid_alt ") }
            else -> { score -= 30f; tags.append("high_alt ") }
        }

        if (obs.isLoitering) { score += 10f; tags.append("loitering ") }
        if (obs.isKnownLawEnforcement) {
            tags.append("known_le ")
        } else {
            // Behaviour-only: cap it. Circling is suggestive, not proof.
            score = minOf(score, AIRCRAFT_UNKNOWN_CAP.toFloat())
            tags.append("unidentified ")
        }
        // The operator asked public trackers to hide this airframe.
        if (obs.isLadd) { score += 4f; tags.append("ladd ") }

        val final = score.roundToInt().coerceIn(0, 100)
        val who = obs.owner
            ?: obs.registration
            ?: obs.callsign
            ?: "Unidentified aircraft"
        val what = obs.aircraftType?.let { " ($it)" } ?: ""
        val altText = obs.altitudeFt?.let { ", ${it} ft" } ?: ""
        val verb = if (obs.isLoitering) "circling" else "overhead"
        val label = "$who$what $verb @ ${obs.distanceMeters.toInt()}m$altText"
        tags.append("d=${obs.distanceMeters.toInt()}m hex=${obs.icaoHex}")
        return Scored(final, tags.toString().trim(), label, isAxon = false)
    }

    fun scoreWaze(obs: WazeObservation): Scored {
        // Distance first, then the two crowd-trust nudges, then age. Kept well
        // under the multi-method bonus so a corroborating BLE/WiFi/DeFlock hit
        // still dominates the global tier.
        var score = falloff(obs.distanceMeters, WAZE_FALLOFF)
        if (obs.reliability >= 7) score += 5f
        if (obs.confidence >= 4) score += 5f
        // Police move; a report at the far end of the freshness window is much
        // weaker evidence than one a minute old.
        val ageFraction = (obs.ageMs.toFloat() / WAZE_MAX_AGE_MS).coerceIn(0f, 1f)
        score -= B_WAZE_AGE_DECAY * ageFraction
        val final = score.roundToInt().coerceIn(0, 100)
        val ageMin = (obs.ageMs / 60_000L).toInt()
        val methods = "waze_police d=${obs.distanceMeters.toInt()}m age=${ageMin}min " +
            "rel=${obs.reliability} conf=${obs.confidence}"
        val sub = obs.subtype?.let { " ($it)" } ?: ""
        val label = "Police report$sub @ ${obs.distanceMeters.toInt()}m, ${ageMin}min ago"
        return Scored(final, methods, label, isAxon = false)
    }

    /**
     * Distance falloff anchors, as (metres, score) pairs interpolated linearly
     * by [falloff].
     *
     * These are **absolute distances, deliberately not a fraction of the user's
     * detection radius.** A camera 200 m away is exactly as close whether the
     * radius slider reads 300 m or 5 km, so it has to score the same either
     * way; keying off the radius would make the threat level move when the
     * user touched a setting, which is the opposite of what the number means.
     * The radius decides what gets *reported*, never how alarming it is.
     *
     * A fixed ALPR's position is surveyed and exact, so it stays alarming
     * closer in and decays slowly. The 50 m and 200 m values are carried over
     * from the old step scoring so calibration at typical distances is
     * unchanged.
     */
    private val DEFLOCK_FALLOFF = arrayOf(
        0f to 92f, 50f to 85f, 200f to 60f, 600f to 45f, 1500f to 30f, 3000f to 22f
    )

    /**
     * A fixed speed camera is enforcement infrastructure, but it only acts on
     * you if you're speeding past it — relevant, not alarming.
     */
    private val SPEED_CAMERA_FALLOFF = arrayOf(
        0f to 75f, 50f to 70f, 200f to 52f, 600f to 40f, 1500f to 28f, 3000f to 20f
    )

    /**
     * A generic `man_made=surveillance` node is as likely to be a shop's CCTV
     * as a street camera, so it peaks below the YELLOW line at any real
     * distance and never drives the tier on its own.
     */
    private val CAMERA_FALLOFF = arrayOf(
        0f to 55f, 50f to 48f, 200f to 38f, 600f to 30f, 1500f to 22f, 3000f to 18f
    )

    /**
     * A Waze police report is a crowd-sourced pin on a moving car: coarser
     * than a surveyed camera, so it peaks lower and decays faster. 300 m holds
     * the old flat baseline of 55.
     */
    private val WAZE_FALLOFF = arrayOf(
        0f to 80f, 100f to 70f, 300f to 55f, 800f to 45f, 2000f to 32f, 4000f to 25f
    )

    /**
     * Aircraft falloff, over ground distance. Far wider than the ground
     * sources because an aircraft orbiting 5 km away is still watching you —
     * unlike a camera 5 km away, which cannot see you at all.
     */
    private val AIRCRAFT_FALLOFF = arrayOf(
        0f to 88f, 1000f to 80f, 3000f to 68f, 6000f to 55f, 10000f to 42f, 15000f to 30f
    )

    /** An unregistered aircraft is judged on behaviour alone, so it is capped
     *  below the "certain" band — circling could still be news or survey work. */
    const val AIRCRAFT_UNKNOWN_CAP = 69

    /** Linear interpolation across [anchors]; clamps outside the first/last. */
    private fun falloff(distanceMeters: Float, anchors: Array<Pair<Float, Float>>): Float {
        val d = distanceMeters.coerceAtLeast(0f)
        if (d <= anchors.first().first) return anchors.first().second
        for (i in 0 until anchors.size - 1) {
            val (d0, s0) = anchors[i]
            val (d1, s1) = anchors[i + 1]
            if (d <= d1) return s0 + (d - d0) / (d1 - d0) * (s1 - s0)
        }
        return anchors.last().second
    }

    fun scoreDeflock(obs: DeflockObservation): Scored {
        val d = obs.distanceMeters
        val (curve, fallbackName, tag) = when (obs.kind) {
            DeflockClient.Kind.ALPR -> Triple(DEFLOCK_FALLOFF, "ALPR", "alpr")
            DeflockClient.Kind.SPEED_CAMERA ->
                Triple(SPEED_CAMERA_FALLOFF, "Speed camera", "speed_cam")
            DeflockClient.Kind.CAMERA ->
                Triple(CAMERA_FALLOFF, "Surveillance camera", "camera")
        }
        val score = falloff(d, curve).roundToInt().coerceIn(0, 100)
        val descriptor = listOfNotNull(obs.manufacturer, obs.operator)
            .joinToString(" / ").ifBlank { fallbackName }
        // The OSM id rides in the methods line rather than the label: it is
        // what you need to look a camera up, and nothing you want shouting
        // from the one-line status on the main screen.
        val label = "%s @ %dm".format(descriptor, d.toInt())
        return Scored(score, "$tag d=${d.toInt()}m osm:${obs.osmId}", label, isAxon = false)
    }

    /** A BLE mic-bearing-device observation, score-capped at ORANGE. */
    data class MicBleObservation(
        val mac: String,
        val rssi: Int,
        val deviceName: String?,
        val advertisedUuids: List<java.util.UUID>?,
        val manufacturerCompanyId: Int?,
        val isStationary: Boolean
    )

    /** A WiFi mic-bearing-device observation, score-capped at ORANGE. */
    data class MicWifiObservation(
        val bssid: String,
        val ssid: String?,
        val rssi: Int,
        val isStationary: Boolean
    )

    fun scoreMicBle(obs: MicBleObservation): Scored {
        var score = 0
        var methodCount = 0
        val methods = StringBuilder()
        val ouiFamily = org.soulstone.overwatch.data.targets.MicTargets.matchOui(obs.mac)
        if (ouiFamily != null) {
            score += W_MIC_OUI
            methods.append("mic_oui ")
            methodCount++
        }
        val nameMatch = org.soulstone.overwatch.data.targets.MicTargets.matchBleName(obs.deviceName)
        if (nameMatch != null) {
            score += W_MIC_NAME
            methods.append("mic_name ")
            methodCount++
        }
        val mfgFamily = org.soulstone.overwatch.data.targets.MicTargets.matchManufacturer(obs.manufacturerCompanyId)
        if (mfgFamily != null) {
            score += W_MIC_MFG
            methods.append("mic_mfg ")
            methodCount++
        }
        if (org.soulstone.overwatch.data.targets.MicTargets.matchAvsService(obs.advertisedUuids)) {
            score += W_MIC_AVS_UUID
            methods.append("mic_avs ")
            methodCount++
        }
        if (methodCount >= 2) {
            score += B_MIC_MULTI
            methods.append("multi ")
        }
        if (obs.rssi > -50) {
            score += B_MIC_STRONG_RSSI
            methods.append("strong_rssi ")
        }
        if (obs.isStationary) {
            score += B_MIC_STATIONARY
            methods.append("stationary ")
        }
        score = score.coerceAtMost(MIC_SCORE_CAP)
        val family = nameMatch?.family ?: ouiFamily ?: mfgFamily
            ?: org.soulstone.overwatch.data.targets.MicTargets.Family.HIDDEN_CAM
        val familyLabel = org.soulstone.overwatch.data.targets.MicTargets.familyLabel(family)
        val nameSuffix = if (!obs.deviceName.isNullOrBlank()) " — ${obs.deviceName}" else ""
        return Scored(score, methods.toString().trim(), "$familyLabel$nameSuffix (${obs.mac})", isAxon = false)
    }

    fun scoreMicWifi(obs: MicWifiObservation): Scored {
        var score = 0
        var methodCount = 0
        val methods = StringBuilder()
        val ouiFamily = org.soulstone.overwatch.data.targets.MicTargets.matchOui(obs.bssid)
        if (ouiFamily != null) {
            score += W_MIC_OUI
            methods.append("mic_oui ")
            methodCount++
        }
        val ssidMatch = org.soulstone.overwatch.data.targets.MicTargets.matchSsid(obs.ssid)
        if (ssidMatch != null) {
            score += W_MIC_SSID
            methods.append("mic_ssid ")
            methodCount++
        }
        if (methodCount >= 2) {
            score += B_MIC_MULTI
            methods.append("multi ")
        }
        if (obs.rssi > -50) {
            score += B_MIC_STRONG_RSSI
            methods.append("strong_rssi ")
        }
        if (obs.isStationary) {
            score += B_MIC_STATIONARY
            methods.append("stationary ")
        }
        score = score.coerceAtMost(MIC_SCORE_CAP)
        val family = ssidMatch?.family ?: ouiFamily
            ?: org.soulstone.overwatch.data.targets.MicTargets.Family.HIDDEN_CAM
        val familyLabel = org.soulstone.overwatch.data.targets.MicTargets.familyLabel(family)
        val ssidSuffix = if (!obs.ssid.isNullOrBlank()) " — ${obs.ssid}" else ""
        return Scored(score, methods.toString().trim(), "$familyLabel$ssidSuffix (${obs.bssid})", isAxon = false)
    }

    fun scoreWifi(obs: WifiObservation): Scored {
        var score = 0
        val methods = StringBuilder()
        var methodCount = 0

        val policeOui = org.soulstone.overwatch.data.targets.VendorOuis.isPoliceExclusive(obs.bssid)
        val ouiHit = policeOui || org.soulstone.overwatch.data.targets.WifiOuis.matches(obs.bssid)
        if (policeOui) {
            score += W_WIFI_OUI_POLICE
            methods.append("police_oui ")
            methodCount++
        } else if (ouiHit) {
            score += W_WIFI_OUI
            methods.append("oui ")
            methodCount++
        }

        var ssidHit = false
        if (org.soulstone.overwatch.data.targets.Patterns.ssidFlockFormat(obs.ssid)) {
            score += W_WIFI_SSID_FLOCK_FMT
            methods.append("ssid_flock ")
            ssidHit = true
        } else if (org.soulstone.overwatch.data.targets.Patterns.ssidGenericMatch(obs.ssid)) {
            score += W_WIFI_SSID_GENERIC
            methods.append("ssid_generic ")
            ssidHit = true
        }
        if (ssidHit) methodCount++

        if (methodCount >= 2) {
            score += B_MULTI_METHOD
            methods.append("multi ")
        }
        if (obs.rssi > -50) {
            score += B_STRONG_RSSI
            methods.append("strong_rssi ")
        }
        if (obs.isStationary) {
            score += B_STATIONARY
            methods.append("stationary ")
        }

        score = score.coerceAtMost(100)

        val vendor = org.soulstone.overwatch.data.targets.VendorOuis.label(obs.bssid)
        val label = when {
            vendor != null && !obs.ssid.isNullOrBlank() -> "$vendor — ${obs.ssid} (${obs.bssid})"
            vendor != null -> "$vendor (${obs.bssid})"
            !obs.ssid.isNullOrBlank() -> "${obs.ssid} (${obs.bssid})"
            else -> "Surveillance WiFi (${obs.bssid})"
        }

        return Scored(score, methods.toString().trim(), label, isAxon = false)
    }
}
