package org.soulstone.overwatch.fusion

import java.util.UUID

/**
 * Bluetooth "pop-up" spam: the floods of fake AirPods, Apple TV, Fast Pair, Samsung
 * and Windows pairing adverts that a Flipper Zero, an ESP32 (Bruce, Marauder, Sour
 * Apple) or an Android BLE-spam app sends, and the iOS 17 crash advert.
 *
 * Patterns are from the tools' own source (Momentum-Apps `ble_spam`, AppleJuice,
 * ESP32-Sour-Apple; GPL and Apache code, used here as facts only):
 *
 *  - **One packet is enough** for two fixed shapes. The crash advert is an Apple
 *    Nearby Action (`0F 05 …`) followed by `00 00 10` and a length byte that runs
 *    past the end of the payload; no real Apple device sends a structure that
 *    overruns its own advert. AppleJuice's templates carry fixed bytes a real device
 *    randomises (`… 60 4C 95` as an authentication tag, `20 75 AA 30 01 00 00 45 12 12 12`
 *    as AirPods state).
 *  - **Everything else needs churn**, because a real AirPods case sends the same
 *    shape: either 40+ new addresses carrying pop-up adverts within 10 s (address-
 *    rotating spammers send one per 20–100 ms; a crowd's AirPods rotate every ~15
 *    minutes), or one address cycling through 4+ different fake models or actions
 *    within 10 s (Flipper's Apple modes keep one address per run).
 *
 * Every scan (re)start opens a 15 s warm-up in which the new-address count is not
 * judged, because at that moment every device in range is new.
 */
class BleSpamDetector(private val nowMs: () -> Long = System::currentTimeMillis) {

    data class Finding(val key: String, val label: String, val method: String)

    private val firstSeen = HashMap<String, Long>()
    private val variants = HashMap<String, ArrayDeque<Pair<Long, Int>>>()
    private var warmUpUntil = 0L
    private var lastPrune = 0L

    @Synchronized
    fun restartWarmUp() {
        warmUpUntil = nowMs() + WARM_UP_MS
    }

    /**
     * Look at one advert. Returns a finding when this advert proves (or completes the
     * evidence for) spam; null otherwise.
     *
     * @param apple the Apple (0x004C) manufacturer payload, company ID excluded
     * @param manufacturerData every manufacturer payload by company ID
     */
    @Synchronized
    fun observe(
        mac: String,
        apple: ByteArray?,
        manufacturerData: Map<Int, ByteArray>?,
        serviceData: Map<UUID, ByteArray>?
    ): Finding? {
        val now = nowMs()
        if (now - lastPrune > PRUNE_EVERY_MS) prune(now)

        if (apple != null) {
            if (isCrashAdvert(apple)) {
                return Finding("blespam:crash", "iOS crash advert (Sour Apple / Flipper)", "ble_spam_crash")
            }
            if (isAppleJuice(apple)) {
                return Finding("blespam:applejuice", "Apple pop-up spam (AppleJuice)", "ble_spam_template")
            }
        }

        val variant = popupVariant(apple, manufacturerData, serviceData) ?: return null
        firstSeen.putIfAbsent(mac, now)
        val seq = variants.getOrPut(mac) { ArrayDeque() }
        seq.addLast(now to variant)
        while (seq.isNotEmpty() && now - seq.first().first > WINDOW_MS) seq.removeFirst()

        val distinct = seq.map { it.second }.toSet().size
        if (distinct >= CYCLE_THRESHOLD) {
            return Finding("blespam:cycle:$mac", "BLE spam — one transmitter faking $distinct devices ($mac)", "ble_spam_cycle")
        }
        if (now >= warmUpUntil) {
            val fresh = firstSeen.values.count { now - it <= WINDOW_MS }
            if (fresh >= FLOOD_THRESHOLD) {
                return Finding("blespam:flood", "BLE spam flood — $fresh new pop-up devices in 10 s", "ble_spam_flood")
            }
        }
        return null
    }

    private fun prune(now: Long) {
        lastPrune = now
        firstSeen.entries.removeAll { now - it.value > FORGET_MS }
        variants.entries.removeAll { (_, q) -> q.isEmpty() || now - q.last().first > WINDOW_MS }
    }

