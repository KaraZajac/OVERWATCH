package org.soulstone.overwatch.data.targets

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternsTest {

    @Test
    fun bleNamePatternsRemainCaseSensitive() {
        assertTrue(Patterns.bleNameMatch("FlockCam-123"))
        assertFalse(Patterns.bleNameMatch("flockcam-123"))
        assertFalse(Patterns.bleNameMatch(null))
    }

    @Test
    fun penguinNumericNamesUseEightToTwelveDigits() {
        assertTrue(Patterns.isPenguinNumeric("12345678"))
        assertTrue(Patterns.isPenguinNumeric("123456789012"))
        assertFalse(Patterns.isPenguinNumeric("1234567"))
        assertFalse(Patterns.isPenguinNumeric("1234567890123"))
        assertFalse(Patterns.isPenguinNumeric("1234567A"))
    }

    @Test
    fun genericSsidMatchingIsCaseInsensitive() {
        assertTrue(Patterns.ssidGenericMatch("Guest-FLOCK-5G"))
        assertTrue(Patterns.ssidGenericMatch("FS_EXT-123"))
        assertFalse(Patterns.ssidGenericMatch("home-network"))
        assertFalse(Patterns.ssidGenericMatch(null))
    }

    @Test
    fun flockSsidRequiresExactlyFourHexDigits() {
        assertTrue(Patterns.ssidFlockFormat("Flock-1A2f"))
        assertFalse(Patterns.ssidFlockFormat("Flock-1A2"))
        assertFalse(Patterns.ssidFlockFormat("Flock-1A2FG"))
        assertFalse(Patterns.ssidFlockFormat("Flock-1A2F-extra"))
        assertFalse(Patterns.ssidFlockFormat("flock-1A2F"))
    }
}
