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
| **WAZE** | Live crowd-sourced police reports | OpenWeb Ninja *or* Waze's own protocol | ~7.5¢ per scanning hour *or* free | 2026-09-21 |
| **AIRCRAFT** | Police / surveillance aircraft overhead | ADS-B community feeds | free | 2026-09-17 |
| **COMMERCIAL** | Consumer cameras, voice assistants, smart glasses | Rides BLE + WiFi | free | 2026-09 |

Two radio sources (local) and three network sources (position-driven). The app
**never connects to, pairs with, or jams** anything it detects. It is not
radio-silent, though: Android's scans transmit — Wi-Fi scanning sends probe
requests and Bluetooth scanning sends scan requests to advertisers — and the
network sources have to say where to look. Since v0.5.18 none of them is given
the phone's position: the ADS-B feeds, OpenWeb Ninja and Waze direct get the
centre of the 1 km grid square it is in, Overpass its ~5.5 km cache cell, and the
map's tiles come from OpenStreetMap. §5.4 lists every destination.

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

- **What Overpass learns.** The box is ±0.05° around the centre of the phone's
  0.05° grid cell, never around the fix, so Overpass learns only which ~5.5 km
  cell you are in. Until v0.5.18 it was centred exactly on the fix, so its centre
  was your position. Centring on the cell also closed a coverage gap: a box
  centred on whichever fix first filled a cell could leave part of a later fix's
  1.2 km evaluation circle outside it (12.5% of positions in a 200,000-fix
  simulation). A cell-centred box reaches at least 0.025° past every edge of the
  cell (~2.8 km north–south, ~2.2 km east–west at 38°), which holds the circle of
  every fix *inside that cell* below about 64° latitude. Keeping each fix inside
  the cell it is checked against is the scanner's job (next bullets).
- Response cached on disk per 0.05° grid cell (`deflock3_<i>_<j>`, the cell's
  grid indices `floor(coordinate / 0.05)`), for the current scan only. The directory doubles as a list of every ~5.5 km area
  scanned, so `LocationTraces` deletes it every time scanning stops and again at
  the next start, and a request still in flight at the stop does not write.
  Before v0.5.17 the files were never deleted; a 24 h TTL only made old ones
  ignored.
- **When it fetches.** On every fix the scanner checks whether the loaded box
  still holds that fix's 1.2 km circle (`DeflockClient.Cell.holds`, with
  metres-per-degree rounded down so it errs toward fetching). When it doesn't, it
  asks for the fix's own cell, whose box does; a cell already fetched this scan
  comes from the cache without a request. A failed fetch is retried after 60 s,
  moving or not. On simulated drives through the shipping classes (45,000 fixes
  each, 100 m apart, checked against the true geodesic circle), no fix at 38°N or
  60°N was checked against a box missing part of its circle; at 64°N, 2 were.
  Until v0.5.19 the scanner refetched only after 1.5 km of movement, so for a
  while after entering a new cell a fix was checked against the old box: 0.64% of
  fixes at 38°N, 11.7% at 60°N and 18% at 64°N had part of their circle outside
  it. A retry also needed 1.5 km of movement, so one failure while parked left the
  map empty until you drove off.
- **A fetch in flight is never cancelled.** Until v0.5.19 the scanner used
  `collectLatest`, so a fix arriving mid-fetch cancelled it and the reply was
  thrown away. At start the phone's cached position and its first live fix often
  land within a second, so the first fetch could be lost, and with the 1.5 km rule
  nothing asked again while the phone stayed put: with a 2.5 s reply and the live
  fix 0.8 s after the cached one, the v0.5.18 scanner was still empty after 70 s.
  Now newer fixes wait for the fetch, and the latest is scored when it returns.
- A `200 OK` body containing `{"remark": "...timed out..."}` is treated as a
  failure, so an Overpass timeout cannot poison the cache.
- **Dead:** `cdn.deflock.me/regions/*.json` — behind Cloudflare bot mitigation,
  unusable from a mobile HTTP client.

### 2.2 The WAZE source — two backends

The WAZE source reads user-reported `POLICE` alerts. Two backends exist and the
user picks one in Settings; `scan/WazeSource.kt` is the interface both implement,
so neither leaks into scoring or UI. Default is OpenWeb Ninja. The service builds
the chosen backend at every Start, so **Restart scan to apply** switches it. In
v0.5.18 it was built once per service, and that release's restart fix kept the
service alive, so switching to OpenWeb Ninja and restarting kept polling Waze
directly until a full Stop.

