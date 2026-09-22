# OVERWATCH — sources, identifiers and endpoints

Every external data source, every signature the app matches on, and every
scoring table, in one place. Written to be the reference behind a talk, so it
records **what was tried and failed** alongside what works — the dead ends are
usually the more interesting half.

Dates are when a claim was last verified against the live service. Anything
network-facing rots; re-verify before quoting it.

---

## 1. Detection sources at a glance

| Source | What it sees | Transport | Cost | Verified |
|---|---|---|---|---|
| **BLE** | Bluetooth-LE advertisements from surveillance hardware | Local radio | free | 2026-09 |
| **WIFI** | BSSIDs + SSIDs of surveillance infrastructure | Local radio | free | 2026-09 |
| **DEFLOCK** | Mapped fixed surveillance: ALPR, speed cameras, CCTV | Overpass / OSM | free | 2026-09-17 |
| **WAZE** | Live crowd-sourced police reports | OpenWeb Ninja | ~$1–3/mo | 2026-09-16 |
| **AIRCRAFT** | Police / surveillance aircraft overhead | ADS-B community feeds | free | 2026-09-17 |
| **COMMERCIAL** | Consumer cameras, voice assistants, smart glasses | Rides BLE + WiFi | free | 2026-09 |

Two radio sources (passive, local) and three network sources (position-driven).
The app **never transmits** to any detected device — it only listens, and only
queries third-party APIs about coordinates.

---

## 2. Network endpoints

### 2.1 Overpass / OpenStreetMap — the DEFLOCK source

```
POST https://overpass.deflock.org/api/interpreter      (preferred)
POST https://overpass-api.de/api/interpreter           (fallback)

[out:json][timeout:25];
(node["man_made"="surveillance"](S,W,N,E);
 node["highway"="speed_camera"](S,W,N,E););out body;
```

**DeFlock and OSM are the same dataset.** DeFlock is a crowdsourcing project
whose contributors map ALPR cameras *into OpenStreetMap* as
`man_made=surveillance` + `surveillance:type=ALPR` nodes. There is no separate
DeFlock database to query — the only DeFlock-specific piece is their Overpass
mirror, which the app prefers because it is less rate-limited for this use.
The OSM ALPR registry passed **336,000 nodes** worldwide in early 2026, covering
Flock plus Motorola Vigilant, Axon Fleet and Genetec.

`man_made=surveillance` is the superset that already contains the ALPR nodes,
so the query does not ask for ALPR separately; the parser classifies by tag.

Measured node counts in a 5 km bbox (2026-09-17):

| Area | ALPR | speed_camera | all surveillance |
|---|---|---|---|
| Northern Virginia | 166 | 31 | 189 |
| Manhattan | 292 | 12 | 515 |

Widening past ALPR roughly doubles the worst case rather than exploding it,
which is why generic cameras are worth carrying.

- Response cached on disk 24 h, keyed by 0.05° grid cell (`deflock2_<lat>_<lon>`).
- Refetch when the user moves > 1.5 km from the last fetch centre.
- A `200 OK` body containing `{"remark": "...timed out..."}` is treated as a
  failure, so an Overpass timeout cannot poison the cache.
- **Dead:** `cdn.deflock.me/regions/*.json` — behind Cloudflare bot mitigation,
  unusable from a mobile HTTP client.

### 2.2 OpenWeb Ninja — the WAZE source

```
GET https://api.openwebninja.com/waze/alerts-and-jams
    ?bottom_left=<lat>,<lon>&top_right=<lat>,<lon>
    &max_alerts=200&max_jams=0&alert_types=POLICE
Header: X-API-Key: <the user's own key>
```

**Bring your own key** — sign up at openwebninja.com, subscribe to the Waze API,
paste the key into Settings. Stored encrypted (Android Keystore AES/GCM via
`SecureStore`); nothing ships in the APK. Pay-as-you-go ≈ **$0.005/request**,
≈ 15 requests/active hour at the ~4-minute poll, so **$1–3/month**. The free
tier's 100 requests/month verifies the integration but will not run it.

Verified behaviour (2026-09-16):

- `alert_types=POLICE` **is** honoured server-side now. It was echoed-but-ignored
  when the integration was first written, so the client still re-filters by type
  — a silent revert must not let other alert types through.
- `max_jams=0` suppresses the jam array. With both parameters a typical response
  drops from **~17.9 KB to ~1.5 KB**.
