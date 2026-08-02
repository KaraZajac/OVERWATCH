package org.soulstone.overwatch.data.targets

import java.util.Random
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ManufacturersTest {

    @Test
    fun oneByteTrailingTDoesNotReadPastEnd() {
        assertFalse(
            Manufacturers.hasTnSerial(
                byteArrayOf('T'.code.toByte())
            )
        )
    }

    @Test
    fun twentyOneByteTrailingTDoesNotReadPastEnd() {
        val payload = ByteArray(21)
        payload[payload.lastIndex] = 'T'.code.toByte()

        assertFalse(Manufacturers.hasTnSerial(payload))
    }

    @Test
    fun tnSerialAtIndexTwentyIsRecognized() {
        val payload = ByteArray(23)
        payload[20] = 'T'.code.toByte()
        payload[21] = 'N'.code.toByte()
        payload[22] = '7'.code.toByte()

        assertTrue(Manufacturers.hasTnSerial(payload))
    }

    @Test
    fun tnSerialAfterIndexTwentyIsIgnored() {
        val payload = ByteArray(24)
        payload[21] = 'T'.code.toByte()
        payload[22] = 'N'.code.toByte()
        payload[23] = '7'.code.toByte()

        assertFalse(Manufacturers.hasTnSerial(payload))
    }

    @Test
    fun variedPayloadSizesNeverThrow() {
        val random = Random(0x09C8)

        for (size in 0..64) {
            repeat(32) {
                val payload = ByteArray(size)
                random.nextBytes(payload)
                try {
                    Manufacturers.hasTnSerial(payload)
                } catch (t: Throwable) {
                    fail("hasTnSerial threw for payload size $size: ${t.message}")
                }
            }

            if (size > 0) {
                val trailingT = ByteArray(size)
                random.nextBytes(trailingT)
                trailingT[trailingT.lastIndex] = 'T'.code.toByte()
                try {
                    Manufacturers.hasTnSerial(trailingT)
                } catch (t: Throwable) {
                    fail("hasTnSerial threw for forced trailing T, payload size $size: ${t.message}")
                }
            }
        }
    }
}
