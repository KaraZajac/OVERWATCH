package org.soulstone.overwatch.data.targets

import java.util.UUID

/**
 * Drone Remote ID (ASTM F3411 / FAA Part 89) as a phone can receive it.
 *
 * Layout from opendroneid-core-c and receiver-android (both Apache-2.0): the
 * reference receiver for Android, which needs no root.
 *
 *  - **Bluetooth.** Service data for 16-bit UUID 0xFFFA ("ASTM Remote ID"): app code
 *    0x0D, a message counter, then one 25-byte message (legacy advertising, one
 *    message type per advert) or a message pack (long-range extended advertising).
 *  - **Wi-Fi beacon.** A vendor-specific element (221) with OUI FA:0B:BC and type
 *    0x0D, then a counter and a message pack. Android exposes it through
 *    `ScanResult.getInformationElements()` from API 30.
 *
 * US drones comply under ASTM F3586, which allows only these two (Wi-Fi NaN is
 * not an accepted US means). Police drones must broadcast too: state and local
 * government aircraft register, so Part 89 applies, unless the FAA authorises a
 * deviation (86 FR 4404; 14 CFR 89.105), and drone-as-first-responder flights
 * beyond line of sight need standard (built-in) Remote ID.
 *
 * Only what OVERWATCH shows is decoded: the Basic ID (serial), the Location
 * (the drone's own position and altitude) and the status. The System message also
 * carries the operator's or take-off position; OVERWATCH ignores it.
 */
object RemoteId {

    val BLE_SERVICE_UUID: UUID = UUID.fromString("0000fffa-0000-1000-8000-00805f9b34fb")
    const val APP_CODE = 0x0D
    private const val MESSAGE_SIZE = 25
    private const val TYPE_BASIC_ID = 0x0
    private const val TYPE_LOCATION = 0x1
    private const val TYPE_PACK = 0xF
    private const val ID_TYPE_SERIAL = 1

    /** What one transmitter has said so far. */
    class Drone {
        var serial: String? = null
        var otherId: String? = null
        var latitude: Double? = null
        var longitude: Double? = null
        var altitudeM: Float? = null
        var airborne: Boolean = false
        /** Best identifier for the store key and the label. */
        val id: String? get() = serial ?: otherId
    }

    /** The messages in a Bluetooth 0xFFFA service-data payload, or empty. */
    fun messagesFromBle(serviceData: ByteArray?): List<ByteArray> {
        if (serviceData == null || serviceData.size < 2 + MESSAGE_SIZE) return emptyList()
        if ((serviceData[0].toInt() and 0xFF) != APP_CODE) return emptyList()
        return unpack(serviceData, 2)
    }

    /** The messages in one Wi-Fi vendor element's bytes (OUI onwards), or empty. */
    fun messagesFromWifiIe(ie: ByteArray?): List<ByteArray> {
        if (ie == null || ie.size < 5 + 3) return emptyList()
        if ((ie[0].toInt() and 0xFF) != 0xFA || (ie[1].toInt() and 0xFF) != 0x0B ||
            (ie[2].toInt() and 0xFF) != 0xBC || (ie[3].toInt() and 0xFF) != APP_CODE
        ) return emptyList()
        return unpack(ie, 5)
    }

    /** One message, or every message in a pack, starting at [offset]. */
    private fun unpack(buf: ByteArray, offset: Int): List<ByteArray> {
        if (buf.size < offset + 1) return emptyList()
        val type = (buf[offset].toInt() and 0xF0) shr 4
        if (type != TYPE_PACK) {
            if (buf.size < offset + MESSAGE_SIZE) return emptyList()
            return listOf(buf.copyOfRange(offset, offset + MESSAGE_SIZE))
        }
        if (buf.size < offset + 3) return emptyList()
        val size = buf[offset + 1].toInt() and 0xFF
        val count = buf[offset + 2].toInt() and 0xFF
        if (size != MESSAGE_SIZE || count == 0 || count > 9) return emptyList()
        val out = ArrayList<ByteArray>(count)
        for (i in 0 until count) {
            val start = offset + 3 + i * MESSAGE_SIZE
            if (buf.size < start + MESSAGE_SIZE) break
            out.add(buf.copyOfRange(start, start + MESSAGE_SIZE))
        }
        return out
    }