    companion object {
        private const val WINDOW_MS = 10_000L
        private const val WARM_UP_MS = 15_000L
        private const val FORGET_MS = 120_000L
        private const val PRUNE_EVERY_MS = 5_000L
        private const val FLOOD_THRESHOLD = 40
        private const val CYCLE_THRESHOLD = 4

        private val FAST_PAIR: UUID = UUID.fromString("0000fe2c-0000-1000-8000-00805f9b34fb")
        private const val SAMSUNG = 0x0075
        private const val MICROSOFT = 0x0006

        private val SAMSUNG_BUDS = byteArrayOf(0x42, 0x09, 0x81.toByte(), 0x02, 0x14, 0x15, 0x03, 0x21, 0x01, 0x09)
        private val SAMSUNG_WATCH = byteArrayOf(0x01, 0x00, 0x02, 0x00, 0x01, 0x01, 0xFF.toByte(), 0x00, 0x00, 0x43)
        private val SWIFT_PAIR = byteArrayOf(0x03, 0x00, 0x80.toByte())

        /** `0F 05 <flags> <action> <tag ×3> 00 00 10 <len>` with len past the end. */
        internal fun isCrashAdvert(p: ByteArray): Boolean {
            for (k in 0..p.size - 11) {
                if (p[k] == 0x0F.toByte() && p[k + 1] == 0x05.toByte() &&
                    p[k + 7] == 0x00.toByte() && p[k + 8] == 0x00.toByte() && p[k + 9] == 0x10.toByte()
                ) {
                    val len = p[k + 10].toInt() and 0xFF
                    val remaining = p.size - (k + 11)
                    if (len > remaining) return true
                }
            }
            return false
        }

        /** AppleJuice's fixed Nearby Action and AirPods templates (applejuice.ino:36-65). */
        internal fun isAppleJuice(p: ByteArray): Boolean {
            // 0F 05 C1 <action> 60 4C 95 00 00 10 00 00 00
            for (k in 0..p.size - 13) {
                if (p[k] == 0x0F.toByte() && p[k + 1] == 0x05.toByte() && p[k + 2] == 0xC1.toByte() &&
                    p[k + 4] == 0x60.toByte() && p[k + 5] == 0x4C.toByte() && p[k + 6] == 0x95.toByte() &&
                    p[k + 7] == 0x00.toByte() && p[k + 8] == 0x00.toByte() && p[k + 9] == 0x10.toByte()
                ) return true
            }
            // 07 19 07 <model> 20 75 AA 30 01 00 00 45 12 12 12
            if (p.size >= 15 && p[0] == 0x07.toByte() && p[1] == 0x19.toByte() && p[2] == 0x07.toByte() &&
                p[4] == 0x20.toByte() && p[5] == 0x75.toByte() && p[6] == 0xAA.toByte() && p[7] == 0x30.toByte() &&
                p[11] == 0x45.toByte() && p[12] == 0x12.toByte() && p[13] == 0x12.toByte() && p[14] == 0x12.toByte()
            ) return true
            return false
        }

        /** A pop-up-style advert's "what it claims to be", as an int, or null. */
        internal fun popupVariant(
            apple: ByteArray?,
            manufacturerData: Map<Int, ByteArray>?,
            serviceData: Map<UUID, ByteArray>?
        ): Int? {
            if (apple != null && apple.size >= 2) {
                // Walk Apple's type-length-value records, so bytes inside an
                // encrypted field can never pass for a record header.
                var i = 0
                while (i + 2 <= apple.size) {
                    val type = apple[i].toInt() and 0xFF
                    val len = apple[i + 1].toInt() and 0xFF
                    if (i + 2 + len > apple.size) break
                    // Proximity Pairing: 07 19 <prefix> <model hi> <model lo> …
                    if (type == 0x07 && len == 0x19) {
                        return 0x0700_0000 or ((apple[i + 3].toInt() and 0xFF) shl 8) or (apple[i + 4].toInt() and 0xFF)
                    }
                    // Nearby Action: 0F 05 <flags> <action> <tag ×3>
                    if (type == 0x0F && len == 0x05) {
                        return 0x0F00_0000 or (apple[i + 3].toInt() and 0xFF)
                    }
                    i += 2 + len
                }
            }
            serviceData?.get(FAST_PAIR)?.let { d ->
                // Discoverable Fast Pair adverts carry the bare 3-byte model ID.
                if (d.size == 3) {
                    return 0x2C00_0000 or ((d[0].toInt() and 0xFF) shl 16) or
                        ((d[1].toInt() and 0xFF) shl 8) or (d[2].toInt() and 0xFF)
                }
            }
            manufacturerData?.get(SAMSUNG)?.let { d ->
                if (startsWith(d, SAMSUNG_BUDS) || startsWith(d, SAMSUNG_WATCH)) {
                    return 0x7500_0000 or d.drop(10).take(2).fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
                }
            }
            manufacturerData?.get(MICROSOFT)?.let { d ->
                if (startsWith(d, SWIFT_PAIR)) return 0x0600_0000 or d.size
            }
            return null
        }

        private fun startsWith(d: ByteArray, prefix: ByteArray): Boolean =
            d.size >= prefix.size && prefix.indices.all { d[it] == prefix[it] }
    }
}
