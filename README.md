# [DЯΣΛMMΛKΣЯ]
## &nbsp;&nbsp;&nbsp;. //0VΣЯW4TCH

A native Android (Kotlin) **passive surveillance-detection** app. Open it, hit
**START**, and a circle turns **green / yellow / orange / red** depending on
how confident the engine is that there's a Flock Safety ALPR, an Axon body
camera, or active police presence near you. With the screen locked, the
foreground notification updates with the current tier and the phone vibrates
on upward escalations — you don't have to be looking at the screen.

> **Passive defense only.** OVERWATCH only listens — it does not transmit,
> probe, jam, or interfere with any device or network. The Axon
> advertise/fuzz code from one of the reference projects is intentionally
> excluded.

Latest release: [v0.1.7](https://github.com/KaraZajac/OVERWATCH/releases) (debug-signed APK, sideload).

---

## What it detects

| Source | What it looks at | Where it comes from |
|---|---|---|
| **BLE** | Bluetooth-LE advertisements: vendor MAC OUIs (Axon, Flock Penguin / Raven, XUNTONG mfg id `0x09C8`, "TN" serial pattern), Raven service UUIDs, device-name patterns | Local radio scan (BLE callback API). Iterates every manufacturer-specific data entry to find XUNTONG, not just the first. |
| **WiFi** | BSSID OUI prefixes for Flock infrastructure (31-prefix superset), `Flock-XXXX` and other generic SSID patterns | `WifiManager.getScanResults()` polled every 35 s (just under the Android 11+ 4-scans/2-min throttle) |
| **DEFLOCK** | Crowdsourced ALPR locations within configurable proximity (default 200 m) | POST to Overpass API (`overpass.deflock.org` → fallback `overpass-api.de`) for `man_made=surveillance + surveillance:type=ALPR` in a 5 km bbox; 24 h on-disk cache by 0.05° grid cell. Refetches when the user moves > 1.5 km from the last fetch center. Backoffs after Overpass failures; treats `{"remark": "...timed out..."}` 200-responses as failure so timeouts don't poison the cache. |
| **CITIZEN** | Real-time public-safety incidents (police-relevant only — fire/medical-only events filtered out) within configurable proximity, < 30 min old | `citizen.com/api/incident/trending` (bbox) polled every 60 s, then per-incident detail via `/api/incident/{id}` with an in-memory cache so each incident is fetched once per session. First poll fires immediately on the first location fix. |

> **Why no Waze?** Waze added reCAPTCHA gating to its `live-map/api/georss` endpoint in 2025/2026. Mobile clients receive HTTP 403, and the only known workarounds (Selenium proxy on a home server, Waze for Cities partner program) aren't viable for a phone-deployed app. Citizen replaces it as the police-presence source.

Every observation is scored 0–100 by `ConfidenceEngine`. The on-screen tier is
the maximum live score across all sources:

```
GREEN      < 40    nothing credible
YELLOW   40 – 69   single weak indicator
ORANGE   70 – 84   high confidence
RED        85 +    certain
```

The user-facing circle uses the full 4-tier mapping. Cross-source corroboration
naturally pushes the global max upward (a BLE OUI hit *and* a DeFlock map
match in the same area produce a higher tier than either alone). When idle,
the circle shows muted gray with `IDLE` text so it's distinguishable at a
glance from "scanning, all clear."

---

## How alerts work

- **In-app**: the threat circle pulses while scanning; tap it to open the
  bottom-sheet drill-down with per-source rows. DEFLOCK and CITIZEN events
  carry coordinates — each row has a tap-to-open Maps icon (`geo:` intent).
- **Foreground notification**: rebuilt on every threat-tier change. Title
  becomes `OVERWATCH • RED` (or whatever tier); text shows the top
  detection's score + label. Notification priority bumps to HIGH on RED so
  the system can surface it as a heads-up.
- **Vibration**: on upward tier transitions only. Short pulse for YELLOW,
  double for ORANGE, escalating triple for RED. Toggle in Settings → Alerts.
- **Per-source health**: the drill-down sheet shows orange `Source unreachable`
  text on a row when its scanner couldn't reach its data source — silent
  empty results vs. real failures are distinguishable.

---

## Architecture

```
ui/MainScreen.kt                   circle + START/STOP + tap-to-open bottom sheet
ui/SettingsScreen.kt               source toggles, distance sliders, vibrate, theme
ui/theme/Theme.kt                  Material 3 dark/light + threat colors
service/DetectionService.kt        foreground service — owns scanners, notification, vibration
scan/BleScanner.kt                 BLE callback scanner
scan/WifiScanner.kt                WifiManager poller + SCAN_RESULTS receiver
scan/DeflockClient.kt              Overpass POST (deflock.org → overpass-api.de) + 24h cache
scan/DeflockScanner.kt             location-driven proximity check + failure backoff
scan/CitizenClient.kt              GET /api/incident/trending + /api/incident/{id}
scan/CitizenScanner.kt             60 s poller, fire/medical filter, per-id cache
fusion/ConfidenceEngine.kt         scoring (one place — BLE / WiFi / DeFlock / Citizen)
fusion/RssiTracker.kt              rise-peak-fall stationary-signal detector
fusion/DetectionStore.kt           in-memory dedup, 5-min retention, max-tier flow
fusion/SourceHealth.kt             per-source OK/FAILED registry for the drill-down
fusion/ThreatLevel.kt              4-tier enum + DetectionSource enum
data/location/LocationProvider.kt  FusedLocationProviderClient wrapper
data/settings/Settings.kt          SharedPreferences-backed StateFlow settings
data/targets/                      BleOuis, WifiOuis, RavenUuids, Patterns, Manufacturers
```

No detection-history database. All state is in-memory and clears on stop, by
design. Service uses `START_NOT_STICKY` — system kill doesn't auto-restart
into a stuck state.

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

Or download the latest debug-signed APK from
[Releases](https://github.com/KaraZajac/OVERWATCH/releases).

---

## Permissions

| Permission | Why |
|---|---|
| `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` (API 31+) | BLE scanning |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` (≤ API 30) | BLE scanning, legacy |
| `ACCESS_FINE_LOCATION` | Required for BLE pre-S, WiFi pre-T, and DeFlock/Citizen proximity |
| `NEARBY_WIFI_DEVICES` (API 33+) | WiFi scan results without using location |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` | Trigger and read scan results |
| `INTERNET`, `ACCESS_NETWORK_STATE` | DeFlock Overpass + Citizen API |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_LOCATION` | Keep scanning with the screen off |
| `POST_NOTIFICATIONS` (API 33+) | Foreground-service notification |
| `VIBRATE` | Haptic alert on threat-tier escalation |

Requested at runtime when you press START for the first time. If you
permanently deny a required permission ("don't ask again"), the START button
swaps to **Open app settings** which fires the per-app system-settings page
so you can grant manually.

---

## Settings

Tap the gear icon in the top-right.

- **Detection sources**: toggle BLE / WiFi / DeFlock / Citizen independently.
  Changes take effect on the next Start. While scanning, a **Restart scan to
  apply** button appears that does `stop()` + `start()` in one tap.
- **Proximity thresholds** (sliders commit on release, not per-pixel):
  - DeFlock: 50 m – 1600 m (default 200 m)
  - Citizen: 100 m – 5000 m (default 500 m)
- **Alerts**:
  - Vibrate on threat escalation (default on)
- **Appearance**: System / Dark / Light (default Dark)

---

## Reference repos studied while building

These live under `REFERENCES/` (gitignored):

- **AxonCadabra** — BLE scanner skeleton (scan side only; advertise/fuzz code excluded)
- **flock-detection** — confidence-scoring algorithm (highest reusability), RSSI rise-peak-fall, OUIs + UUIDs + patterns
- **flock-you** — 31-OUI WiFi superset (promiscuous-mode tricks not portable to Android)
- **deflock** + **deflock-app** — Overpass query format + proximity-alert pattern (the Flutter app uses Overpass directly, not the CDN tiles, which the OVERWATCH client mirrors)
- **wazepolice** — live-map/api/georss recipe; informed v0.1.0–v0.1.5 Waze integration that has since been removed (endpoint is reCAPTCHA-gated)

---

## Status

Phases 1–5 (skeleton, BLE, WiFi, DeFlock, Citizen, polish) complete and
field-tested. Current release **v0.1.7** addresses two full audit passes
(see release notes for v0.1.2, v0.1.3, v0.1.6). Notable changes since v0.1.0:

- v0.1.2 — Android 14+ foreground service type fix (location was being silently revoked); NaN-coordinate filter on map data.
- v0.1.3 — DeFlock CDN replaced by direct Overpass calls (Cloudflare-blocked).
- v0.1.4 — Citizen.com added as 5th source, per-source health registry.
- v0.1.5 — Waze removed (reCAPTCHA-gated; no clean mobile workaround).
- v0.1.6 — Dynamic notification with tier + label, haptic alerts on escalation, Open-in-Maps for geo events, idle visual differentiated from "scanning, all clear", permanent-deny recovery via Open Settings.
- v0.1.7 — System back from Settings returns to MAIN instead of exiting.

## License

Personal use. Reference repos retain their own licenses; do not redistribute
their code as part of this project.

## Disclaimer

Tool for situational awareness about deployed surveillance infrastructure in
public spaces. Local laws regarding electronic surveillance, RF monitoring, and
police-tracking apps vary — your responsibility to know what's legal where you
are.