- Auth failure is `HTTP 401 {"message":"Unauthorized"}` for both a missing and an
  invalid key.
- `URLEncoder` renders the bbox comma as `%2C`; the API parses that correctly.
- Police alerts very often carry `alert_confidence: 0` and `alert_reliability: 0`,
  so the distance floor matters more than the crowd-trust bonuses.

**Why not scrape Waze directly:** `waze.com/live-map/api/georss` is gated by
reCAPTCHA Enterprise *reputation* scoring. Tested 2026-07 from a residential IP —
plain curl, headless Chromium, **headful** Chromium on a real display, and
undetected-chromedriver all returned **HTTP 403**, including Waze's own page
requests. It scores browser reputation, not automation flags, so no scraper
survives. The Waze for Cities partner feed excludes POLICE and is agency-only.

### 2.3 ADS-B community networks — the AIRCRAFT source

```
GET https://opendata.adsb.fi/api/v2/lat/<lat>/lon/<lon>/dist/30   (preferred)
GET https://api.adsb.lol/v2/lat/<lat>/lon/<lon>/dist/30           (fallback)
```

**No API key, no account.** Both are volunteer data-in/data-out networks running
the same readsb-derived schema, so one parser covers both.

Community networks matter specifically here: the commercial trackers
(FlightAware, Flightradar24) filter military and sensitive law-enforcement
flights at government request — exactly the traffic this source exists to see.
ADSBexchange, adsb.fi, adsb.lol and airplanes.live do not filter.

- Envelope is `{"ac":[...]}` on radius queries and `{"aircraft":[...]}` on wider
  ones — **both shapes occur**, and reading only one silently returns zero
  aircraft. The parser accepts either.
- Fields used: `hex` (ICAO 24-bit address), `flight`, `lat`, `lon`, `alt_baro`,
  `gs`, `track`, `r` (registration), `t` (type).
- `alt_baro` is the **string** `"ground"` for parked aircraft, so a naive
  integer read reports that as 0 ft.
- Rapid sequential queries across many metros get refused; the app polls once
  per minute from one point, well inside courtesy limits.

### 2.4 Aircraft identity — ADSBexchange `basic-ac-db`

```
https://downloads.adsbexchange.com/downloads/basic-ac-db.json.gz
```

14.5 MB gzipped, **618,270 aircraft**, newline-delimited JSON, derived from the
FAA registry and **already keyed by ICAO hex** — which is what ADS-B broadcasts,
so no N-number→hex conversion is needed. Fields: `icao`, `reg`, `ownop`
(owner/operator), `icaotype`, `model`, `faa_pia`, `faa_ladd`, `mil`.

Filtering `ownop` for law-enforcement patterns yields **1,971 US aircraft**
(213 LADD-flagged), bundled as `app/src/main/res/raw/le_aircraft.csv` (~83 KB).
Regenerate with `scripts/gen-le-aircraft.py`. It runs at development time and
commits its output; the app never downloads it.

Owner-match patterns: `SHERIFF`, `POLICE`, `STATE PATROL`, `HIGHWAY PATROL`,
`STATE TROOPER`, `PUBLIC SAFETY`, `US MARSHAL`, `MARSHALS SERVICE`, `CONSTABLE`,
`DEPT/DEPARTMENT OF CORRECTION`, `BORDER PATROL`, `CUSTOMS AND BORDER`,
`HOMELAND SECURITY`, `DRUG ENFORCEMENT`, `FEDERAL BUREAU OF INVESTIGATION`.

Excluded to avoid false positives: `MARSHALL SPACE` (NASA), plus `FIRE`,
`AMBULANCE`, `MEDICAL`, `EMS`, `AIR METHODS`, `LIFE FLIGHT`, `MEDEVAC`,
`HOSPITAL` — air-ambulance operators share a lot of vocabulary with police
aviation, and a medevac helicopter is not what this app exists to warn about.

Top operators by airframe count: DHS 98, US CBP 54, Arizona DPS 35, CHP 24,
Texas DPS 23, US Border Patrol 20.

**Rejected alternatives:**

- **FAA releasable registry** (`registry.faa.gov/database/...`) — HTTP **403**,
  Akamai bot mitigation. Also keyed by N-number, not hex.