|  | OpenWeb Ninja | Direct (Waze RT) |
|---|---|---|
| Key | user's own, required | none |
| Cost | ~$0.005/request (Oct 2026) | free |
| Poll | 240 s | 60 s |
| Latency | ~20 min behind live | live |
| Position sent to Waze | none | 1 km grid square, every poll |
| Position sent to OpenWeb Ninja | 1 km grid square, every poll, with your key (the exact fix until v0.5.18) | none |
| Default | yes | no, opt-in |

#### 2.2a OpenWeb Ninja (default)

```
GET https://api.openwebninja.com/waze/alerts-and-jams
    ?bottom_left=<lat>,<lon>&top_right=<lat>,<lon>
    &max_alerts=200&max_jams=0&alert_types=POLICE
Header: X-API-Key: <the user's own key>
```

**What OpenWeb Ninja learns.** The box is centred on the centre of the 1 km grid
square from `PositionCloak` (§2.2b), never on the fix, and grown to ±(2000 + 919)
m so the 2 km evaluation circle around the real position stays inside it. So the
provider learns that square every 240 s, tied to your key. Until v0.5.18 the box
was centred exactly on the fix. Checked with the shipping class on 2026-10-10:
two positions 300 m apart in one square produce byte-identical requests.

**Bring your own key** — sign up at openwebninja.com, subscribe to the Waze API,
paste the key into Settings. Stored encrypted (Android Keystore AES/GCM via
`SecureStore`); nothing ships in the APK. Pay-as-you-go ≈ **$0.005/request**,
≈ 15 requests per hour of scanning at the 4-minute poll, so **≈ 7.5¢ an hour** —
about $2.25 a month at an hour a day. The free
tier's 100 requests/month verifies the integration but will not run it.
Prices from `openwebninja.com/api/waze`, checked 2026-10-11: Free $0/month for 100
requests; Pro $25 for 10,000 (+$0.003 each after); Ultra $75 for 50,000
(+$0.002); Mega $150 for 200,000 (+$0.001); Pay As You Go $0.005 a request. The
separate agent storefront (`agents.openwebninja.com`) prices differently.

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

#### 2.2b Direct — the Waze app's own protocol (opt-in)

```
POST https://rt-xlb-am.waze.com/rtserver/distrib/static    # register
POST https://rt-xlb-am.waze.com/rtserver/distrib/login     # authenticate
POST https://rt-xlb-am.waze.com/rtserver/distrib/command   # query / stream
Content-Type: binary/octet-stream      Response: application/x-protobuf
```

Regional hosts: `rt-xlb-am` (North America), `rt-xlb-il` (Israel),
`rt-xlb-row` (rest of world). OVERWATCH hard-codes the North America region
(`WazeRtClient.DEFAULT_REGION = "na"`), so it only ever talks to `rt-xlb-am`,
wherever the phone is.

No key and no account of the user's: `/static` mints an anonymous
username/password pair on request. The request body is line-oriented — protobuf
messages framed as `"ProtoBase64," + base64(Batch{Element})`, plain-text command
lines (`SeeMe`, `SetMood`, `Location`, `MapDisplayed`) — joined by newlines. The
message set lives in `app/src/main/proto/waze.proto` (proto2, `LITE_RUNTIME`).

Behaviour verified 2026-09-21, from a Linux host and from an Android 16
emulator:

- **The session is stateful.** `/command` returns each alert *once* per session
  as an `AddAlertAction`, then a removal as an `old_command` line of the form
  `RmAlert,<uuid>` — not a message type. A client that treats each response as a
  snapshot goes empty after the first query, so responses are merged into a cache
  with a 5-minute soft-delete.
- **Login sets a `Waze-Session-Affinity` cookie** that every later `/command`
  must carry, and the session idles out after ~100 s.
- **A fresh account's first `/command` usually returns an in-band
  `ServerError{code: 504, description: "Retry"}` inside an HTTP 200.** It
  succeeds on the next attempt, so it is retried rather than treated as failure.
- **Queries are a series of shrinking boxes** (5 steps, each half the last, each
  shrunk to 0.75 before sending). The server thins results by viewport size, so a
  single wide box drops near-driver detail.
- **Longitude arrives as unsigned 32-bit micro-degrees** and must be mapped back
  to signed, or the western hemisphere lands on the wrong side of the planet.
- **Data volume:** ~195 KB for the first (handshake) response of a session, ~8 KB
  per query after. Keeping the session alive is therefore *cheaper* than polling
  slowly, which is why the poll is 60 s.
