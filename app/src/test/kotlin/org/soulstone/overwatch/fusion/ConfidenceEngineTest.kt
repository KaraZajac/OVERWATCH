package org.soulstone.overwatch.fusion

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfidenceEngineTest {

    @Test
    fun standardBluetoothServicesDoNotReceiveRavenScore() {
        listOf("180a", "1809", "1819").forEach { shortUuid ->
            val scored = ConfidenceEngine.scoreBle(bleObservation(uuid16(shortUuid)))

            assertEquals(0, scored.score)
            assertFalse(scored.methods.contains("raven"))
            assertFalse(scored.label.startsWith("Raven gunshot detector"))
        }
    }

    @Test
    fun eachRavenSpecificUuidRetainsSingleMatchScore() {
        listOf("3100", "3200", "3300", "3400", "3500").forEach { shortUuid ->
            val scored = ConfidenceEngine.scoreBle(bleObservation(uuid16(shortUuid)))

            assertEquals(ConfidenceEngine.W_BLE_RAVEN_UUID, scored.score)
            assertEquals("raven_uuid", scored.methods)
            assertTrue(scored.label.startsWith("Raven gunshot detector"))
        }
    }

    @Test
    fun threeRavenSpecificUuidsRetainMultiMatchScore() {
        val scored = ConfidenceEngine.scoreBle(
            bleObservation(uuid16("3100"), uuid16("3200"), uuid16("3300"))
        )

        assertEquals(ConfidenceEngine.W_BLE_RAVEN_UUID_MULTI, scored.score)
        assertEquals("raven_multi", scored.methods)
    }

    @Test
    fun twoRavenSpecificUuidsPlusStandardUuidUseSingleMatchScore() {
        val scored = ConfidenceEngine.scoreBle(
            bleObservation(uuid16("3100"), uuid16("3200"), uuid16("180a"))
        )

        assertEquals(ConfidenceEngine.W_BLE_RAVEN_UUID, scored.score)
        assertEquals("raven_uuid", scored.methods)
    }

    @Test
    fun duplicateRavenSpecificUuidDoesNotInflateScore() {
        val ravenUuid = uuid16("3100")
        val scored = ConfidenceEngine.scoreBle(
            bleObservation(ravenUuid, ravenUuid, ravenUuid)
        )

        assertEquals(ConfidenceEngine.W_BLE_RAVEN_UUID, scored.score)
        assertEquals("raven_uuid", scored.methods)
    }

    @Test
    fun mixedAdvertisementClassifiesRavenUsingOnlySpecificUuids() {
        val scored = ConfidenceEngine.scoreBle(
            bleObservation(uuid16("1809"), uuid16("3100"), uuid16("1819"))
        )

        assertEquals(ConfidenceEngine.W_BLE_RAVEN_UUID, scored.score)
        assertEquals("raven_uuid", scored.methods)
        assertTrue(scored.label.startsWith("Raven gunshot detector"))
    }

    @Test
    fun unrelatedUuidBehaviorRemainsUnchanged() {
        val scored = ConfidenceEngine.scoreBle(bleObservation(uuid16("180d")))

        assertEquals(0, scored.score)
        assertEquals("", scored.methods)
        assertFalse(scored.label.startsWith("Raven gunshot detector"))
    }

    @Test
    fun bleStrongRssiBonusStartsAboveNegativeFifty() {
        val base = ConfidenceEngine.BleObservation(
            mac = "10:20:30:40:50:60",
            rssi = -50,
            deviceName = null,
            advertisedUuids = null,
            manufacturerCompanyId = null,
            manufacturerPayload = null
        )
        val atBoundary = ConfidenceEngine.scoreBle(base)
        val justStronger = ConfidenceEngine.scoreBle(base.copy(rssi = -49))

        assertEquals(0, atBoundary.score)
        assertEquals(10, justStronger.score)
    }

    @Test
    fun multipleBleMethodsReceiveCorroborationBonus() {
        val scored = ConfidenceEngine.scoreBle(
            ConfidenceEngine.BleObservation(
                mac = "00:25:df:40:50:60",
                rssi = -60,
                deviceName = "FlockCam",
                advertisedUuids = null,
                manufacturerCompanyId = null,
                manufacturerPayload = null
            )
        )

        assertEquals(145.coerceAtMost(100), scored.score)
        assertTrue(scored.methods.contains("axon_oui"))
        assertTrue(scored.methods.contains("name"))
        assertTrue(scored.methods.contains("multi"))
        assertTrue(scored.isAxon)
    }

    @Test
    fun deflockScoreChangesAtFiftyMeterBoundary() {
        val veryNear = ConfidenceEngine.scoreDeflock(
            ConfidenceEngine.DeflockObservation(1L, 50f, null, null)
        )
        val near = ConfidenceEngine.scoreDeflock(
            ConfidenceEngine.DeflockObservation(1L, 50.1f, null, null)
        )

        assertEquals(ConfidenceEngine.W_DEFLOCK_VERY_NEAR, veryNear.score)
        assertEquals(ConfidenceEngine.W_DEFLOCK_NEAR, near.score)
    }

    @Test
    fun microphoneScoresNeverExceedOrangeCap() {
        val scored = ConfidenceEngine.scoreMicBle(
            ConfidenceEngine.MicBleObservation(
                mac = "0c:47:c9:40:50:60",
                rssi = -10,
                deviceName = "Echo Dot",
                advertisedUuids = listOf(
                    java.util.UUID.fromString("0000fe03-0000-1000-8000-00805f9b34fb")
                ),
                manufacturerCompanyId = 0x0171,
                isStationary = true
            )
        )

        assertEquals(ConfidenceEngine.MIC_SCORE_CAP, scored.score)
    }

    @Test
    fun knownLocalWifiPrefixIsWeakerThanSubmissionThreshold() {
        val scored = ConfidenceEngine.scoreWifi(
            ConfidenceEngine.WifiObservation(
                bssid = "82:6B:F2:40:50:60",
                ssid = null,
                rssi = -70,
                isStationary = false
            )
        )

        assertEquals(ConfidenceEngine.W_WIFI_KNOWN_LOCAL_PREFIX, scored.score)
        assertEquals("known_local_prefix", scored.methods)
        assertFalse(scored.score >= ConfidenceEngine.WIFI_SUBMISSION_THRESHOLD)
    }

    @Test
    fun globallyAssignedWifiOuiKeepsVendorScore() {
        val scored = ConfidenceEngine.scoreWifi(
            ConfidenceEngine.WifiObservation(
                bssid = "70:C9:4E:40:50:60",
                ssid = null,
                rssi = -70,
                isStationary = false
            )
        )

        assertEquals(ConfidenceEngine.W_WIFI_OUI, scored.score)
        assertEquals("oui", scored.methods)
    }

    @Test
    fun knownLocalWifiPrefixCanBeCorroboratedByIndependentSsidMethod() {
        val scored = ConfidenceEngine.scoreWifi(
            ConfidenceEngine.WifiObservation(
                bssid = "82:6B:F2:40:50:60",
                ssid = "flock-network",
                rssi = -70,
                isStationary = false
            )
        )

        assertEquals(
            ConfidenceEngine.W_WIFI_KNOWN_LOCAL_PREFIX +
                ConfidenceEngine.W_WIFI_SSID_GENERIC +
                ConfidenceEngine.B_MULTI_METHOD,
            scored.score
        )
        assertEquals("known_local_prefix ssid_generic multi", scored.methods)
        assertTrue(scored.score >= ConfidenceEngine.WIFI_SUBMISSION_THRESHOLD)
    }

    private fun bleObservation(vararg advertisedUuids: UUID) =
        ConfidenceEngine.BleObservation(
            mac = "10:20:30:40:50:60",
            rssi = -70,
            deviceName = null,
            advertisedUuids = advertisedUuids.toList(),
            manufacturerCompanyId = null,
            manufacturerPayload = null
        )

    private fun uuid16(short: String): UUID =
        UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
}
