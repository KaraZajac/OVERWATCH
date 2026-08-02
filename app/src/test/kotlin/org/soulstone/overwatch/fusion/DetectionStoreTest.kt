package org.soulstone.overwatch.fusion

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionStoreTest {

    @Test
    fun submitDropsEventsOutsideRetentionWindow() {
        var now = 100L
        val store = DetectionStore(retentionMs = 50L, nowMs = { now })
        store.submit(event(DetectionSource.BLE, "old", score = 70, timestampMs = 40L))

        now = 100L
        store.submit(event(DetectionSource.WIFI, "fresh", score = 40, timestampMs = 100L))

        assertEquals(listOf("fresh"), store.events.value.map { it.key })
        assertEquals(40, store.maxScore.value)
        assertEquals(ThreatLevel.YELLOW, store.threatLevel.value)
    }

    @Test
    fun newerEventReplacesOlderEventForSameSourceAndKey() {
        val store = DetectionStore(retentionMs = 1_000L, nowMs = { 1_000L })
        store.submit(event(DetectionSource.BLE, "same", score = 40, timestampMs = 900L))
        store.submit(event(DetectionSource.BLE, "same", score = 80, timestampMs = 950L))

        assertEquals(1, store.events.value.size)
        assertEquals(80, store.events.value.single().score)
    }

    @Test
    fun clearSourceRecomputesThreatState() {
        val store = DetectionStore(nowMs = { 1_000L })
        store.submit(event(DetectionSource.BLE, "ble", score = 90, timestampMs = 1_000L))
        store.submit(event(DetectionSource.WIFI, "wifi", score = 45, timestampMs = 1_000L))

        store.clearSource(DetectionSource.BLE)

        assertEquals(listOf("wifi"), store.events.value.map { it.key })
        assertEquals(45, store.maxScore.value)
        assertEquals(ThreatLevel.YELLOW, store.threatLevel.value)
    }

    @Test
    fun concurrentSubmissionsRemainDeduplicatedAndConsistent() {
        val store = DetectionStore(retentionMs = 10_000L, nowMs = { 1_000L })
        val workers = 4
        val perWorker = 50
        val executor = Executors.newFixedThreadPool(workers)
        val ready = CountDownLatch(workers)
        val done = CountDownLatch(workers)

        repeat(workers) { worker ->
            executor.execute {
                ready.countDown()
                ready.await()
                repeat(perWorker) { index ->
                    store.submit(
                        event(
                            source = DetectionSource.BLE,
                            key = "device-${worker * perWorker + index}",
                            score = index,
                            timestampMs = 1_000L
                        )
                    )
                }
                done.countDown()
            }
        }

        assertTrue(done.await(10, TimeUnit.SECONDS))
        executor.shutdownNow()

        assertEquals(workers * perWorker, store.events.value.size)
        assertEquals(perWorker - 1, store.maxScore.value)
    }

    private fun event(
        source: DetectionSource,
        key: String,
        score: Int,
        timestampMs: Long
    ) = DetectionEvent(
        source = source,
        key = key,
        label = key,
        score = score,
        matchedMethods = "test",
        timestampMs = timestampMs
    )
}
