package org.soulstone.overwatch.data.targets

import java.util.UUID

/**
 * Offensive and pentest radio gear, for the HACKER source: equipment that transmits
 * *at* other radios, rather than equipment that watches.
 *
 * Every identifier comes from the tool's own source or its vendor's documentation,
 * read in November 2026 (repo@commit in SOURCES.md §3.10). Identifiers are facts;
 * none of these projects' code is used, and several are GPL. Matching is exact
 * wherever the tool sends a fixed value, because a guess copied between detectors
 * is how a Sonos speaker spent eleven releases labelled "Vigilant" in another one.
 *
 * Deliberately not here: bare Espressif addresses (a nyanBOX, a Marauder, an M5Stack
 * and a smart plug are the same chip), Flipper Devices' own IEEE block 0C:FA:22 and
 * company ID 0x0E29 and UUID 0x308A (all three are the BUSY Bar desk sign, not a
 * Zero), Hak5 "OUIs" (Hak5 holds no IEEE registration), and the Pwnagotchi, whose
 * peer beacons carry no SSID element and are dropped by Android's Wi-Fi stack
 * before any app could see them.
 */
object HackerTargets {

    enum class Kind {
        FLIPPER, FLIPPER_HID, NYANBOX, AXONCADABRA,
        TOOL_AP, PORTAL_AP, ROGUE_AP, MARAUDER_DECOY, SKIMMER
    }

    data class Match(val kind: Kind, val label: String, val method: String)

    // ---- Flipper Zero ------------------------------------------------------------

    /** The serial profile's service UUID is 0x3080 plus the case colour (0 unknown,
     *  1 black, 2 white, 3 transparent): flipperzero-firmware serial_profile.c:54 and
     *  furi_hal_version.h:34-39. 0x308A is the BUSY Bar and is not matched. */
    private val FLIPPER_UUIDS: Set<UUID> = (0x3080..0x3083).map(::uuid16).toSet()

    /** The Zero builds its public address from ST's company ID and the WB55 chip
     *  ID, so stock units advertise from 80:E1:26 (furi_hal_version.c:107-116; the
     *  BLE remote mode adds one to the fourth octet). Not an IEEE registration, so it
     *  only ever supports another signal. Wall of Flippers also checks 80:E1:27,
     *  which nothing in the firmware explains; it is not used. */
    private val FLIPPER_ADDRESS_PREFIXES = listOf("80:e1:26")

    /** HID over GATT: the Zero's Bluetooth remote and BadUSB-over-BLE modes. */
    private val HID_UUID: UUID = uuid16(0x1812)

    fun isFlipperUuid(uuids: List<UUID>?): Boolean = uuids?.any { it in FLIPPER_UUIDS } == true

    /** Stock and Unleashed name the unit "Flipper <name>"; Momentum drops the prefix. */
    fun isFlipperName(name: String?): Boolean = name?.startsWith("Flipper ") == true

    fun isFlipperAddress(mac: String?): Boolean {
        val lower = mac?.lowercase() ?: return false
        return FLIPPER_ADDRESS_PREFIXES.any { lower.startsWith(it) }
    }

    /** Remote / BadUSB-over-BLE mode: HID service, a "Control …" or "BadUSB …" name,
     *  and a Flipper address. All three, because 0x1812 alone is any keyboard. */
    fun isFlipperHid(mac: String?, name: String?, uuids: List<UUID>?): Boolean {
        if (!isFlipperAddress(mac)) return false
        if (uuids?.contains(HID_UUID) != true) return false
        return name != null && (name.startsWith("Control ") || name.startsWith("BadUSB "))
    }

    // ---- nyanBOX -----------------------------------------------------------------

    /** "nyanBOX-service!" as a UUID, from nyanBOX's published legacy source
     *  (nyanbox_advertiser.cpp:27-89); the scan response names it nyanBOX-XXXX. */
    private val NYANBOX_UUID: UUID = UUID.fromString("6e79616e-424f-582d-7365-727669636521")

    fun isNyanBox(name: String?, uuids: List<UUID>?): Boolean =
        uuids?.contains(NYANBOX_UUID) == true || name?.startsWith("nyanBOX-") == true

