package org.soulstone.overwatch.data.targets

import java.util.UUID

/**
 * Axon (TASER) and Utility BodyWorn identifiers that survive a randomised MAC.
 *
 * Source: all-cameras-are-beacons ("ACAB", github.com/soyboi1312/all-cameras-are-beacons,
 * Apache-2.0), field captures from Southern California, June–October 2026, counted
 * in its `firmware/lib/acab_core/axon_signatures.h` and `docs/signatures.md`; the
 * Utility remote name from nite-oui-collection (nitekry, MIT). OVERWATCH takes the
 * identifiers, not the code.
 *
 *  - **BWC tag.** Axon body cams put a 128-bit service-data UUID (AD 0x21) in their
 *    advert that reads "AXJANUSBWCDEVICE" once its little-endian bytes are reversed.
 *    ACAB tagged ≥13 MACs with it and five were confirmed as body cams by eye at a San
 *    Diego street fair; Blue2thprinting logged it independently by 2024-12. It has
 *    never been seen on anything that isn't Axon. Matched as ASCII "BWCDEVICE" in
 *    either byte order anywhere in the raw advert, as ACAB does.
 *  - **METROPOLIS tag.** The same scheme spelling "METROPOLISDEVICE"; 2 captures,
 *    leaning in-car.
 *  - **SIG numbers.** 0xFE6B and 0xFE6C (TASER International) and 0xFC81 (Axon
 *    Enterprise) are Axon's member UUIDs; 0x034D is TASER's company ID. A SIG number
 *    is issued to the product vendor, not to a radio-module maker. 0xFE6B rode as
 *    service data with an ASCII serial on 7 devices ("D01A…" on body cams); 0x034D
 *    counts only with the shape its one Axon-block sighting carried, 0x02 and a
 *    nine-character serial ("X870054FW"), which Blue2thprinting maps X87 to the
 *    Axon Signal Vehicle unit.
 *  - **BodyWorn.** Utility's activation remote names itself "BodyWorn Remote XXXX"
 *    (10 MACs on Utility's 00:09:bc, one observer, 2025-08).
 *
 * AxonCadabra's fake Signal trigger (0xFE6C carrying serial "X87002FP4") is a spoof
 * and is claimed by [HackerTargets] before it gets here.
 */
object AxonSignatures {

    /** The BWC tag's UUID as Android parses it (for screen-off scan filters). */
    val BWC_TAG_UUID: UUID = UUID.fromString("41584a41-4e55-5342-5743-444556494345")

    val SVC_FE6B: UUID = UUID.fromString("0000fe6b-0000-1000-8000-00805f9b34fb")
    val SVC_FE6C: UUID = UUID.fromString("0000fe6c-0000-1000-8000-00805f9b34fb")
    val SVC_FC81: UUID = UUID.fromString("0000fc81-0000-1000-8000-00805f9b34fb")

    const val TASER_COMPANY_ID = 0x034D

    private val BWC_TAG = "BWCDEVICE".toByteArray(Charsets.US_ASCII)
    private val BWC_TAG_REV = BWC_TAG.reversedArray()
    private val METROPOLIS_TAG = "METROPOLISDEVICE".toByteArray(Charsets.US_ASCII)
    private val METROPOLIS_TAG_REV = METROPOLIS_TAG.reversedArray()

    private const val BODYWORN_NAME = "BodyWorn Remote"
    private const val CID_SERIAL_LEN = 9

    enum class Kind { BWC_TAG, METROPOLIS, SIG_SERIAL, SIG_UUID, BODYWORN }

    data class Match(val kind: Kind, val label: String, val method: String)

    /**
     * The strongest Axon/Utility identifier in one advert, or null.
     *
     * @param raw the advert's raw bytes ([android.bluetooth.le.ScanRecord.getBytes])
     * @param serviceUuids the advertised service-UUID list only
     * @param serviceData service-data payloads by UUID
     * @param manufacturerData manufacturer-data payloads by company ID (company ID not included)
     */
    fun match(
        raw: ByteArray?,
        name: String?,
        serviceUuids: List<UUID>?,
        serviceData: Map<UUID, ByteArray>?,
        manufacturerData: Map<Int, ByteArray>?
    ): Match? {
        if (raw != null) {
            if (contains(raw, BWC_TAG) || contains(raw, BWC_TAG_REV)) {
                return Match(Kind.BWC_TAG, "Axon body cam", "axon_bwc")
            }
            if (contains(raw, METROPOLIS_TAG) || contains(raw, METROPOLIS_TAG_REV)) {
                return Match(Kind.METROPOLIS, "Axon device (in-car tag)", "axon_metropolis")
            }
        }
        // Service data counts as "with a serial" only when a serial is in it. Any
        // other payload is the bare Axon id, which the last rule below scores.
        serviceData?.get(SVC_FE6B)?.let { data ->
            asciiSerial(data)?.let { serial ->
                val what = if (serial.startsWith("D01A")) "Axon body cam" else "Axon device"
                return Match(Kind.SIG_SERIAL, "$what ($serial)", "axon_sig")
            }
        }
        serviceData?.get(SVC_FE6C)?.let { data ->
            asciiSerial(data)?.let { serial ->
                return Match(Kind.SIG_SERIAL, "Axon Signal trigger ($serial)", "axon_sig")
            }
        }
        manufacturerData?.get(TASER_COMPANY_ID)?.let { data ->
            cidSerial(data)?.let { serial ->
                val what = when {
                    serial.startsWith("X87") -> "Axon Signal Vehicle"
                    serial.startsWith("X85") -> "Axon Fleet power unit"
                    else -> "Axon device"
                }
                return Match(Kind.SIG_SERIAL, "$what ($serial)", "axon_sig")
            }
        }
        if (!name.isNullOrBlank() && name.contains(BODYWORN_NAME)) {
            return Match(Kind.BODYWORN, "Utility BodyWorn remote", "bodyworn_name")
        }
        val listed = serviceUuids.orEmpty() + serviceData?.keys.orEmpty()
        if (listed.any { it == SVC_FE6B || it == SVC_FE6C || it == SVC_FC81 }) {
            val label = if (serviceData?.get(SVC_FE6C)?.isNotEmpty() == true) "Axon Signal trigger"
                else "Axon device (Axon Bluetooth ID)"
            return Match(Kind.SIG_UUID, label, "axon_uuid")
        }
        return null
    }

    /** True when the 0x034D payload is 0x02 then nine uppercase letters/digits. */
    internal fun cidSerial(data: ByteArray): String? {
        if (data.size < 1 + CID_SERIAL_LEN || data[0] != 0x02.toByte()) return null
        val chars = CharArray(CID_SERIAL_LEN) { (data[1 + it].toInt() and 0xFF).toChar() }
        return if (chars.all { it in 'A'..'Z' || it in '0'..'9' }) String(chars) else null
    }

    /** The longest run of uppercase ASCII letters/digits, if it is at least 6 long. */
    internal fun asciiSerial(data: ByteArray): String? {
        var best: String? = null
        val run = StringBuilder()
        for (b in data) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c in 'A'..'Z' || c in '0'..'9') {
                run.append(c)
            } else {
                if (run.length >= 6 && run.length > (best?.length ?: 0)) best = run.toString()
                run.setLength(0)
            }
        }
        if (run.length >= 6 && run.length > (best?.length ?: 0)) best = run.toString()
        return best
    }

    internal fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
