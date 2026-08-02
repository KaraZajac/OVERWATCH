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
        if (payload == null || payload.size < 3) return false
        // A serial may start at any position from 0 through 20 (inclusive).
        val maxStartIndex = minOf(payload.size - 3, 20)
        for (index in 0..maxStartIndex) {
            if (
                payload[index] == 'T'.code.toByte() &&
                payload[index + 1] == 'N'.code.toByte()
            ) {
                // Followed by digits = high-confidence Penguin/Flock serial
                val c = payload[index + 2].toInt().toChar()
                if (c in '0'..'9') return true
            }
        }
        return false
    }
}