    // ---- AxonCadabra ---------------------------------------------------------------

    private val AXONCADABRA_SERIAL = "X87002FP4".toByteArray(Charsets.US_ASCII)

    /** AxonCadabra replays one captured Axon Signal trigger: 0xFE6C service data
     *  carrying serial X87002FP4 (its MainActivity.kt:38-46). Real Axon gear is
     *  matched in [AxonSignatures]; this exact serial is the tool. */
    fun isAxonCadabra(serviceData: Map<UUID, ByteArray>?): Boolean {
        val data = serviceData?.get(AxonSignatures.SVC_FE6C) ?: return false
        return AxonSignatures.contains(data, AXONCADABRA_SERIAL)
    }

    // ---- Card skimmers (Bluetooth Classic) -----------------------------------------

    /** Default names of the serial modules gas-pump skimmers are built from
     *  (SparkFun Skimmer Scanner; ESP32 Marauder's skimmer detector). They are
     *  Bluetooth Classic, which only a Classic discovery can see. */
    private val SKIMMER_NAMES: Set<String> = setOf("HC-03", "HC-05", "HC-06", "RN42", "BT04-A")

    fun skimmerName(name: String?): Match? {
        if (name.isNullOrBlank()) return null
        val n = name.trim()
        if (n in SKIMMER_NAMES || n.startsWith("RNBT-")) {
            return Match(Kind.SKIMMER, "Possible card skimmer — $n Bluetooth module", "skimmer_name")
        }
        return null
    }

    // ---- Wi-Fi ---------------------------------------------------------------------

    private data class SsidRule(val kind: Kind, val label: String, val matches: (String) -> Boolean)

    /** P4wnP1 A.L.O.A.'s default SSID, as code points (defaults.go:19-38). */
    private val P4WNP1_SSID: String = intArrayOf(
        0x1F4A5, 0x1F5A5, 0x1F4A5, 0x20, 0x24C5, 0x2783, 0x24CC, 0x24C3, 0x1F15F, 0x2776
    ).joinToString("") { String(Character.toChars(it)) }

    /** Default AP names. Owners can rename every one, so these are medium-grade. */
    private val SSID_RULES: List<SsidRule> = listOf(
        SsidRule(Kind.TOOL_AP, "WiFi Pineapple setup network") { it.startsWith("Pineapple_") },
        SsidRule(Kind.TOOL_AP, "ESP deauther control network") { it.startsWith("pwned") },
        SsidRule(Kind.TOOL_AP, "Bruce firmware network") {
            it.equals("BruceNet", ignoreCase = true) || it == "BruceShell" || it == "BRUCE-UDP"
        },
        SsidRule(Kind.TOOL_AP, "GhostESP network") { it == "GhostNet" },
        SsidRule(Kind.TOOL_AP, "Flipper Wi-Fi dev board") { it == "blackmagic" },
        SsidRule(Kind.TOOL_AP, "O.MG cable network") { it == "O.MG" },
        SsidRule(Kind.TOOL_AP, "USB Nugget network") { it == "Nugget AP" },
        SsidRule(Kind.TOOL_AP, "WiFi Duck keystroke injector") { it == "wifiduck" },
        SsidRule(Kind.TOOL_AP, "ESPloitV2 / WHID Cactus network") { it == "Exploit" },
        SsidRule(Kind.TOOL_AP, "Evil Crow RF network") { it == "Evil Crow RF v2" },
        SsidRule(Kind.TOOL_AP, "P4wnP1 network") { it == P4WNP1_SSID },
        SsidRule(Kind.PORTAL_AP, "Evil-portal default network") {
            it == "Evil Portal" || it == "EvilPortal" || it == "EvilAP" || it == "NEMO Free WiFi" ||
                it.startsWith("Evil-") || it.startsWith("Evil‑")
        }
    )

    fun matchSsid(ssid: String?): Match? {
        if (ssid.isNullOrBlank()) return null
        val rule = SSID_RULES.firstOrNull { it.matches(ssid) } ?: return null
        return Match(rule.kind, "${rule.label} — $ssid", if (rule.kind == Kind.PORTAL_AP) "portal_ssid" else "tool_ssid")
    }