- Anonymous-account registration is capped per device per day (10), so the
  account is persisted encrypted and reused, and a rejected account backs off
  30 s, doubling to 10 min, instead of spinning the register loop. (Until v0.5.18
  the doubling stopped at 8 min.)
- **Forget** (Settings) wipes the stored account, and a running scan registers a
  new one at its next poll. Until v0.5.19 the scan carried on under the forgotten
  account until Stop: Settings forgets through a `WazeRtFetcher` of its own, and
  the scan's fetcher held its session, credentials included, in memory. Now every
  forget bumps a process-wide counter that the scan's fetcher checks each poll.

**What it costs the user, and why it is off by default:** the app becomes a Waze
client. It holds a Waze account and, on every poll, tells Waze which 1 km grid
square it is in. Waze never gets the exact fix, but one persistent account
reporting squares is a coarse track a Google service can follow. The
Settings screen says so in those terms before the toggle.

**Where the position actually goes.** Three things in the protocol carry a
location, and they are not treated alike:

| Carrier | When | What the vendored codec does with the position |
|---|---|---|
| `ClientInfo.last_position` | register, login | jitters it, up to ±500 m per axis |
| `Location,<lon>,<lat>` | each session's handshake | sends it **verbatim**, full double precision |
| `MapDisplayed` viewport | handshake + 5 per poll | sends it **verbatim**: the box is centred on it and the centre is an explicit field |

v0.5.15 passed the raw GPS fix into all three, so only the login messages were
blurred and the exact position went out several times a minute — while the
Settings screen said it was "blurred by up to 500 m". A wire tap built from the
shipping classes showed it on 2026-10-10, for a true position of
`38.832417, -77.106238`:

```
static  ClientInfo           38.831311, -77.109785    331 m from the fix
login   ClientInfo           38.828469, -77.105609    442 m
command Location             Location,-77.106238,38.832417      0.0 m
command MapDisplayed centre  38.832417, -77.106238      0.0 m   (×7 in one fetch)
```

**v0.5.16 fix — `PositionCloak`.** Every position-bearing message is now built
from the centre of a 1 km grid square, never from the fix. (The class lived in
`scan/wazert/` until v0.5.18 moved it to `data/location/PositionCloak.java` to
share it with the ADS-B and OpenWeb Ninja clients. The move changed no cell:
fed the same 400,000 positions, old and new return bit-identical centres.)

- **A fixed grid, not random noise.** Fresh noise on every poll averages out
  over a minute-by-minute stream. Every point in a square maps to one centre,
  so there is nothing to average.
- **Metres, not degrees.** The longitude step is computed per latitude row, so
  cells are 1000 × 1000 m from the equator to 64°N in both hemispheres
  (checked at six cities). Adjacent rows are offset like bricks as a result.
- **150 m hysteresis.** Parked on an edge, GPS wander would flip between two
  squares and the flipping would itself pinpoint you. A square is kept until the
  device is 150 m past it; ±30 m wander across an edge never flips it.
- **Detection unaffected.** The cell centre is at most `√2 × (500 + 150) ≈ 919 m`
  from the real position. The query radius is `max(2000 + 919, 8000) = 8000 m`, so
  the 8 km floor dominates: after the 0.75 shrink the boxes are 6000, 3000, 1500,
  750 and 375 m half-width, and the 2 km evaluation circle around the real fix,
  which reaches at most 2919 m from the centre, stays inside the second.
  Distances are still measured on the device from the real fix.

Verified on the wire the same day: two real positions **745 m apart in one
square produced byte-identical command lines**; every `Location` and
`MapDisplayed` centre equalled the square's centre (within the 1e-6° rounding of
`MapDisplayed`); `ClientInfo` landed 145–545 m from it. The cloaked query missed
none of the police reports the exact query found within 5 km.

**Visibility to other drivers.** The handshake's `SeeMe,1,2,…` sets the
visibility group to **1 = `VisGrp_Invisible`**, per `RealtimeDefs.h` in the
original open-source Waze client (mirrored at `github.com/mkoloberdin/waze`), so
OVERWATCH asks not to appear on other drivers' maps. Every response nonetheless
carries `x-waze-stealth-mode-activated: false`. What that header tracks is
**unverified** — treat "invisible to other drivers" as requested, not proven.

The protocol layer under `scan/wazert/` is **vendored** from
highway-radar-sabre-plus (MIT, licence kept beside the code): the codec, session,
constants, geometry, alert cache and model classes plus `proto/waze.proto`,
unmodified apart from the package name, removal of the report-submission path,
and a rewrite of the HTTP client from OkHttp to `HttpURLConnection`.
`WazeRtFetcher.java` is OVERWATCH's own driver modelled on upstream's, carried
under the same licence; `PositionCloak` is OVERWATCH's own and lives outside that
directory. OVERWATCH reads alerts and never reports one.

