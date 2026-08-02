package org.soulstone.overwatch.data.targets

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OuiMatchingTest {

    @Test
    fun knownBleOuiMatchesCaseInsensitively() {
        assertTrue(BleOuis.matches("00:25:DF:40:50:60"))
        assertTrue(BleOuis.isAxon("00:25:DF:40:50:60"))
    }

    @Test
    fun locallyAdministeredBleVariantDoesNotMatchVendorOui() {
        assertFalse(BleOuis.matches("02:25:df:40:50:60"))
        assertFalse(BleOuis.isAxon("02:25:df:40:50:60"))
    }

    @Test
    fun knownWifiOuiMatchesCaseInsensitively() {
        assertTrue(WifiOuis.matches("70:C9:4E:40:50:60"))
    }

    @Test
    fun locallyAdministeredWifiVariantDoesNotMatchVendorOui() {
        assertFalse(WifiOuis.matches("72:c9:4e:40:50:60"))
    }

    @Test
    fun locallyAdministeredBitIsClassifiedDirectly() {
        assertFalse(isLocallyAdministered("00:25:DF:40:50:60"))
        assertTrue(isLocallyAdministered("02:25:DF:40:50:60"))
        assertTrue(isLocallyAdministered("82:6B:F2:40:50:60"))
    }

    @Test
    fun knownLocalWifiPrefixIsNotTreatedAsVendorOui() {
        val bssid = "82:6B:F2:40:50:60"

        assertFalse(WifiOuis.matches(bssid))
        assertTrue(KnownLocalWifiPrefixes.matches(bssid))
        assertTrue(isLocallyAdministered(bssid))
    }

    @Test
    fun malformedAddressesDoNotMatch() {
        assertFalse(BleOuis.matches("00:25"))
        assertFalse(WifiOuis.matches("70:c9"))
        assertFalse(KnownLocalWifiPrefixes.matches("82:6b"))
    }
}