    /** Rogue-AP frameworks' fixed default BSSIDs. */
    fun matchRogueBssid(bssid: String?): Match? {
        val b = bssid?.lowercase() ?: return null
        val label = when {
            // wifiphisher: the 00:00:00 (Xerox) block unless -iNM / -iAM is given
            b == "00:00:00:00:00:00" -> return null   // "no address", not an address
            b.startsWith("00:00:00:") -> "wifiphisher rogue AP (default address)"
            b == "00:11:22:33:44:00" -> "eaphammer rogue AP (default address)"
            b == "bc:f6:85:03:36:5b" -> "WiFi Pumpkin 3 rogue AP (default address)"
            else -> return null
        }
        return Match(Kind.ROGUE_AP, label, "rogue_bssid")
    }

    /** ESP32 Marauder's wardrive mode beacons a network named exactly "Flock" from
     *  the ESP's own soft-AP address (WiFiScan.cpp ~6459, with camera-style IEs), so
     *  a bare "Flock" from an Espressif block is that, not Flock hardware. */
    fun isMarauderFlockDecoy(ssid: String?, bssid: String?): Boolean =
        Patterns.ssidIsBareFlock(ssid) && EspressifOuis.contains(bssid)

    /** One access point as the beacon-spam check sees it. */
    data class ApBrief(val bssid: String, val ssid: String?, val capabilities: String?)

    data class SpamFinding(val label: String, val method: String, val count: Int)

    /**
     * Beacon spam across one scan's results, or null.
     *
     *  - The ESP8266 deauther's beacons carry an RSN element that lists the same
     *    cipher twice, which Android renders as `[WPA2-PSK-CCMP+CCMP]`
     *    (esp8266_deauther Attack.h:194-208; AOSP InformationElementUtil).
     *  - Marauder's random-BSSID spam sets the multicast bit on about half of
     *    them, which no real access point does (WiFiScan.cpp:9666-9714).
     *  - The Rick-roll lists ("Never gonna give you up" …) ship with Marauder,
     *    NEMO and the deauther.
     * Three or more of a kind are required, so one odd router never trips it.
     */
    fun beaconSpam(aps: List<ApBrief>): SpamFinding? {
        var dupCipher = 0
        var multicast = 0
        var rickroll = 0
        for (ap in aps) {
            val caps = ap.capabilities.orEmpty()
            if ("CCMP+CCMP" in caps || "TKIP+TKIP" in caps) dupCipher++
            val first = ap.bssid.take(2).toIntOrNull(16)
            if (first != null && (first and 0x01) == 1) multicast++
            if (ap.ssid != null && RICKROLL.containsMatchIn(ap.ssid)) rickroll++
        }
        return when {
            rickroll >= 3 -> SpamFinding("Wi-Fi beacon spam — $rickroll Rick-roll networks", "beacon_spam_rickroll", rickroll)
            dupCipher >= 3 -> SpamFinding("Wi-Fi beacon spam — $dupCipher fake networks (deauther)", "beacon_spam_rsn", dupCipher)
            multicast >= 3 -> SpamFinding("Wi-Fi beacon spam — $multicast fake networks (random BSSIDs)", "beacon_spam_bssid", multicast)
            else -> null
        }
    }

    /** One BSSID advertising five or more different network names in one scan:
     *  wifiphisher's Known Beacons, Karma-style rogue APs. Returns that BSSID and
     *  its name count. */
    fun manyNamesOneBssid(aps: List<ApBrief>): Pair<String, Int>? {
        val names = HashMap<String, MutableSet<String>>()
        for (ap in aps) {
            val ssid = ap.ssid ?: continue
            names.getOrPut(ap.bssid.lowercase()) { HashSet() }.add(ssid)
        }
        val top = names.maxByOrNull { it.value.size } ?: return null
        return if (top.value.size >= 5) top.key to top.value.size else null
    }

    private val RICKROLL = Regex("^(\\d\\d )?never gonna", RegexOption.IGNORE_CASE)

    private fun uuid16(v: Int): UUID =
        UUID.fromString(String.format(java.util.Locale.US, "0000%04x-0000-1000-8000-00805f9b34fb", v))
}