**Why not `live-map/api/georss`:** the endpoint every scraper used is fronted by
Google's edge, which returns **HTTP 403** to automated clients. Tested 2026-07
and again 2026-09 from a residential IP — plain curl, headless Chromium,
**headful** Chromium on a real display, and undetected-chromedriver all 403, as
did Waze's own page requests. The response carries `via: 1.1 google`, i.e. the
block is at the front end, before any application logic; no user-agent, cookie,
TLS fingerprint or IP changes it. The RT hosts above are a different front end
and answer normally. The Waze for Cities partner feed excludes POLICE and is
agency-only.

### 2.3 ADS-B community networks — the AIRCRAFT source

```
GET https://opendata.adsb.fi/api/v2/lat/<lat>/lon/<lon>/dist/30   (preferred)
GET https://api.adsb.lol/v2/lat/<lat>/lon/<lon>/dist/30           (fallback)
```

**No API key, no account.** Both are volunteer data-in/data-out networks running
the same readsb-derived schema, so one parser covers both.

**What the feeds learn.** `<lat>`/`<lon>` are the centre of the 1 km grid square
from `PositionCloak` (§2.2b), to four decimals with a `.` separator, never the
fix. The 30 nm query dwarfs the ≤919 m offset, and distances are measured on the
device. Until v0.5.18 the URL carried the fix itself (~11 m) every 60 s, and it
was formatted in the phone's locale: on a German, French or Spanish phone the
request read `lat/38,8895/…`, which the feeds answer with HTTP 400, so the source
never worked there. (Checked 2026-10-10: comma form → 400; the v0.5.18 client
under a German locale → 200 with 66 aircraft.)

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

14.5 MB gzipped, **618,270 aircraft**, newline-delimited JSON, derived mostly
from the FAA registry and **already keyed by ICAO hex** — which is what ADS-B broadcasts,
so no N-number→hex conversion is needed. Fields: `icao`, `reg`, `ownop`
(owner/operator), `icaotype`, `model`, `faa_pia`, `faa_ladd`, `mil`.

Filtering `ownop` for law-enforcement patterns yields **1,971 aircraft**
(213 LADD-flagged), bundled as `app/src/main/res/raw/le_aircraft.csv` (~83 KB).
**1,935 are US agencies; 36 are foreign police** the same patterns catch: the
Korean National Police (8), New South Wales Police (8), Western Australia Police
(8), Royal Oman Police (3), one each from the Australian Federal Police, Victoria
Police and the Dutch police, and six Canadian entries: Ontario's Ministry of the
Solicitor General (2), Peel and York regional police, the Regina Board of Police
Commissioners (`C-GRPF`, a Cessna 182: ADSBexchange has the full name, and our
generator cuts every owner name at 48 characters), and the Saskatchewan Public
Safety Agency. That last one matched on `PUBLIC SAFETY`, and its air operations
are most likely wildfire work, so it is probably not police — the pattern's
limit. They are kept because they matter to users outside the US. 18 of the US entries sit outside the civil US address block
`A00001–ADF7C7`; all are CBP or DHS.
Regenerate with `scripts/gen-le-aircraft.py`. It runs at development time and
commits its output; the app never downloads it.

