package org.soulstone.overwatch.data.targets

/**
 * BLE manufacturer-specific data signatures.
 *
 * Source: flock-detection.
 * - Company ID 0x09C8 (XUNTONG, Raven manufacturer): score 60.
 * - "TN" ASCII prefix in payload (Penguin/Flock TN serial e.g. TN72023022000771): +20.
 */
object Manufacturers {

    const val XUNTONG_COMPANY_ID = 0x09C8

    fun hasTnSerial(payload: ByteArray?): Boolean {
        if (payload == null || payload.size < 2) return false
        // Look for "TN" anywhere in the first ~20 bytes (post-header)
        val limit = minOf(payload.size - 1, 20)
        for (i in 0..limit) {
            if (payload[i] == 'T'.code.toByte() && payload[i + 1] == 'N'.code.toByte()) {
                // Followed by digits = high-confidence Penguin/Flock serial
                if (i + 2 < payload.size) {
                    val c = payload[i + 2].toInt().toChar()
                    if (c in '0'..'9') return true
                }
            }
        }
        return false
    }
}
