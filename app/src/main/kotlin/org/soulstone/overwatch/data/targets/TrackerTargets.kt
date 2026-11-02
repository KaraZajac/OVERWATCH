package org.soulstone.overwatch.data.targets

import java.util.UUID

/**
 * Item trackers that can be slipped into a bag or onto a car, for the TRACKER source.
 *
 * Rules follow AirGuard (seemoo-lab, Apache-2.0, v3.1.3), the open-source Android
 * tracker detector, and the IETF DULT draft. What matters is whether a tag is
 * **away from its owner**: a tag beside its owner's phone is somebody's keys, one
 * that has been separated for a while and is near you is the stalking case.
 *
 *  - **Apple Find My** (company 0x004C): type 0x12 with length 0x19 means separated
 *    (0x02 is near the owner). Status bits 4–5 give the device class: 1 AirTag,
 *    2 Find My accessory (second-generation AirTags report this too), 0 an Apple
 *    device and 3 AirPods, which are skipped so a lost iPhone isn't a "tracker".
 *  - **Samsung SmartTag** (service data 0xFD5A, first byte & 0xF8 == 0x10): the low
 *    three bits are 1 just separated, 2 lost, 3 lost over 24 h. 0xFD69 (Galaxy phones
 *    and earbuds in lost mode) and 0xFD59 (unregistered-tag onboarding) are not
 *    trackers and are not matched.
 *  - **Chipolo** (0xFE33): bit 0 of the second service-data byte is 1 when the tag
 *    hasn't seen its owner for 30 minutes, on firmware whose address starts D8 or
 *    lower; newer firmware doesn't say.
 *  - **Tile** (0xFEED, 0xFEEC, 0xFD84) and **Pebblebee** (0xFA25) never say, so they
 *    only count once they have stayed near you ([ConfidenceEngine.scoreTracker]).
 *  - **DULT** (0xFCB2, draft-ietf-dult-accessory-protocol-01 §3.4.2): a network-ID
 *    byte, then a byte whose low bit is 1 near the owner and 0 when separated.
 *  - **Google Find Hub** (0xFEAA service data, frame type 0x40 or 0x41; the Fast
 *    Pair FMDN spec): 0x41 is unwanted-tracking-protection mode, i.e. separated;
 *    0x40 doesn't say. Only those two frame types count: the same UUID carries every
 *    Eddystone shop beacon (frames 0x00–0x30). OVERWATCH declares `neverForLocation`
 *    on BLUETOOTH_SCAN, and Android withholds Eddystone and iBeacon adverts from such
 *    apps, but its default deny-list exempts exactly these two frame types
 *    (AOSP AdapterService DEFAULT_LOCATION_DENYLIST_ADVERTISING_DATA), so they arrive.
 */
object TrackerTargets {

    enum class Kind(val label: String) {
        AIRTAG("AirTag"),
        FIND_MY("Find My accessory"),
        SMARTTAG("Samsung SmartTag"),
        CHIPOLO("Chipolo"),
        TILE("Tile tracker"),
        PEBBLEBEE("Pebblebee tracker"),
        FIND_HUB("Find Hub tracker"),
        DULT("Item tracker")
    }

    /** @param separated true when the tag says it is away from its owner, null when
     *  the format doesn't say (Tile, Pebblebee, newer Chipolo). */
    data class Match(val kind: Kind, val separated: Boolean?)

    const val APPLE_COMPANY_ID = 0x004C

    private val SMARTTAG = uuid16(0xFD5A)
    private val CHIPOLO = uuid16(0xFE33)
    private val TILE: Set<UUID> = setOf(uuid16(0xFEED), uuid16(0xFEEC), uuid16(0xFD84))
    private val PEBBLEBEE = uuid16(0xFA25)
    private val DULT = uuid16(0xFCB2)
    private val FIND_HUB = uuid16(0xFEAA)

    fun classify(
        mac: String?,
        serviceUuids: List<UUID>?,
        serviceData: Map<UUID, ByteArray>?,
        manufacturerData: Map<Int, ByteArray>?
    ): Match? {
        manufacturerData?.get(APPLE_COMPANY_ID)?.let { p ->
            if (p.size >= 3 && p[0] == 0x12.toByte() && p[1] == 0x19.toByte()) {
                when ((p[2].toInt() shr 4) and 0x03) {
                    1 -> return Match(Kind.AIRTAG, separated = true)
                    2 -> return Match(Kind.FIND_MY, separated = true)
                }
            }
        }
        serviceData?.get(SMARTTAG)?.let { d ->
            if (d.isNotEmpty() && (d[0].toInt() and 0xF8) == 0x10) {
                val state = d[0].toInt() and 0x07
                if (state in 1..3) return Match(Kind.SMARTTAG, separated = true)
            }
        }
        serviceData?.get(DULT)?.let { d ->
            if (d.size >= 2 && (d[1].toInt() and 0x01) == 0) return Match(Kind.DULT, separated = true)
        }
        serviceData?.get(FIND_HUB)?.let { d ->
            when (d.firstOrNull()?.toInt()?.and(0xFF)) {
                0x41 -> return Match(Kind.FIND_HUB, separated = true)
                0x40 -> return Match(Kind.FIND_HUB, separated = null)
            }
        }
        val listed = serviceUuids.orEmpty() + serviceData?.keys.orEmpty()
        if (CHIPOLO in listed) {
            val d = serviceData?.get(CHIPOLO)
            val first = mac?.take(2)?.toIntOrNull(16)
            if (d != null && d.size >= 2 && first != null && first <= 0xD8) {
                // Old firmware states it; 0 means it saw its owner within 30 min.
                return if ((d[1].toInt() and 0x01) == 1) Match(Kind.CHIPOLO, separated = true) else null
            }
            return Match(Kind.CHIPOLO, separated = null)
        }
        if (listed.any { it in TILE }) return Match(Kind.TILE, separated = null)
        if (PEBBLEBEE in listed) return Match(Kind.PEBBLEBEE, separated = null)
        return null
    }

    private fun uuid16(v: Int): UUID =
        UUID.fromString(String.format(java.util.Locale.US, "0000%04x-0000-1000-8000-00805f9b34fb", v))
}