Owner-match patterns: `SHERIFF`, `POLICE`, `STATE PATROL`, `HIGHWAY PATROL`,
`STATE TROOPER`, `PUBLIC SAFETY`, `US MARSHAL`, `MARSHALS SERVICE`, `CONSTABLE`,
`DEPT/DEPARTMENT OF CORRECTION`, `BORDER PATROL`, `CUSTOMS AND BORDER`,
`CUSTOMS & BOR` (a prefix, so it matches however the name goes on; the source names are complete, and only the generator's 48-character cut shortens them), `HOMELAND SECURITY`, `DRUG ENFORCEMENT`, `FEDERAL BUREAU OF INVESTIGATION`.

Excluded to avoid false positives: `MARSHALL SPACE` (NASA), plus `FIRE`,
`AMBULANCE`, `MEDICAL`, `EMS`, `AIR METHODS`, `LIFE FLIGHT`, `MEDEVAC`,
`HOSPITAL` — air-ambulance operators share a lot of vocabulary with police
aviation, and a medevac helicopter is not what this app exists to warn about.

Top operators by airframe count, adding up every spelling of an agency's name:
DHS 306 (CBP with its Air and Marine Operations, Border Patrol and DHS itself,
under 26 spellings), Arizona DPS 67, the California Highway Patrol 58 and Texas
DPS 32. A single spelling undercounts: the largest, `DEPARTMENT OF HOMELAND
SECURITY`, covers 98. (Until v0.5.19 this line mixed single spellings with totals
and left the LADD-flagged aircraft out of two figures.)

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
in v0.5.8 rather than left as a permanently-failing row. (No v0.5.7 was ever
released; the version went from 0.5.6 to 0.5.8.)

*(Historical footnote for the talk: passing that empty string to `JSONObject`
throws `Value of type java.lang.String cannot be converted to JSONObject` —
the doubled space in the message is where the empty value interpolates. That
exact string, leaked into the app's UI, is how the shutdown was first noticed.)*

---

## 3. Radio identifiers

### 3.1 BLE OUIs — surveillance

`data/targets/BleOuis.kt`. Matched on the lowercased `xx:xx:xx` MAC prefix.

**Axon** (flagged separately, scores 80): `00:25:df`

**Seen on Flock hardware** (24, from flock-detection). None is registered to
Flock Safety. The IEEE registry (hwdata 0.411, checked 2026-10-11) gives ten to
Silicon Labs, nine to Liteon, three to Espressif and one to USI, all radio-module
makers, and `d8:a0:d8` is unassigned. A hit is a module those makers also sell
for everything else, which is why an OUI alone scores 40, the bottom of YELLOW.
Flock Safety's own registration, `b4:1e:52`, sits with the vendor OUIs (§3.3).
```
58:8e:81  cc:cc:cc  ec:1b:bd  90:35:ea  f0:82:c0  1c:34:f1  38:5b:44  94:34:69
b4:e3:f9  3c:91:80  d8:f3:bc  80:30:49  14:5a:fc  9c:2f:9d  94:08:53  e4:aa:ea
48:e7:29  c8:c9:a3  74:4c:a1  70:c9:4e  04:0d:84  08:3a:88  a4:cf:12  d8:a0:d8
```

### 3.2 WiFi OUIs — seen on Flock hardware

`data/targets/WifiOuis.kt`. flock-you's 31 prefixes (research by NitekryDPaul +
DeFlockJoplin). Despite the "superset" label upstream, only 14 of
flock-detection's 24 BLE prefixes are among them, and `82:6b:f2` has the
locally-administered bit set, so it is not an IEEE OUI; it is kept because
flock-you observed it. None is registered to Flock Safety: by the IEEE registry
they are 21 Liteon, 3 Silicon Labs, 2 Espressif, 2 USI and 1 Samsung, and
`b8:35:32` is unassigned.
```
70:c9:4e  3c:91:80  d8:f3:bc  80:30:49  b8:35:32  14:5a:fc  74:4c:a1  08:3a:88
9c:2f:9d  c0:35:32  94:08:53  e4:aa:ea  f4:6a:dd  f8:a2:d6  24:b2:b9  00:f4:8d
d0:39:57  e8:d0:fc  e0:4f:43  b8:1e:a4  70:08:94  58:8e:81  ec:1b:bd  3c:71:bf
58:00:e3  90:35:ea  5c:93:a2  64:6e:69  48:27:ea  a4:cf:12  82:6b:f2
```

Android exposes only the BSSID — flock-you's promiscuous-mode `addr1` and
wildcard-probe tricks are not portable to a userspace Android app.

### 3.3 Vendor OUIs — enterprise / municipal surveillance

`data/targets/VendorOuis.kt`. All 19 verified against the IEEE registry (18
against the Wireshark `manuf` snapshot of 2026-08-28, Flock Safety's against
hwdata 0.411 on 2026-10-11), so a hit names the manufacturer directly. Merged
into **both** the BLE and WiFi match sets.

| OUI | Vendor | Radio reality |
|---|---|---|
| `b4:1e:52` | Flock Safety | Flock's own registration, added in v0.5.19; the Flock lists in §3.1 and §3.2 are module makers' prefixes and never carried it. Which Flock products transmit with it isn't documented. Not police-exclusive (Flock also sells to HOAs and businesses), so it scores 40. |
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

| UUID | Meaning | Firmware | Counts as evidence |
|---|---|---|---|
| `3100` | GPS Location | 1.2.x+ | alone |
| `3200` | Power Management | 1.2.x+ | alone |
| `3300` | Network Status | 1.2.x+ | alone |
| `3400` | Upload Statistics | 1.3.x | alone |
| `3500` | Error Diagnostics | 1.3.x | alone |
| `180a` | Device Information | all | only with `1809` + `1819` |
| `1809` | Health Thermometer | 1.1.x | only with `180a` + `1819` |
| `1819` | Location & Navigation | 1.1.x | only with `180a` + `1809` |

Three or more counted UUIDs scores 90; one or two scores 70. The last three are
standard Bluetooth SIG services that ordinary devices advertise on their own; a
Bluetooth thermometer sends `1809`. Before v0.5.17 any single one of the eight
scored 70, ORANGE, labelled "Raven gunshot detector". Since then they count only
as the full set of three, which is FW 1.1.x's signature. Every documented
firmware still scores 90: FW 1.1.x counts its three generics, FW 1.2.x its three
own UUIDs, and FW 1.3.x its five. The screen-off scan filters still list all
eight, because a filter only decides which advertisements get delivered.

### 3.5 Manufacturer-specific data

`data/targets/Manufacturers.kt`. Company ID **`0x09C8` (XUNTONG)** scores 60; a
`"TN"`-prefixed serial in the payload adds 20.

The BLE scanner iterates **every** manufacturer-data entry in an advertisement,
not just the first — devices advertise several and the target is often not
first in the list. Only one company id is kept per observation, ranked XUNTONG
first, then any COMMERCIAL company id (§3.7), then whatever came first. Until
v0.5.18 the middle rank was missing, so a glasses or Echo id behind a
lower-numbered entry went unseen.

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
should survive if the 16-slot list is trimmed. With COMMERCIAL on it always is:
1 XUNTONG + 8 Raven + 2 service UUIDs + 8 company ids makes 19, so the last
three company ids (Amazon, Google, Sonos) never get a slot, while the five
glasses ids do.

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
some devices advertise several. (OVERWATCH had the same gap for COMMERCIAL ids
until v0.5.18; see §3.5.)

Hidden-camera OUIs, all radio-module makers' and so high-noise: `fc:b4:67`,
`dc:4f:22` and `8c:ce:4e` (Espressif), `00:e0:4c` (Realtek, in many cheap cams)
and `a4:c1:38` (Telink, common in cheap BLE mics). Until v0.5.19 the code credited
three of them to Yingxin/SmartLife, Tuya and Shenzhen iComm; the IEEE registry
says Espressif.

The Amazon and Google OUI sets are not all Amazon's and Google's. The Amazon set
includes Samsung's `a8:51:5b`; the Google set includes BlackBerry's `94:eb:cd`,
AzureWave's `6c:ad:f8`, Zhongshan B&T's `94:c9:60`, Wistron's `f4:f1:9e` and the
unassigned `fc:9f:e9`. They are kept, so a device with one of those prefixes can
be labelled Amazon or Google gear when it is neither; a lone OUI hit scores 30, so
it shows only very close and passing (§4.2).

Weights: OUI 30, name 45, company id 30, service UUID 50, SSID 45; +10 when two
or more methods agree on one device, +5 above −50 dBm, +8 for rise-peak-fall.
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
**absolute metres and deliberately independent of the user's `show within`
setting**: a camera 200 m away is equally close whether the slider reads 300 m
or 5 km, so moving a setting must never move the threat level. The setting
decides only what is drawn and listed, never what is evaluated or how alarming
it is.

| Distance | ALPR | Speed camera | Generic camera | Waze police | Aircraft |
|---|---|---|---|---|---|
| 0 m | 95 | 78 | 55 | 80 | 88 |
| 50 m | 85 | 70 | 46 | 75 | — |
| 100 m | 72 | 63 | 42 | 70 | — |
| 200 m | 55 | 51 | 35 | 63 | — |
| 350 m | 42 | 40 | 29 | 53 | — |
| 500 m | 35 | 32 | 27 | 48 | — |
| 1000 m | 26 | 27 | 21 | 35 | 80 |
| 3000 m | 18 | 16 | 14 | 23 | 68 |
| 15000 m | — | — | — | — | 30 |

Values are the code's (linear interpolation, then rounded), before any modifier.
Each first drops below 40 at: ALPR ~403 m, speed camera ~351 m, generic camera
~132 m, Waze ~769 m, aircraft ~11 km.

**Every curve crosses below 40 (YELLOW) at roughly the distance the thing stops
being able to act on you**, and **scoring is fully decoupled from the range
control**: scanners evaluate everything inside their own fixed radii (DeFlock
1200 m, Waze 2000 m, aircraft 15 km, or 8 km for an unlisted plane) and the tier is computed from that, so the
`show within` slider cannot move it in either direction. Anything at YELLOW or
above is displayed whatever the range says, since a view setting that can hide a
live alert is a trap rather than a feature.

Getting there took three passes. v0.5.8 made the anchors absolute. But the
on-screen tier is `max(score)` over everything reported, and the slider then
decided what was reported, so loose curves let the setting leak into the alarm:
measured against a real 195-node cache, standing still and dragging from 300 m
to 500 m flipped the app GREEN → YELLOW with nothing physical changing. v0.5.10
recalibrated the curves, and sweeping 200 m → 4900 m then left the tier untouched
(reported items 0 → 133) — but that sweep ran where the nearest item scored 35,
so it could not catch the opposite case: *narrowing* the range still hid a real
alert. On a device, a police report 312 m away scoring 52 went GREEN when the
view went from 1100 m to 300 m. v0.5.12 decoupled the two: the scanners evaluate
at fixed radii, and anything YELLOW or above is shown whatever the range.
Standing 29 m from a Flock camera still reads 89 RED.

Aircraft are evaluated over their own fixed ranges — 15 km for a registry hit,
8 km for a loitering unknown — because an aircraft orbiting overhead is still
watching you from a distance at which a camera cannot see you at all. Display
works as for every source: YELLOW and above always shows, and a weaker aircraft
beyond the `show within` range is hidden. Since the range tops out at 5 km, a
sub-40 aircraft further out is never drawn: a registered airframe 9 km away at
9,000 ft scores 33.

A surveyed ALPR peaks highest and decays fastest — its position is exact and it
cannot see you from a kilometre away. A Waze report is a coarse pin on a car
that may be moving toward you. An aircraft decays slowest of all, because one
orbiting 5 km away is still watching you.

### 4.2 Modifiers

- **Waze age decay** — up to −12 points across the 45-minute freshness window.
  Police move; a surveyed camera does not.
- **Waze crowd trust** — `reliability ≥ 7` +5, `confidence ≥ 4` +5. On the direct
  backend confidence is always 0 and reliability is the thumbs-up count capped
  at 10, so only the first can apply.
- **Aircraft altitude** — ≤3,000 ft +6; ≤8,000 ft +0; ≤15,000 ft −12;
  above −30. A known police airframe at 30,000 ft is an airliner's neighbour,
  not an observer.
- **Aircraft loitering** — +10. Surveillance aircraft orbit; airliners and
  medevac flights heading somewhere do not.
- **Aircraft LADD** — +4. The operator asked public trackers to hide it.
- **Unidentified aircraft cap** — 69, the top of YELLOW. Behaviour-only evidence
  never reaches ORANGE, let alone RED; circling could still be news or survey
  work.
- **Radio base weights.** BLE: OUI 40 (Axon 80, police-exclusive vendor 75), name
  45 (Penguin numeric 15), XUNTONG maker id 60 (+20 with a `TN` serial), Raven 70
  (90 for three or more counted UUIDs). WiFi: OUI 40 (police-exclusive 75), Flock
  SSID format 65, generic SSID 50. Methods on one device add up, capped at 100.
- **Multi-method bonus** — +20 when two independent BLE/WiFi methods agree.
- **Strong RSSI** (> −50 dBm) +10; **stationary** (rise-peak-fall) +15. COMMERCIAL
  uses +10, +5 and +8 (§3.7).
- **Display floor** — a BLE, WiFi or COMMERCIAL hit scoring under 40 is dropped:
  it is never shown and never counts toward the tier (`ALARM_THRESHOLD` in
  `BleScanner` and `WifiScanner`).
  Most single methods clear it alone. Three don't. A Penguin numeric name (15)
  needs both a strong signal and a rise-peak-fall (15 + 10 + 15), and so does a
  lone COMMERCIAL OUI or company id (30 + 5 + 8). So glasses that advertise only
  Meta's or Luxottica's company id show only very close, as you pass them; a
  matching name or the HeyCyan service UUID shows at any range the radio reaches.

### 4.3 Loiter detection

An aircraft is "loitering" when, over the last 15 minutes of tracked history:
at least **3 samples** spanning at least **4 minutes**, every one within
**5 km** of their own centroid, with the latest report at or below **12,000 ft**
and **200 kt**. A contact reporting `ground` altitude, or no speed, does not count
as low or slow.

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
Flock prefixes, the 19 vendor prefixes). There is no filter that means "any MAC
starting with these three octets", and no filter that means "any device".

