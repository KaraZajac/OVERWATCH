package org.soulstone.overwatch.data.targets

/**
 * BLE manufacturer-specific data signatures.
 *
 * - Company ID 0x09C8 (XUNTONG in the Bluetooth SIG registry): score 60. It is the
 *   **Penguin battery pack's** ID, not Raven's maker's, as this file said until
 *   v0.6.0: it rides on 7,575 of the 17,410 `Penguin-` records in flock-you's WiGLE
 *   set and on none of its 4,907 `FS Ext Battery` ones, and the camera and Raven
 *   radios are LiteOn/Qualcomm and ESP32. XUNTONG is probably the pack's BLE-module
 *   supplier.
 * - "TN" ASCII prefix in payload (Penguin/Flock TN serial e.g. TN72023022000771): +20.
 */
object Manufacturers {

    const val XUNTONG_COMPANY_ID = 0x09C8

    fun hasTnSerial(payload: ByteArray?): Boolean {
        if (payload == null || payload.size < 2) return false
        // Look for "TN" anywhere in the first ~20 bytes (post-header). The last
        // start index is size - 2, so payload[i + 1] stays in bounds; until v0.6.0
        // it was size - 1, which read past the end when a payload ended in 'T'.
        val limit = minOf(payload.size - 2, 20)
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
