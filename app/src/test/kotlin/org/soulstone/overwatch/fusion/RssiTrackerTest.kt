package org.soulstone.overwatch.fusion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RssiTrackerTest {

    @Test
    fun risePeakFallWithMinimumRangeIsStationary() {
        val tracker = RssiTracker(windowSize = 5, minRangeDb = 6)
        listOf(-70, -66, -60, -66, -70).forEach { tracker.update("camera", it) }

        assertTrue(tracker.isStationary("camera"))
    }

    @Test
    fun peakAtEitherEdgeIsNotStationary() {
        val rising = RssiTracker(windowSize = 5, minRangeDb = 6)
        listOf(-70, -66, -60).forEach { rising.update("rising", it) }
        val falling = RssiTracker(windowSize = 5, minRangeDb = 6)
        listOf(-60, -66, -70).forEach { falling.update("falling", it) }

        assertFalse(rising.isStationary("rising"))
        assertFalse(falling.isStationary("falling"))
    }

    @Test
    fun rangeBelowThresholdIsNotStationary() {
        val tracker = RssiTracker(windowSize = 5, minRangeDb = 6)
        listOf(-70, -65, -70).forEach { tracker.update("quiet", it) }

        assertFalse(tracker.isStationary("quiet"))
    }

    @Test
    fun samplesAreBoundedByWindowSize() {
        val tracker = RssiTracker(windowSize = 3, minRangeDb = 6)
        listOf(-70, -60, -70, -70).forEach { tracker.update("camera", it) }

        assertFalse(tracker.isStationary("camera"))
    }

    @Test
    fun clearRemovesAllDeviceHistory() {
        val tracker = RssiTracker()
        listOf(-70, -60, -70).forEach { tracker.update("camera", it) }
        assertTrue(tracker.isStationary("camera"))

        tracker.clear()

        assertFalse(tracker.isStationary("camera"))
    }
}