So the scanner switches strategy on `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF`:

| Screen | Scan | Covers | Loses |
|---|---|---|---|
| on | unfiltered | everything — OUI, name, UUID, manufacturer | — |
| off | filtered (≤16) | Raven service UUIDs, XUNTONG manufacturer id, the Echo and HeyCyan service UUIDs, the glasses company ids (Amazon, Google and Sonos are trimmed when COMMERCIAL is on, §3.7) | OUI prefixes, name substrings |

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

### 5.3 What stays on the phone

Detections live in memory and clear when scanning stops. Two disk caches and the
system log could otherwise show roughly where the app has been used
(`data/cache/LocationTraces.kt`):

| Cache | What it reveals | When it is deleted |
|---|---|---|
| `cache/deflock/` | one file per ~5.5 km area scanned | every stop, and again at the next start |
| `cache/osmdroid-tiles/cache.db` | OpenStreetMap tiles wherever the map was shown | same schedule, only if **Settings → Privacy → Delete map tiles when scanning stops** is on (default off) |
| Android's log (logcat) | nothing positional and no account details: no coordinates, cell names, request URLs or Waze account name. v0.5.17 and earlier logged the exact fix on every camera-map fetch. v0.5.18 still quoted a malformed reply from Overpass, the aircraft feeds or OpenWeb Ninja whole (Android's org.json puts its entire input in a syntax error, so: the positions of the cameras, aircraft or police around you), and logged the direct backend's account name; since v0.5.19 those lines carry the error's class only | not the app's to delete: it lives in RAM, rotates, and clears on reboot. One gap remains outside the app's code: osmdroid logs a tile's number when that tile fails to download |

