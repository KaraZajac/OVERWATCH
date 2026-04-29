# [DЯΣΛMMΛKΣЯ]
## &nbsp;&nbsp;&nbsp;. //0VΣЯW4TCH

A native Android (Kotlin) **passive surveillance-detection** app. Open it, hit
**START**, and a circle turns **green / yellow / orange / red** depending on
how confident the engine is that there's a Flock Safety ALPR, an Axon body
camera, or police presence near you.

> **Passive defense only.** OVERWATCH only listens — it does not transmit,
> probe, jam, or interfere with any device or network. The Axon
> advertise/fuzz code from one of the reference projects is intentionally
> excluded.

---

## What it detects

| Source | What it looks at | Where it comes from |
|---|---|---|
| **BLE** | Bluetooth-LE advertisements: vendor MAC OUIs (Axon, Flock Penguin / Raven, XUNTONG mfg id `0x09C8`, "TN" serial pattern), Raven service UUIDs, device-name patterns | Local radio scan (BLE callback API) |
| **WiFi** | BSSID OUI prefixes for Flock infrastructure (31-prefix superset), `Flock-XXXX` and other generic SSID patterns | `WifiManager.getScanResults()` polled every 35 s (just under the Android 11+ 4-scans/2-min throttle) |
| **DEFLOCK** | Crowdsourced ALPR locations within configurable proximity (default 200 m) | POST to Overpass API (`overpass.deflock.org` → fallback `overpass-api.de`) for `man_made=surveillance + surveillance:type=ALPR` in a 5 km bbox; 24 h on-disk cache by 0.05° grid cell. Refetches when the user moves > 1.5 km from the last fetch center. |
| **WAZE** | Live `POLICE` reports within configurable proximity (default 500 m) and < 10 min old | `live-map/api/georss` polled every 60 s with a small bbox around the user. **Note:** Waze added reCAPTCHA gating to this endpoint in 2025/2026; mobile clients now receive HTTP 403. The bottom-sheet drill-down surfaces this as a per-source health indicator. |

Every observation is scored 0-100 by `ConfidenceEngine`. The on-screen tier is
the maximum live score across all sources:

```
GREEN      < 40    nothing credible
YELLOW   40 – 69   single weak indicator
ORANGE   70 – 84   high confidence
RED        85 +    certain
```

The user-facing circle uses the full 4-tier mapping. Cross-source corroboration
naturally pushes the global max upward (a BLE OUI hit *and* a DeFlock map
match in the same area produce a higher tier than either alone).

---

## Architecture

```
ui/MainScreen.kt              circle + START/STOP + tap-to-open bottom sheet
ui/SettingsScreen.kt          per-source toggles, distance sliders, theme
service/DetectionService.kt   foreground service — owns scanners + store
scan/BleScanner.kt            BLE callback scanner
scan/WifiScanner.kt           WifiManager poller + SCAN_RESULTS receiver
scan/DeflockClient.kt         CDN tile fetch + 24h cache
scan/DeflockScanner.kt        location-driven proximity check
scan/WazeClient.kt            live-map/api/georss bbox fetch
scan/WazeScanner.kt           60s poller + age/distance gate
fusion/ConfidenceEngine.kt    scoring (one place)
fusion/RssiTracker.kt         rise-peak-fall stationary-signal detector
fusion/DetectionStore.kt      in-memory dedup, 5-min retention
data/location/LocationProvider.kt  FusedLocationProviderClient wrapper
data/settings/Settings.kt     SharedPreferences-backed StateFlow settings
data/targets/                 BleOuis, WifiOuis, RavenUuids, Patterns, Manufacturers
```

No detection-history database. All state is in-memory and clears on stop, by
design.

---

## Build & install

Requires:
- **JDK 21** (Android Gradle Plugin 8.7.x rejects JDK 26)
- **Android Studio** with SDK Platform 34 + Build-Tools 34.x + Platform-Tools

```sh
# 1) Copy the example local.properties and point sdk.dir at your install
cp local.properties.example local.properties
# edit local.properties → sdk.dir=/Users/<you>/Library/Android/sdk

# 2) Make sure JAVA_HOME is JDK 21
export JAVA_HOME=/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home

# 3) Build & install on a connected device with USB debugging
./gradlew :app:installDebug
```

Or download the latest signed APK from
[Releases](https://github.com/KaraZajac/OVERWATCH/releases).

---

## Permissions

| Permission | Why |
|---|---|
| `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` (API 31+) | BLE scanning |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` (≤ API 30) | BLE scanning, legacy |
| `ACCESS_FINE_LOCATION` | Required for BLE pre-S, WiFi pre-T, and DeFlock proximity |
| `NEARBY_WIFI_DEVICES` (API 33+) | WiFi scan results without using location |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` | Trigger and read scan results |
| `INTERNET`, `ACCESS_NETWORK_STATE` | DeFlock CDN + Waze API |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_LOCATION` | Keep scanning with the screen off |
| `POST_NOTIFICATIONS` (API 33+) | Foreground-service notification |

Requested at runtime when you press START for the first time.

---

## Settings

Tap the gear icon in the top-right.

- **Detection sources**: toggle BLE / WiFi / DeFlock / Waze independently. Takes
  effect on next Start.
- **Proximity thresholds**:
  - DeFlock: 50 m – 1600 m (default 200 m)
  - Waze: 100 m – 5000 m (default 500 m)
- **Appearance**: System / Dark / Light (default Dark)

---

## Reference repos studied while building

These live under `REFERENCES/` (gitignored):

- **AxonCadabra** — BLE scanner skeleton (scan side only; advertise/fuzz code excluded)
- **flock-detection** — confidence-scoring algorithm (highest reusability), RSSI rise-peak-fall, OUIs + UUIDs + patterns
- **flock-you** — 31-OUI WiFi superset (promiscuous-mode tricks not portable to Android)
- **deflock** + **deflock-app** — CDN tile scheme, proximity-alert pattern
- **wazepolice** — live-map/api/georss recipe, Chrome header spoofing

---

## Status

Phases 1–5 (skeleton, BLE, WiFi, DeFlock, Waze, polish) complete as of v0.1.0.
Field-test-ready, not yet field-validated.

## License

Personal use. Reference repos retain their own licenses; do not redistribute
their code as part of this project.

## Disclaimer

Tool for situational awareness about deployed surveillance infrastructure in
public spaces. Local laws regarding electronic surveillance, RF monitoring, and
police-tracking apps vary — your responsibility to know what's legal where you
are.