- **OpenSky `aircraftDatabase.csv`** — works, has an `owner` column, but 94 MB
  for the same information and *not* hex-keyed as cleanly. 6× the download.
- **plane-alert-db** (`sdr-enthusiasts`) — the obvious community choice, and the
  wrong one for US coverage. `plane-alert-pol.csv` holds 996 police aircraft but
  only **255 US-registered**; matching all 2,760 of its police+government hexes
  against **394 live aircraft** over a 250 nm sweep of Washington DC produced
  **zero hits**, while 15 helicopters were aloft. Good for international and
  head-of-state airframes, thin for US local police.
- **EFF Atlas of Surveillance** — no aircraft identities. Its only aircraft
  dataset is an FAA **drone** registration lookup, last updated **April 2022**,
  and drones do not broadcast ADS-B. Still useful as per-agency context
  ("what does this department deploy"), not as a live source.

### 2.5 Retired: Citizen

```
GET https://citizen.com/api/incident/trending   ->  HTTP 410 Gone
    {"error":"This endpoint has been removed."}
```

Citizen ended the police-dispatch data partnership behind its public feed in
**June 2026**. Timeline: the endpoint first degraded to a silent stub returning
a bare JSON empty string `""` (verified 2026-08-28), then to a formal **410 Gone**
(verified 2026-09-16). `/api/incident/{id}` returns `{}` for every id, real or
invented, and `data.sp0n.io/v1/incidents/trending` — the host the current web app
uses — answers 200 with a **zero-byte body even with no query parameters at all**,
which rules out a parameter-shape mismatch. Every sibling path (`nearby`,
`recent`, `latest`, `map`, `list`, `active`) returns `{}`.

That is a decommissioned surface, not a changed contract. Structured incident
data is now Citizen's paid Enterprise API only. The source was removed entirely
in v0.5.7 rather than left as a permanently-failing row.