    /** Fold one 25-byte message into [drone]. Unknown types are ignored. */
    fun apply(drone: Drone, msg: ByteArray) {
        if (msg.size < MESSAGE_SIZE) return
        when ((msg[0].toInt() and 0xF0) shr 4) {
            TYPE_BASIC_ID -> {
                val idType = (msg[1].toInt() and 0xF0) shr 4
                val id = ascii(msg, 2, 20) ?: return
                if (idType == ID_TYPE_SERIAL) drone.serial = id else if (drone.otherId == null) drone.otherId = id
            }
            TYPE_LOCATION -> {
                val status = (msg[1].toInt() and 0xF0) shr 4
                drone.airborne = status == 2 || status == 3
                val lat = le32(msg, 5) * 1e-7
                val lon = le32(msg, 9) * 1e-7
                if (lat != 0.0 && lon != 0.0 && kotlin.math.abs(lat) <= 90.0 && kotlin.math.abs(lon) <= 180.0) {
                    drone.latitude = lat
                    drone.longitude = lon
                }
                val geo = le16(msg, 15)
                val baro = le16(msg, 13)
                val raw = if (geo != 0) geo else baro
                drone.altitudeM = if (raw != 0) raw / 2f - 1000f else null
            }
        }
    }

    enum class Tier { POLICE_MAKER, FIRST_RESPONDER, PUBLIC_SAFETY, ANY }

    data class Classification(val tier: Tier, val name: String)

    /**
     * Maker and model from an ANSI/CTA-2063-A serial: a four-character manufacturer
     * code, a length character, then the maker's own serial. Prefixes were read off
     * the FAA's public Remote ID Declarations of Compliance (uasdoc.faa.gov) in
     * November 2026. A model isn't an owner — utilities and security firms fly dock
     * drones too — so even BRINC, which sells almost only to police, stops short of
     * certainty.
     */
    fun classify(serial: String?): Classification {
        val s = serial?.uppercase() ?: return Classification(Tier.ANY, "Drone")
        for ((prefix, entry) in MODELS) if (s.startsWith(prefix)) return entry
        for ((prefix, entry) in MAKERS) if (s.startsWith(prefix)) return entry
        return Classification(Tier.ANY, "Drone")
    }

    private val MODELS: List<Pair<String, Classification>> = listOf(
        "1914CR1D" to Classification(Tier.POLICE_MAKER, "BRINC Responder"),
        "1914CL2D" to Classification(Tier.POLICE_MAKER, "BRINC LEMUR 2"),
        "1668BR40" to Classification(Tier.FIRST_RESPONDER, "Skydio X10"),
        "1668BR70" to Classification(Tier.FIRST_RESPONDER, "Skydio R10"),
        "1668BE10" to Classification(Tier.PUBLIC_SAFETY, "Skydio X2E"),
        "1581F5BL" to Classification(Tier.FIRST_RESPONDER, "DJI M30 Dock"),
        "1581F5BM" to Classification(Tier.FIRST_RESPONDER, "DJI M30T Dock"),
        "1581F6QA" to Classification(Tier.FIRST_RESPONDER, "DJI M3D Dock"),
        "1581F6Q8" to Classification(Tier.FIRST_RESPONDER, "DJI M3TD Dock"),
        "1581F8HH" to Classification(Tier.FIRST_RESPONDER, "DJI Matrice 4D Dock"),
        "1581F8HG" to Classification(Tier.FIRST_RESPONDER, "DJI Matrice 4TD Dock"),
        "1581F5BK" to Classification(Tier.PUBLIC_SAFETY, "DJI M30T"),
        "1581F5FJ" to Classification(Tier.PUBLIC_SAFETY, "DJI Mavic 3T"),
        "1581F7K3" to Classification(Tier.PUBLIC_SAFETY, "DJI Matrice 4T"),
        "1748FEV3" to Classification(Tier.PUBLIC_SAFETY, "Autel EVO Max"),
        "1748FEV4" to Classification(Tier.PUBLIC_SAFETY, "Autel Alpha")
    )

    private val MAKERS: List<Pair<String, Classification>> = listOf(
        "1914" to Classification(Tier.POLICE_MAKER, "BRINC drone"),
        "1668" to Classification(Tier.ANY, "Skydio drone"),
        "1588" to Classification(Tier.PUBLIC_SAFETY, "Parrot ANAFI"),
        "1839" to Classification(Tier.PUBLIC_SAFETY, "Teal drone"),
        "1867" to Classification(Tier.PUBLIC_SAFETY, "Hoverfly tethered drone"),
        "1898" to Classification(Tier.PUBLIC_SAFETY, "Fotokite tethered drone"),
        "1701" to Classification(Tier.PUBLIC_SAFETY, "Percepto dock drone"),
        "1748" to Classification(Tier.ANY, "Autel drone"),
        "1581" to Classification(Tier.ANY, "DJI drone")
    )

    private fun ascii(b: ByteArray, from: Int, len: Int): String? {
        val sb = StringBuilder()
        for (i in from until minOf(b.size, from + len)) {
            val c = b[i].toInt() and 0xFF
            if (c == 0) break
            if (c < 0x20 || c > 0x7E) return null
            sb.append(c.toChar())
        }
        return sb.toString().trim().ifEmpty { null }
    }

    private fun le32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    private fun le16(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
}
