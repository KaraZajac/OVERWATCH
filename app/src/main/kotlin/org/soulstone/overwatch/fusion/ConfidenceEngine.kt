package org.soulstone.overwatch.fusion

import kotlin.math.roundToInt
import org.soulstone.overwatch.scan.DeflockClient

/**
 * Confidence scoring — port of flock-detection's algorithm with weights from the OVERWATCH plan.
 *
 * One [BleObservation] (a single ScanResult) → one score. Multi-method bonus and RSSI bonuses
 * apply within a single observation. Nothing combines scores across observations or sources:
 * [DetectionStore] reports the single highest live score, so two sources hitting the same area
 * read as the stronger one alone.
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
    // Radio-module makers' prefixes seen on Flock hardware (Liteon, Silicon Labs,
    // USI): below the display floor alone, since v0.6.0. Those chips are in
    // laptops, TVs and smart plugs; a real Flock radio also sends a name, an SSID
    // or a maker id, and that is what lifts it.
    const val W_BLE_OUI_MODULE = 25
    // Axon / Utility identifiers that survive a random MAC (AxonSignatures).
    const val W_BLE_AXON_TAG = 90         // "BWCDEVICE" body-cam tag
    const val W_BLE_AXON_SIG = 80         // SIG number with a serial, METROPOLIS, BodyWorn
    const val W_BLE_AXON_UUID = 75        // an Axon SIG UUID listed, no payload

    // Single-method base weights (WiFi — wired in Phase 2)
    const val W_WIFI_OUI = 40
    const val W_WIFI_OUI_POLICE = 75  // WatchGuard 4RE cruiser APs, ShotSpotter backhaul
    const val W_WIFI_OUI_AXON = 80
    const val W_WIFI_OUI_MODULE = 25
    const val W_WIFI_SSID_GENERIC = 50
    const val W_WIFI_SSID_FLOCK_FMT = 65
    const val W_WIFI_SSID_FLOCK_HOTSPOT = 65
    const val B_WIFI_SSID_FLOCK_TAIL = 15   // the hex is the BSSID's last 3 bytes

    // HACKER — attack and pentest gear. Capped at ORANGE like COMMERCIAL: RED stays
    // reserved for ALPR/Axon-grade evidence of being watched.
    const val HACKER_SCORE_CAP = 84
    const val W_HACK_FLIPPER = 60           // Flipper serial-profile UUID 0x3080–0x3083
    const val B_HACK_FLIPPER_NAME = 15      // "Flipper <name>"
    const val B_HACK_FLIPPER_ADDR = 10      // 80:E1:26
    const val W_HACK_FLIPPER_HID = 70       // BLE remote / BadUSB mode
    const val W_HACK_NYANBOX = 60
    const val W_HACK_AXONCADABRA = 75       // spoofed Axon Signal trigger
    const val W_HACK_BLE_SPAM = 84          // crash advert, AppleJuice, flood, cycling
    const val W_HACK_TOOL_SSID = 55
    const val W_HACK_PORTAL_SSID = 50
    const val W_HACK_ROGUE_BSSID = 70
    const val W_HACK_BEACON_SPAM = 75
    const val W_HACK_MANY_NAMES = 70        // one BSSID, 5+ network names
    const val W_HACK_MARAUDER_DECOY = 55
    const val W_HACK_SKIMMER = 65           // HC-05-family module (Bluetooth Classic)
    const val B_HACK_STRONG_RSSI = 5

    // TRACKER — tags away from their owner. Capped at ORANGE.
    const val TRACKER_SCORE_CAP = 84
    const val W_TRACKER_SEPARATED = 45      // the tag says it is away from its owner
    const val W_TRACKER_UNKNOWN = 30        // the format doesn't say (Tile, Pebblebee)
    const val B_TRACKER_FOLLOWING = 25      // in range without a break for 10+ min
    const val TRACKER_FOLLOWING_MS = 10L * 60L * 1000L

    // Drones (Remote ID), filed under AIRCRAFT. Capped at ORANGE: a model is not an owner.
    const val DRONE_SCORE_CAP = 84
    const val W_DRONE_ANY = 45
    const val W_DRONE_PUBLIC_SAFETY = 55    // enterprise / public-safety models
    const val W_DRONE_FIRST_RESPONDER = 65  // DJI dock aircraft, Skydio X10 / R10
    const val W_DRONE_POLICE_MAKER = 75     // BRINC

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
    const val W_MIC_SERVICE_UUID = 50   // AVS (Echo) or HeyCyan (glasses)
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
        val isStationary: Boolean = false,
        /** The advertised service-UUID list alone, without service-data keys. */
        val serviceUuidList: List<java.util.UUID>? = null,
        val serviceData: Map<java.util.UUID, ByteArray>? = null,
        /** Every manufacturer payload by company ID (company ID not included). */
        val manufacturerData: Map<Int, ByteArray>? = null,
        val rawBytes: ByteArray? = null
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
        val isAxon: Boolean,
        /** Set where an ignore rule can cover every device of the sort; see [DetectionEvent.kind]. */
        val kind: DetectionKind? = null
    )

    fun scoreBle(obs: BleObservation): Scored {
        var score = 0
        val methods = StringBuilder()
        var methodCount = 0
        var isAxon = false

        // OUI prefix: Axon, then a vendor's own block, then a module maker's.
        val mac = obs.mac
        val ouiHit = when {
            org.soulstone.overwatch.data.targets.BleOuis.isAxon(mac) -> {
                score += W_BLE_OUI_AXON; methods.append("axon_oui "); isAxon = true; true
            }
            org.soulstone.overwatch.data.targets.VendorOuis.isPoliceExclusive(mac) -> {
                score += W_BLE_OUI_POLICE; methods.append("police_oui "); true
            }
            org.soulstone.overwatch.data.targets.VendorOuis.matches(mac) -> {
                score += W_BLE_OUI; methods.append("vendor_oui "); true
            }
            org.soulstone.overwatch.data.targets.BleOuis.isModule(mac) -> {
                score += W_BLE_OUI_MODULE; methods.append("module_oui "); true
            }
            else -> false
        }
        if (ouiHit) methodCount++

        // Axon / Utility identifiers that survive a randomised MAC.
        val axon = org.soulstone.overwatch.data.targets.AxonSignatures.match(
            obs.rawBytes, obs.deviceName, obs.serviceUuidList, obs.serviceData, obs.manufacturerData
        )
        if (axon != null) {
            score += when (axon.kind) {
                org.soulstone.overwatch.data.targets.AxonSignatures.Kind.BWC_TAG -> W_BLE_AXON_TAG
                org.soulstone.overwatch.data.targets.AxonSignatures.Kind.SIG_UUID -> W_BLE_AXON_UUID
                else -> W_BLE_AXON_SIG
            }
            methods.append(axon.method).append(' ')
            methodCount++
            if (axon.kind != org.soulstone.overwatch.data.targets.AxonSignatures.Kind.BODYWORN) isAxon = true
        }

        // Device name patterns
        var nameHit = false
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

        // Manufacturer-data signature: XUNTONG, the Penguin battery pack's ID
        var mfgHit = false
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
        var ravenHit = false
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

        val vendor = org.soulstone.overwatch.data.targets.VendorOuis.label(mac)
        val label = when {
            axon != null -> "${axon.label} ($mac)"
            isAxon -> "Axon device ($mac)"
            ravenHit -> "Raven gunshot detector ($mac)"
            vendor != null && !obs.deviceName.isNullOrBlank() ->
                "$vendor — ${obs.deviceName} ($mac)"
            vendor != null -> "$vendor ($mac)"
            !obs.deviceName.isNullOrBlank() -> "${obs.deviceName} ($mac)"
            mfgHit -> "Flock Penguin battery ($mac)"
            else -> "Surveillance BLE ($mac)"
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
        // The operator asked public trackers to hide this airframe. Added before
        // the cap below, which nothing may lift an unidentified aircraft over.
        if (obs.isLadd) { score += 4f; tags.append("ladd ") }
        if (obs.isKnownLawEnforcement) {
            tags.append("known_le ")
        } else {
            // Behaviour-only: cap it. Circling is suggestive, not proof.
            score = minOf(score, AIRCRAFT_UNKNOWN_CAP.toFloat())
            tags.append("unidentified ")
        }

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
        // under a strong BLE/WiFi/DeFlock hit, so one of those still sets the
        // global tier (the store takes the single highest score).
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
     * range setting.** A camera 200 m away is exactly as close whether the
     * slider reads 300 m or 5 km, so it has to score the same either way.
     *
     * That alone was not enough, and the gap is worth recording. The on-screen
     * tier is `max(score)` over everything *reported*, and the slider decides
     * what gets reported — so with the original curves, standing still and
     * dragging the slider from 300 m to 500 m flipped the app GREEN -> YELLOW
     * with nothing physical changing (measured against a real 195-node cache).
     * The scores were absolute but the tier still leaked the setting.
     *
     * The fix is calibration, not plumbing: each curve now crosses below the
     * YELLOW line (40) at roughly the distance the thing stops being able to
     * act on you. A Flock camera reads plates at ~30-50 m, so it is RED on top
     * of it, ORANGE at 100 m, and GREEN by 500 m — visible on the map, not an
     * alarm. Widening the view then adds dots and list rows but cannot change
     * the tier. Waze keeps a wider alert band on purpose: a police car covers
     * 700 m in under a minute, a bollarded camera never moves.
     *
     * A fixed ALPR's position is surveyed and exact, so it stays alarming
     * closer in and decays slowly. The 50 m and 200 m values are carried over
     * from the old step scoring so calibration at typical distances is
     * unchanged.
     */
    private val DEFLOCK_FALLOFF = arrayOf(
        0f to 95f, 25f to 90f, 50f to 85f, 100f to 72f, 200f to 55f,
        350f to 42f, 600f to 30f, 1500f to 22f, 3000f to 18f
    )

    /**
     * A fixed speed camera is enforcement infrastructure, but it only acts on
     * you if you're speeding past it — relevant, not alarming.
     */
    private val SPEED_CAMERA_FALLOFF = arrayOf(
        0f to 78f, 50f to 70f, 150f to 55f, 300f to 42f, 500f to 32f,
        1500f to 22f, 3000f to 16f
    )

    /**
     * A generic `man_made=surveillance` node is as likely to be a shop's CCTV
     * as a street camera, so it peaks below the YELLOW line at any real
     * distance and never drives the tier on its own.
     */
    private val CAMERA_FALLOFF = arrayOf(
        0f to 55f, 50f to 46f, 150f to 38f, 300f to 30f, 800f to 22f, 3000f to 14f
    )

    /**
     * A Waze police report is a crowd-sourced pin on a moving car: coarser
     * than a surveyed camera, so it peaks lower and decays faster. 300 m holds
     * the old flat baseline of 55.
     */
    private val WAZE_FALLOFF = arrayOf(
        0f to 80f, 100f to 70f, 300f to 55f, 600f to 44f, 900f to 36f,
        2000f to 26f, 4000f to 20f
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
        val svcFamily = org.soulstone.overwatch.data.targets.MicTargets.matchService(obs.advertisedUuids)
        if (svcFamily != null) {
            score += W_MIC_SERVICE_UUID
            methods.append("mic_svc ")
            methodCount++
        }
        // Meta's FEB7/FEB8 and Snap's FE45 are company-wide, so they weigh like a
        // company id, and only count when the company id itself is absent.
        val weakSvcFamily = if (svcFamily == null && mfgFamily == null)
            org.soulstone.overwatch.data.targets.MicTargets.matchWeakService(obs.advertisedUuids) else null
        if (weakSvcFamily != null) {
            score += W_MIC_MFG
            methods.append("mic_svc_maker ")
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
        // A service UUID is the most specific signal after a name, so it
        // outranks the OUI and company id when deciding what to call this.
        val family = nameMatch?.family ?: svcFamily ?: ouiFamily ?: mfgFamily ?: weakSvcFamily
            ?: org.soulstone.overwatch.data.targets.MicTargets.Family.HIDDEN_CAM
        val familyLabel = org.soulstone.overwatch.data.targets.MicTargets.familyLabel(family)
        val nameSuffix = if (!obs.deviceName.isNullOrBlank()) " — ${obs.deviceName}" else ""
        return Scored(
            score, methods.toString().trim(), "$familyLabel$nameSuffix (${obs.mac})",
            isAxon = false, kind = micKind(family)
        )
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
        return Scored(
            score, methods.toString().trim(), "$familyLabel$ssidSuffix (${obs.bssid})",
            isAxon = false, kind = micKind(family)
        )
    }

    /** The family as something to ignore wholesale. HIDDEN_CAM has no kind: it is
     *  the name for "matched, but nothing says what it is", and a rule that hid
     *  every unidentified microphone would hide the ones worth seeing. */
    private fun micKind(family: org.soulstone.overwatch.data.targets.MicTargets.Family): DetectionKind? =
        if (family == org.soulstone.overwatch.data.targets.MicTargets.Family.HIDDEN_CAM) null
        else DetectionKind(family.name, org.soulstone.overwatch.data.targets.MicTargets.familyLabel(family))

    fun scoreWifi(obs: WifiObservation): Scored {
        var score = 0
        val methods = StringBuilder()
        var methodCount = 0

        val bssid = obs.bssid
        val axonOui = org.soulstone.overwatch.data.targets.BleOuis.isAxon(bssid)
        val ouiHit = when {
            axonOui -> { score += W_WIFI_OUI_AXON; methods.append("axon_oui "); true }
            org.soulstone.overwatch.data.targets.VendorOuis.isPoliceExclusive(bssid) -> {
                score += W_WIFI_OUI_POLICE; methods.append("police_oui "); true
            }
            org.soulstone.overwatch.data.targets.VendorOuis.matches(bssid) -> {
                score += W_WIFI_OUI; methods.append("vendor_oui "); true
            }
            org.soulstone.overwatch.data.targets.WifiOuis.isModule(bssid) -> {
                score += W_WIFI_OUI_MODULE; methods.append("module_oui "); true
            }
            else -> false
        }
        if (ouiHit) methodCount++

        var ssidHit = false
        val patterns = org.soulstone.overwatch.data.targets.Patterns
        if (patterns.ssidFlockFormat(obs.ssid)) {
            score += W_WIFI_SSID_FLOCK_FMT
            methods.append("ssid_flock ")
            if (patterns.ssidFlockMatchesBssid(obs.ssid, bssid)) {
                score += B_WIFI_SSID_FLOCK_TAIL
                methods.append("bssid_tail ")
            }
            ssidHit = true
        } else if (patterns.ssidFlockHotspot(obs.ssid)) {
            score += W_WIFI_SSID_FLOCK_HOTSPOT
            methods.append("ssid_flock_hotspot ")
            ssidHit = true
        } else if (patterns.ssidGenericMatch(obs.ssid)) {
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

        val vendor = if (axonOui) "Axon device"
            else org.soulstone.overwatch.data.targets.VendorOuis.label(bssid)
        val label = when {
            vendor != null && !obs.ssid.isNullOrBlank() -> "$vendor — ${obs.ssid} ($bssid)"
            vendor != null -> "$vendor ($bssid)"
            !obs.ssid.isNullOrBlank() -> "${obs.ssid} ($bssid)"
            else -> "Surveillance WiFi ($bssid)"
        }

        return Scored(score, methods.toString().trim(), label, isAxon = axonOui)
    }

    // ---- HACKER ---------------------------------------------------------------

    /** Base weight of a single-advert hacker-gear match. */
    fun hackerWeight(kind: org.soulstone.overwatch.data.targets.HackerTargets.Kind): Int = when (kind) {
        org.soulstone.overwatch.data.targets.HackerTargets.Kind.FLIPPER -> W_HACK_FLIPPER
        org.soulstone.overwatch.data.targets.HackerTargets.Kind.FLIPPER_HID -> W_HACK_FLIPPER_HID
        org.soulstone.overwatch.data.targets.HackerTargets.Kind.NYANBOX -> W_HACK_NYANBOX
        org.soulstone.overwatch.data.targets.HackerTargets.Kind.AXONCADABRA -> W_HACK_AXONCADABRA
        org.soulstone.overwatch.data.targets.HackerTargets.Kind.TOOL_AP -> W_HACK_TOOL_SSID
        org.soulstone.overwatch.data.targets.HackerTargets.Kind.PORTAL_AP -> W_HACK_PORTAL_SSID
        org.soulstone.overwatch.data.targets.HackerTargets.Kind.ROGUE_AP -> W_HACK_ROGUE_BSSID
        org.soulstone.overwatch.data.targets.HackerTargets.Kind.MARAUDER_DECOY -> W_HACK_MARAUDER_DECOY
        org.soulstone.overwatch.data.targets.HackerTargets.Kind.SKIMMER -> W_HACK_SKIMMER
    }

    /** A hacker-gear score: [base] plus [extra] corroboration plus +5 when very close,
     *  capped at ORANGE. */
    fun scoreHacker(base: Int, extra: Int, rssi: Int?): Int {
        val close = if (rssi != null && rssi > -50) B_HACK_STRONG_RSSI else 0
        return (base + extra + close).coerceAtMost(HACKER_SCORE_CAP)
    }

    // ---- TRACKER --------------------------------------------------------------

    /** A tracker's score from whether it says it is separated and how long it has
     *  stayed in range. A tag on its own is somebody's keys; one that stays is the case
     *  that matters, so only staying lifts an unknown-state tag over the floor. */
    fun scoreTracker(separated: Boolean?, dwellMs: Long): Scored {
        var score = if (separated == true) W_TRACKER_SEPARATED else W_TRACKER_UNKNOWN
        val methods = StringBuilder(if (separated == true) "separated" else "tracker")
        if (dwellMs >= TRACKER_FOLLOWING_MS) {
            score += B_TRACKER_FOLLOWING
            methods.append(" following")
        }
        return Scored(score.coerceAtMost(TRACKER_SCORE_CAP), methods.toString(), "", isAxon = false)
    }

    // ---- Drones (Remote ID) ---------------------------------------------------

    fun scoreDrone(tier: org.soulstone.overwatch.data.targets.RemoteId.Tier): Int = when (tier) {
        org.soulstone.overwatch.data.targets.RemoteId.Tier.POLICE_MAKER -> W_DRONE_POLICE_MAKER
        org.soulstone.overwatch.data.targets.RemoteId.Tier.FIRST_RESPONDER -> W_DRONE_FIRST_RESPONDER
        org.soulstone.overwatch.data.targets.RemoteId.Tier.PUBLIC_SAFETY -> W_DRONE_PUBLIC_SAFETY
        org.soulstone.overwatch.data.targets.RemoteId.Tier.ANY -> W_DRONE_ANY
    }.coerceAtMost(DRONE_SCORE_CAP)
}