*(Historical footnote for the talk: passing that empty string to `JSONObject`
throws `Value of type java.lang.String cannot be converted to JSONObject` —
the doubled space in the message is where the empty value interpolates. That
exact string, leaked into the app's UI, is how the shutdown was first noticed.)*

---

## 3. Radio identifiers

### 3.1 BLE OUIs — surveillance

`data/targets/BleOuis.kt`. Matched on the lowercased `xx:xx:xx` MAC prefix.

**Axon** (flagged separately, scores 80): `00:25:df`

**Flock + supply chain** (24, from flock-detection):
```
58:8e:81  cc:cc:cc  ec:1b:bd  90:35:ea  f0:82:c0  1c:34:f1  38:5b:44  94:34:69
b4:e3:f9  3c:91:80  d8:f3:bc  80:30:49  14:5a:fc  9c:2f:9d  94:08:53  e4:aa:ea
48:e7:29  c8:c9:a3  74:4c:a1  70:c9:4e  04:0d:84  08:3a:88  a4:cf:12  d8:a0:d8
```

### 3.2 WiFi OUIs — Flock infrastructure

`data/targets/WifiOuis.kt`. 31-prefix superset (flock-you research by
NitekryDPaul + DeFlockJoplin), overlapping flock-detection's 24:
```
70:c9:4e  3c:91:80  d8:f3:bc  80:30:49  b8:35:32  14:5a:fc  74:4c:a1  08:3a:88
9c:2f:9d  c0:35:32  94:08:53  e4:aa:ea  f4:6a:dd  f8:a2:d6  24:b2:b9  00:f4:8d
d0:39:57  e8:d0:fc  e0:4f:43  b8:1e:a4  70:08:94  58:8e:81  ec:1b:bd  3c:71:bf
58:00:e3  90:35:ea  5c:93:a2  64:6e:69  48:27:ea  a4:cf:12  82:6b:f2
```

Android exposes only the BSSID — flock-you's promiscuous-mode `addr1` and
wildcard-probe tricks are not portable to a userspace Android app.

### 3.3 Vendor OUIs — enterprise / municipal surveillance

`data/targets/VendorOuis.kt`. All 18 verified against the IEEE registry
(Wireshark `manuf` snapshot, 2026-08-28), so a hit names the manufacturer
directly. Merged into **both** the BLE and WiFi match sets.

| OUI | Vendor | Radio reality |
|---|---|---|
| `d4:11:d6` | ShotSpotter / SoundThinking | Acoustic gunshot sensors; patents cite WiFi/BT/Zigbee/LPWAN backhaul. **Police-exclusive.** |
| `00:1d:96` | WatchGuard Video (Motorola) | V300 body cams (BT 5.0 + WiFi), 4RE in-car systems run cruiser-local APs. **Police-exclusive.** |
| `e0:a7:00` | Verkada | AD33/AD34 readers advertise BLE for app unlock; SV sensors carry BLE. Cameras are PoE. |
| `70:1a:d5` | Avigilon Alta (Openpath) | Readers use BLE + WiFi (+ UWB) for mobile credentials — constant advertisers. |
| `00:40:8c` `ac:cc:8e` `b8:a4:4f` `e8:27:25` | Axis Communications | W110/W120 **body cams** have BLE 5.1 + dual-band WiFi; fixed cameras are wired. An Axis hit over the air is disproportionately likely to be a body cam. |
| `00:40:7f` `00:1b:d8` | FLIR Systems | Handhelds carry Bluetooth (METERLiNK) + WiFi; fixed thermal is wired. |
| `44:b4:23` `8c:1d:55` `e4:30:22` | Hanwha Vision | Predominantly wired IP video. |
| `00:10:be` `00:12:81` | March Networks | Wired; transit recorders may carry WiFi. |
| `00:13:e2` | GeoVision | Predominantly wired. |
| `00:03:c5` | Mobotix | Predominantly wired. |
| `00:1c:27` | Sunell Electronics | Predominantly wired. |

**Police-exclusive subset** (`ShotSpotter`, `WatchGuard`) scores 75 on sight —
same rationale as the Axon OUI: these prefixes appear on nothing consumer.

### 3.4 BLE service UUIDs — Flock Raven (gunshot detection)

`data/targets/RavenUuids.kt`. 16-bit UUIDs expanded to the Bluetooth base
`0000xxxx-0000-1000-8000-00805f9b34fb`:

| UUID | Meaning | Firmware |
|---|---|---|
| `180a` | Device Information | all |
| `3100` | GPS Location | 1.2.x+ |
| `3200` | Power Management | 1.2.x+ |
| `3300` | Network Status | 1.2.x+ |
| `3400` | Upload Statistics | 1.3.x |
| `3500` | Error Diagnostics | 1.3.x |
| `1809` | Health Thermometer | 1.1.x |
| `1819` | Location & Navigation | 1.1.x |

Three or more matching UUIDs scores 90; one or two scores 70.

### 3.5 Manufacturer-specific data

`data/targets/Manufacturers.kt`. Company ID **`0x09C8` (XUNTONG)** scores 60; a
`"TN"`-prefixed serial in the payload adds 20.

The BLE scanner iterates **every** manufacturer-data entry in an advertisement,
not just the first — devices advertise multiple and the target is often not
first in the list.

### 3.6 Name and SSID patterns

`data/targets/Patterns.kt`.

- **BLE local names** (case-sensitive substring): `FS Ext Battery`, `Penguin`,
  `Flock`, `Pigvision`, `FlockCam`, `FS-`
- **Penguin numeric**: post-March-2025 firmware advertises a bare 8–12 digit
  decimal ID and nothing else — matched as its own weak signal (15).
- **Generic SSID** (case-insensitive): `flock`, `FS_`, `Penguin`, `Pigvision`,
  `FlockOS`, `flocksafety`, `FS Ext Battery`
- **Flock SSID format**: `^Flock-[0-9A-Fa-f]{4}$` — the specific form, scored
  higher (65) than a generic substring hit (50).

### 3.7 COMMERCIAL / consumer gear

`data/targets/MicTargets.kt`. Families: `ECHO`, `RING`, `GOOGLE`, `SONOS`,
`HIDDEN_CAM`, `GLASSES`.

Bluetooth SIG company IDs — every one re-verified against the SIG
assigned-numbers registry (4,035 entries) on 2026-09-21:

| ID | Vendor | Family |
|---|---|---|
| `0x00E0` | Google | GOOGLE |
| `0x0171` | Amazon.com Services | ECHO |
| `0x05A7` | **Sonos Inc** | SONOS |
| `0x01AB` | Meta Platforms, Inc. | GLASSES |
| `0x058E` | Meta Platforms Technologies (Reality Labs / Quest) | GLASSES |
| `0x0D53` | Luxottica Group — Ray-Ban / Oakley Meta frames | GLASSES |
| `0x03C2` | Snapchat Inc — Spectacles | GLASSES |
| `0x060C` | Vuzix | GLASSES |

`0x05A7` was previously labelled "Yingxin / cheap-spy-cam" and mapped to
HIDDEN_CAM; the registry says Sonos. That would have tagged a Sonos speaker as
a hidden camera. Sonos does belong in scope (the Era/One lines carry always-on
microphones), but under an honest label.

**Deliberately excluded:**

- `0x004C` (Apple) — every iPhone and AirPod in range would match, drowning
  the signal.
- `0x0BC6` (TCL) — RayNeo glasses ride under it, but so does everything else
  TCL makes.
- `0x05D6` (Zhuhai Jieli) — Nearby Glasses lists it for the Rogbird VisionPro
  and Rollme VistaView, and its own notes say why it is a problem: it is the id
  of the *Jieli JL70xx Bluetooth chipset*, present in an enormous share of
  cheap TWS earbuds, speakers and toys. Matching it would label every one of
  those "Smart glasses". If distinctive name strings for those products
  surface, they belong in the name hints instead.

Advertised service UUIDs (checked in **both** the service list and the
service-data keys — HeyCyan frames have been seen carrying it either way):

| UUID | Meaning | Family |
|---|---|---|
| `0000FE03-…` (16-bit `FE03`) | Alexa Voice Service (Amazon Lab126) | ECHO |
| `7905FFF0-B5CE-4E99-A40F-4B1E122D00D0` | HeyCyan smart-glasses SDK primary service — Nilox Smart AI Glasses (sold by ALDI/Hofer) and other HeyCyan-based frames. Fixed on the software side, so it survives the randomised MACs and unstable names that defeat most glasses detection. | GLASSES |

Both service UUIDs are also added to the screen-off `ScanFilter` set (§5.1),
ahead of the company ids, because they are exact signatures and are what
should survive if the 16-slot list is trimmed.

Name tokens: the case-sensitive hints (`Spectacles`, `Ray-Ban`, `RayNeo`,
`Vuzix`, `XREAL`, `Rokid`, plus the Echo/Ring/Nest set) and, matched
case-insensitively against the lowercased name, `rayban`, `ray-ban`,
`ray ban`, `heycyan`.

**Credit:** the GLASSES identifier set is drawn from
[Nearby Glasses](https://github.com/yjeanrenaud/yj_nearbyglasses) by
[Yves Jeanrenaud](https://yves.app) (AGPL-3.0, ~2.3k stars), whose
`smart_glasses_identifiers.csv` and README document the company ids, the
HeyCyan UUID (traced via the [HeyCyan SDK](https://github.com/ebowwa/HeyCyanSmartGlassesSDK))
and the name tokens. OVERWATCH takes the published *identifiers* — facts about
the radio protocol — not the project's code. Two small upstream notes worth
passing back: the CSV's `Snap` row carries `0x0D53`, which is Luxottica (the
project's own README and code have Snap correctly as `0x03C2`), and its
scanner reads only the first manufacturer-data entry (`keyAt(0)`), whereas
some devices advertise several.

Hidden-camera OUIs: `fc:b4:67` (Yingxin/SmartLife), `00:e0:4c` (Realtek, in many
cheap cams), `dc:4f:22` (Tuya-affiliated modules), `a4:c1:38` (Telink, common in
cheap BLE mics), `8c:ce:4e` (Shenzhen iComm, frequent in spy-cam BOMs).

Scores are **capped at 84** so a cluster of doorbells — or a passing pair of
Ray-Bans — can never read as ALPR-grade certainty. RED is reserved for
ALPR/Axon-grade evidence.

---

## 4. Scoring

Score 0–100 per observation; the on-screen tier is the **maximum live score**
across all sources. Events expire after 5 minutes.

```
GREEN      < 40    nothing credible
YELLOW   40 – 69   single weak indicator
ORANGE   70 – 84   high confidence
RED        85 +    certain
```

### 4.1 Distance falloff

Sources that carry real coordinates are scored on a **continuous falloff over
actual distance**, linearly interpolated between anchors. The anchors are
**absolute metres and deliberately independent of the user's detection-radius
setting**: a camera 200 m away is equally close whether the slider reads 300 m
or 5 km, so moving a setting must never move the threat level. The radius
decides what gets *reported*, never how alarming it is.

| Distance | ALPR | Speed camera | Generic camera | Waze police | Aircraft |
|---|---|---|---|---|---|
| 0 m | 95 | 78 | 55 | 80 | 88 |
| 50 m | 85 | 70 | 46 | 78 | — |
| 100 m | 72 | 63 | 42 | 70 | — |
| 200 m | 55 | 50 | 34 | 62 | — |
| 350 m | 42 | 38 | 28 | 52 | — |
| 500 m | 35 | 32 | 26 | 47 | — |
| 1000 m | 26 | 27 | 20 | 34 | 80 |
| 3000 m | 18 | 16 | 14 | 23 | 68 |
| 15000 m | — | — | — | — | 30 |

**Every curve crosses below 40 (YELLOW) at roughly the distance the thing stops
being able to act on you**, and **scoring is fully decoupled from the range
control**: scanners evaluate everything inside their own fixed radii (DeFlock
1200 m, Waze 2000 m, aircraft 15 km) and the tier is computed from that, so the
`show within` slider cannot move it in either direction. Anything at YELLOW or
above is displayed whatever the range says, since a view setting that can hide a
live alert is a trap rather than a feature.

Getting there took two passes, and the second one is the instructive half. The on-screen tier is `max(score)` over everything reported,
and the slider decides what is reported, so loose curves let the setting leak
into the alarm: measured against a real 195-node cache, standing still and
dragging from 300 m to 500 m flipped the app GREEN → YELLOW with nothing
physical changing. After recalibration, sweeping 200 m → 4900 m leaves the tier
untouched (reported items 0 → 133), while standing 29 m from a Flock camera
still reads 89 RED.

Aircraft are exempt from the range slider entirely — `AircraftScanner` uses its
own 15 km reporting range (8 km for unregistered contacts), because an aircraft
orbiting overhead is still watching you from a distance at which a camera
cannot see you at all.

A surveyed ALPR peaks highest and decays fastest — its position is exact and it
cannot see you from a kilometre away. A Waze report is a coarse pin on a car
that may be moving toward you. An aircraft decays slowest of all, because one
orbiting 5 km away is still watching you.

### 4.2 Modifiers

- **Waze age decay** — up to −12 points across the 45-minute freshness window.
  Police move; a surveyed camera does not.
- **Waze crowd trust** — `reliability ≥ 7` +5, `confidence ≥ 4` +5.
- **Aircraft altitude** — ≤3,000 ft +6; ≤8,000 ft +0; ≤15,000 ft −12;
  above −30. A known police airframe at 30,000 ft is an airliner's neighbour,
  not an observer.
- **Aircraft loitering** — +10. Surveillance aircraft orbit; airliners and
  medevac flights heading somewhere do not.
- **Aircraft LADD** — +4. The operator asked public trackers to hide it.
- **Unidentified aircraft cap** — 69. Behaviour-only evidence never reaches the
  "certain" band; circling could still be news or survey work.
- **Multi-method bonus** — +20 when two independent BLE/WiFi methods agree.
- **Strong RSSI** (> −50 dBm) +10; **stationary** (rise-peak-fall) +15.

### 4.3 Loiter detection

An aircraft is "loitering" when, over the last 15 minutes of tracked history:
at least **3 samples** spanning at least **4 minutes**, every one within
**5 km** of their own centroid, while below **12,000 ft** and under **200 kt**.

A registry hit is reported wherever it is, within 15 km. An *unregistered*
aircraft is reported only when it is both loitering **and** within 8 km, so
ordinary traffic passing overhead never raises an alert.

---

## 5. Platform constraints that shape the radio sources

The two local-radio sources are limited by OS policy far more than by the
hardware, and the limits are not obvious from the APIs.

### 5.1 Unfiltered BLE scans are suspended when the screen goes off

**Since Android 8.1, the Bluetooth stack stops delivering results for a scan
started with no `ScanFilter` once the screen turns off, and a foreground
service does not exempt it** — it is a stack rule, not a process-lifetime one.
For an app whose entire promise is "keep watching while it's in your pocket",
that is the worst possible silent failure: `startScan` returns success, the
service stays alive, the notification keeps updating, and no BLE result ever
arrives.

The catch is that **a `ScanFilter` cannot express an OUI prefix.** It can match
an exact address, an exact name, a service UUID, or manufacturer data — and
OUI-prefix matching is OVERWATCH's primary BLE method (Axon `00:25:df`, the 24
Flock prefixes, the 18 vendor prefixes). There is no filter that means "any MAC
starting with these three octets", and no filter that means "any device".

So the scanner switches strategy on `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF`:

| Screen | Scan | Covers | Loses |
|---|---|---|---|
| on | unfiltered | everything — OUI, name, UUID, manufacturer | — |
| off | filtered (≤16) | Raven service UUIDs, XUNTONG manufacturer id, mic-target company ids | OUI prefixes, name substrings |

Filter slots are a hardware resource and chipsets differ; a common allocation is
16. Overflow is meant to fall back to software filtering, but since a scan that
silently returns nothing is this app's worst failure mode, the list is capped at
16 with surveillance signatures ordered ahead of consumer ones. The BLE row in
the drill-down states the reduced mode while the screen is off rather than
hiding it, and Flock ALPR coverage is unaffected in that window because the map
source does not depend on the radio.

### 5.2 Other limits worth knowing

- **BLE start-rate limit** — 5 `startScan` calls per 30 s per app. Exceed it and
  the scan *appears* to start but delivers nothing. Screen transitions are rare
  enough to stay clear of it.
- **WiFi scan throttling** — since Android 9, foreground apps get 4 scans per
  2 minutes (background: 1 per 30 min). OVERWATCH polls every 35 s ≈ 3.4 per
  2 min, deliberately just under.
- **`WifiManager.startScan()` is deprecated** and Google has stated the ability
  for apps to trigger scans will be removed in a future release. The scanner
  already treats it as best-effort: it registers for
  `SCAN_RESULTS_AVAILABLE_ACTION` and reads whatever the system last scanned, so
  when the trigger stops working the source degrades to system-paced results
  rather than failing.
- **Promiscuous-mode tricks are not portable.** flock-you's `addr1` and
  wildcard-probe techniques need monitor mode; a userspace Android app sees only
  what `WifiManager` surfaces, which is BSSID and SSID.
- **Android 14+ foreground-service types** are mandatory:
  `connectedDevice` for the radio scanners and `location` for the map/feed
  sources. Both are declared and both are passed at `startForeground` time.

---

## 6. Testing notes

Emulator GPS is the main obstacle to testing the position-driven sources, and it
has a trap worth recording:

`adb emu geo fix` appears to do nothing — it returns `OK` and the position never
changes. The real cause is that **nothing has the GPS provider started**. With a
`PRIORITY_BALANCED_POWER_ACCURACY` request, Play services prefers the network
provider and parks the fused provider at `ProviderRequest[OFF]`, so the injected
NMEA has nowhere to land. Once the app requests `PRIORITY_HIGH_ACCURACY` and a
scan is running, `geo fix` lands immediately.

Two working injection methods, both needing an active GPS consumer:

```sh
# 1. emulator console — note LON comes first
adb -s emulator-5558 emu geo fix <lon> <lat> <alt> <sats>

# 2. Android test provider — note LAT comes first
adb -s <serial> shell appops set --uid 0 android:mock_location allow
adb -s <serial> shell cmd location providers add-test-provider gps
adb -s <serial> shell cmd location providers set-test-provider-enabled gps true
adb -s <serial> shell cmd location providers set-test-provider-location gps --location <lat>,<lon>
```

Wrapped as `scripts/emu-gps.sh <lat> <lon> [serial] [seconds]`. Always confirm
with `adb shell dumpsys location | grep -m1 "last location"`, which prints the
coordinates **and** an `et=` age — a large age means the fix did not land. A
reboot resets the `appops` grant, and the argument order differs between the two
methods, which is easy to transpose.

---

## 7. Provenance

Reference projects studied while building (kept under a gitignored `REFERENCES/`):

- **AxonCadabra** — BLE scanner skeleton. Scan side only; its advertise/fuzz
  code is deliberately excluded, since OVERWATCH never transmits.
- **flock-detection** — the confidence-scoring algorithm, RSSI rise-peak-fall
  stationary detection, OUIs, UUIDs and name patterns.
- **flock-you** — the 31-OUI WiFi superset.
- **deflock / deflock-app** — the Overpass query shape and proximity-alert
  pattern.
- **wazepolice** — the original `live-map/api/georss` recipe, now dead.
