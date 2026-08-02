package org.soulstone.overwatch.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeflockScannerTest {

    @Test
    fun failedAttemptDoesNotRetryDuringBackoffAtSameLocation() {
        assertFalse(
            DeflockScanner.shouldRefetchAfterAttempt(
                lastAttemptOk = false,
                elapsedMs = 59_999L,
                distanceMeters = 0f
            )
        )
    }

    @Test
    fun failedAttemptRetriesAfterBackoffWithoutMovement() {
        assertTrue(
            DeflockScanner.shouldRefetchAfterAttempt(
                lastAttemptOk = false,
                elapsedMs = 60_000L,
                distanceMeters = 0f
            )
        )
    }
}