Tiles stay by default because OpenStreetMap's tile usage policy asks apps to
cache tiles rather than re-download them. They live in one SQLite database that
every MapView shares through osmdroid's static connection, and a MapView can
still be attached when scanning stops. So the file is never deleted out from
under it. Tiles are purged through that same connection, then `VACUUM` rewrites
the file, so deleted rows don't linger in free pages.

### 5.4 What leaves the phone

Every online destination, and the most each one learns:

| Destination | What it learns | How often |
|---|---|---|
| ADS-B feeds (`opendata.adsb.fi`, `api.adsb.lol`) | the centre of your 1 km grid square | every 60 s while scanning |
| OpenWeb Ninja (default Waze backend) | the centre of your 1 km grid square, tied to your API key | every 240 s |
| Waze (direct backend, opt-in) | the centre of your 1 km grid square, tied to an anonymous account; `ClientInfo` gets that centre jittered | every 60 s |
| Overpass (`overpass.deflock.org`, `overpass-api.de`) | which 0.05° (~5.5 km) cell you are in | at start, then when your 1.2 km circle reaches past the loaded box, for a cell not yet fetched this scan; a failure is retried after 60 s |
| OpenStreetMap tile servers | the tiles the map loads, i.e. roughly the area on screen | while the map or bubble is visible, for tiles not already cached |
| Google Maps (a row's map pin) | the exact position of that detection: a mapped camera within 1.2 km of you, a police report within 2 km, or an aircraft | only when you tap the pin |

Before v0.5.18 the ADS-B feeds got the fix itself (~11 m), and the Overpass and
OpenWeb Ninja boxes were centred exactly on it; before v0.5.16 Waze direct got it
too. The location fix itself comes from Google Play services' fused provider;
whether that consults Google's servers depends on the phone's location settings,
not on OVERWATCH.

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
  code is deliberately excluded, since OVERWATCH never advertises to or
  connects with what it detects.
- **flock-detection** — the confidence-scoring algorithm, RSSI rise-peak-fall
  stationary detection, OUIs, UUIDs and name patterns.
- **flock-you** — the 31-prefix Flock WiFi list.
- **deflock / deflock-app** — the Overpass query shape and proximity-alert
  pattern.
- **wazepolice** — the original `live-map/api/georss` recipe, now dead.
- **Waze 2.x client source** (`github.com/mkoloberdin/waze`, the mirror the Waze
  wiki points to) — consulted, not used, for what the `SeeMe` visibility values
  mean. Nothing from it is in OVERWATCH.
- **highway-radar-sabre-plus** (MIT) — the Waze RT protocol layer. The only
  reference that is *vendored* rather than studied: `scan/wazert/*.java` (apart
  from `WazeRtFetcher.java`, OVERWATCH's own driver modelled on upstream's) and
  `proto/waze.proto` are that project's code, carried with its licence.
