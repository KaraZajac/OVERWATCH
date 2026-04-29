package org.soulstone.overwatch.fusion

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
    const val W_BLE_NAME = 45
    const val W_BLE_NAME_PENGUIN_NUMERIC = 15
    const val W_BLE_MFG_XUNTONG = 60
    const val W_BLE_TN_SERIAL_BONUS = 20  // added on top of mfg
    const val W_BLE_RAVEN_UUID = 70
    const val W_BLE_RAVEN_UUID_MULTI = 90 // 3+ UUIDs

    // Single-method base weights (WiFi — wired in Phase 2)
    const val W_WIFI_OUI = 40
    const val W_WIFI_SSID_GENERIC = 50
    const val W_WIFI_SSID_FLOCK_FMT = 65

    // Map (Phase 3)
    const val W_DEFLOCK_NEAR = 60   // <= 200m
    const val W_DEFLOCK_VERY_NEAR = 85 // <= 50m

    // Citizen (replaces Waze; Waze's reCAPTCHA gating made it unreachable)
    const val W_CITIZEN_INCIDENT = 55
    const val B_CITIZEN_LEVEL_BUMP = 5     // level >= 2
    const val B_CITIZEN_POLICE_TITLE = 5   // title contains a police-action keyword

    // Bonuses
    const val B_MULTI_METHOD = 20
    const val B_STRONG_RSSI = 10   // > -50 dBm
    const val B_STATIONARY = 15    // RSSI rise-peak-fall

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

    /** A DeFlock map ALPR observed within proximity threshold. */
    data class DeflockObservation(
        val osmId: Long,
        val distanceMeters: Float,
        val operator: String?,
        val manufacturer: String?
    )

    /** A Citizen incident observed within proximity + freshness, after the
     *  fire/medical filter is applied. */
    data class CitizenObservation(
        val incidentId: String,
        val distanceMeters: Float,
        val ageMs: Long,
        val level: Int,             // 0-5 severity (Citizen's own scale)
        val title: String,
        val isPoliceTitled: Boolean,
        val precinct: String?
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

        val label = when {
            isAxon -> "Axon body cam (${obs.mac})"
            ravenHit -> "Raven gunshot detector (${obs.mac})"
            !obs.deviceName.isNullOrBlank() -> "${obs.deviceName} (${obs.mac})"
            else -> "Surveillance BLE (${obs.mac})"
        }

        return Scored(score, methods.toString().trim(), label, isAxon)
    }

    fun scoreCitizen(obs: CitizenObservation): Scored {
        var score = W_CITIZEN_INCIDENT
        val tags = StringBuilder("citizen ")
        if (obs.level >= 2) {
            score += B_CITIZEN_LEVEL_BUMP
            tags.append("L${obs.level} ")
        }
        if (obs.isPoliceTitled) {
            score += B_CITIZEN_POLICE_TITLE
            tags.append("police_title ")
        }
        if (!obs.precinct.isNullOrBlank()) tags.append("precinct=${obs.precinct} ")
        score = score.coerceAtMost(100)
        val ageMin = (obs.ageMs / 60_000L).toInt()
        val label = "${obs.title} @ ${obs.distanceMeters.toInt()}m, ${ageMin}min ago"
        return Scored(score, tags.toString().trim(), label, isAxon = false)
    }

    fun scoreDeflock(obs: DeflockObservation): Scored {
        val score = if (obs.distanceMeters <= 50f) W_DEFLOCK_VERY_NEAR else W_DEFLOCK_NEAR
        val rangeTag = if (obs.distanceMeters <= 50f) "deflock<=50m" else "deflock<=200m"
        val descriptor = listOfNotNull(obs.manufacturer, obs.operator)
            .joinToString(" / ").ifBlank { "ALPR" }
        val label = "%s @ %dm (osm:%d)".format(descriptor, obs.distanceMeters.toInt(), obs.osmId)
        return Scored(score, rangeTag, label, isAxon = false)
    }

    fun scoreWifi(obs: WifiObservation): Scored {
        var score = 0
        val methods = StringBuilder()
        var methodCount = 0

        val ouiHit = org.soulstone.overwatch.data.targets.WifiOuis.matches(obs.bssid)
        if (ouiHit) {
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

        val label = if (!obs.ssid.isNullOrBlank()) "${obs.ssid} (${obs.bssid})"
            else "Surveillance WiFi (${obs.bssid})"

        return Scored(score, methods.toString().trim(), label, isAxon = false)
    }
}
