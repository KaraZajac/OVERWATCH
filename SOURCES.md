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
| **AIRCRAFT** | Police / surveillance aircraft overhead, and drones by their Remote ID broadcast | ADS-B community feeds; drones by local radio | free | 2026-09-17 / 2026-11-01 |
| **COMMERCIAL** | Consumer cameras, voice assistants, smart glasses | Rides BLE + WiFi | free | 2026-11-01 |
| **HACKER** | Attack and pentest gear: Flipper Zero, BLE spam, rogue APs, beacon spam, card skimmers (opt-in) | Rides BLE + WiFi (+ opt-in Bluetooth Classic) | free | 2026-11-01 |
| **TRACKER** | Item trackers that are away from their owner, or that stay | Rides BLE | free | 2026-11-01 |

Two radio sources (local), three network sources (position-driven), and three
that ride the radio scans; drones' Remote ID is heard by the radio scans and
filed under AIRCRAFT. The app **never connects to, pairs with, or jams**
anything it detects. It is not radio-silent, though: Android's scans transmit —
Wi-Fi scanning sends probe requests and Bluetooth scanning sends scan requests
to advertisers; the opt-in card-skimmer check (§3.10) also sends Bluetooth
Classic inquiries and name requests — and the network sources have to say where
to look. Since v0.5.18 none of them is given
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
- **A camera the circle has left drops out at once.** Every fix re-scores each
  camera within 1.2 km and replaces the source's whole set, so the list, the ring
  and every distance follow the latest fix. A fix that needs a new cell is first
  scored against the box already loaded, so cameras left behind are gone before
  the fetch returns. Until v0.5.20 cameras were only ever added: one left behind
  kept the distance and score of the last fix that had it in range until the
  store's 5-minute expiry. After a jump in position (a cached first fix from
  somewhere else at Start, a GPS glitch, the end of a tunnel) the screen still
  read "@ 66m" with an orange ring 12 km away, as seen on an emulator.
- **A `200 OK` is not yet a result.** When a query runs past a server limit,
  Overpass answers HTTP 200 with a top-level `"remark": "runtime error: Query
  timed out…"` and whatever elements it had reached. That is a failure and is not
  cached. Since v0.6.1 the reply must also parse as a result (JSON with an
  `elements` array; Overpass sends an empty one for an area with nothing in it,
  checked 2026-11-12), or the next endpoint is tried and the row reads
  "Unreadable reply". Through v0.6.0 any other 2xx body, such as an error page
  served with a 200, was cached and read as "no cameras here", with a healthy
  status row, for the rest of the scan. The remark check now reads only the
  reply's own `remark`; it used to search the whole body, so one camera whose
  tags held both "remark" and "timeout" would have failed its cell for good. A
  cache file that no longer parses (a write cut short) is a miss, not an empty
  area.
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

Both backends' reports are scored the same way. A poll's reports are kept and
re-scored at every fix until the next poll: a report doesn't move, so its
distance from the latest fix is always known, and one more than 2 km away drops
out at once. If a poll fails, the last reports stay scored for 5 minutes, then
go. Since v0.6.1 a failure that looks passing (no network, a timeout, a 5xx, an
unreadable reply) is asked again after 30 s, twice at most, before the regular
interval resumes: on OpenWeb Ninja that interval is 4 minutes, so one lost poll
used to blank the reports for about three minutes. A rejected key or any other
4xx is never retried early, and the direct backend paces its own retries. An
early retry that reaches OpenWeb Ninja is a billed request like any other.
Until v0.5.20 a report kept the distance and score of the poll that found it
until the store's 5-minute expiry: when the first fix at Start was the phone's
cached position 12 km away, a report there read "@ 180m" for minutes, as seen on
an emulator.

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

Since v0.6.1:

- **A 2xx that is not a result is a failure.** A body that is not JSON, or JSON
  with `"status": "ERROR"`, gives "Unreadable reply" on the WAZE row and an early
  retry (§2.2). Through v0.6.0 it counted as "no police here", with a healthy
  row. An envelope with no alerts array is still read as "none": what this feed
  sends for an empty box has never been seen, so its absence is not taken for a
  failure.
- **Coordinates are written `%.6f` under `Locale.US`.** They were built with
  `Double.toString()`, which is locale-safe but writes 0.0005 as `5.0E-4`. Only a
  box with an edge within 0.001° (about 111 m) of the equator or the prime
  meridian could produce that form, and whether the API accepts it was never
  tested.

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
  the doubling stopped at 8 min.) Since v0.6.1 a new account is counted and saved
  the moment `/static` returns it, whatever becomes of the login and handshake
  that follow. Through v0.6.0 that happened only after all three had worked, so a
  registration followed by a rejected login was forgotten and replaced without
  ever counting toward the cap: the one loop the cap exists to stop.
- **A login answered 408 or 429 is not a rejected account** (v0.6.1). Those say
  "try again" or "slow down"; through v0.6.0 they were handled like every other
  4xx, by deleting the account and registering another. Whether Waze's login ever
  sends either is not known; the change is the cautious reading.
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
  The grid is not cut to the globe, so the last column before the antimeridian
  has its centre up to half a cell past ±180°. Since v0.6.1 that centre is
  wrapped (and a latitude clamped to ±90°) before it is sent; through v0.6.0
  anyone within about a kilometre of the line (Taveuni, Chukotka) sent a
  longitude no server accepts.
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
directory. OVERWATCH reads alerts and never reports one. `WazeSession.java` has
three further changes, each recorded in its header: the account name and session
id are not logged (v0.5.19), the server's error text is not logged, and the
408/429 rule above (both v0.6.1).

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
- **A 2xx that does not parse falls through to the second feed** (v0.6.1). Both
  feeds send an empty array for empty sky (adsb.fi as `aircraft`, adsb.lol as
  `ac`; checked 2026-11-12), so a reply with neither array is not a result.
  Through v0.6.0 it counted as "no aircraft" and `adsb.lol` was never asked.
- **Each poll replaces the whole ADS-B set** (v0.6.1). An aircraft that has flown
  out of range, landed or stopped circling goes at that poll. Through v0.6.0 each
  one was submitted on its own and never withdrawn, so a helicopter that had left
  kept its last distance, its map marker and the alert level for five more
  minutes. A failed poll replaces nothing, so an outage still ends with the last
  set ageing out. Drones are heard by radio and are not part of that set (§3.9).

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

**Axon** (flagged separately, scores 80): `00:25:df`, and since v0.6.0
`d8:1f:65`, which the IEEE lists as Private and field captures attribute to Axon
(§3.8). Both are also checked on Wi-Fi.

**Seen on Flock hardware** (21, from flock-detection's 24). None is registered to
Flock Safety. The IEEE registry (hwdata 0.411, checked 2026-10-11) gives ten to
Silicon Labs, nine to Liteon and one to USI, all radio-module makers, and
`d8:a0:d8` is unassigned. A hit is a module those makers also sell for
everything else, so **since v0.6.0 a module prefix alone scores 25, under the
display floor**: it shows only with a second signal, such as a Flock name, the
Penguin pack's maker id or a pass (§4.2). Until then it scored 40 and showed on
its own. v0.6.0 also dropped the three Espressif blocks (`48:e7:29`, `c8:c9:a3`,
`a4:cf:12`), which every ESP32 gadget carries; SquachWatch and
all-cameras-are-beacons retired them for the same reason. Flock Safety's own
registration, `b4:1e:52`, sits with the vendor blocks (§3.3).
```
58:8e:81  cc:cc:cc  ec:1b:bd  90:35:ea  f0:82:c0  1c:34:f1  38:5b:44  94:34:69
b4:e3:f9  3c:91:80  d8:f3:bc  80:30:49  14:5a:fc  9c:2f:9d  94:08:53  e4:aa:ea
74:4c:a1  70:c9:4e  04:0d:84  08:3a:88  d8:a0:d8
```

### 3.2 WiFi OUIs — seen on Flock hardware

`data/targets/WifiOuis.kt`. flock-you's 31 prefixes (research by NitekryDPaul +
DeFlockJoplin), 28 since v0.6.0. Despite the "superset" label upstream, only 14 of
flock-detection's 24 BLE prefixes are among them, and `82:6b:f2` has the
locally-administered bit set, so it is not an IEEE OUI; it is kept because
flock-you observed it. None is registered to Flock Safety: by the IEEE registry
they are 21 Liteon, 3 Silicon Labs and 2 USI, and `b8:35:32` is unassigned.
v0.6.0 removed Samsung's `48:27:ea`, which would name a Samsung TV's Wi-Fi Direct
network a Flock camera (SquachWatch left it out for that reason), and Espressif's
`3c:71:bf` and `a4:cf:12`. As on BLE, a module prefix alone now scores 25, and
since v0.6.1 Wi-Fi has no pass bonus (§4.2), so here it never shows alone. A real
Flock hotspot also carries its `Flock-XXXXXX` SSID (§3.6), which lifts it to 100.
```
70:c9:4e  3c:91:80  d8:f3:bc  80:30:49  b8:35:32  14:5a:fc  74:4c:a1  08:3a:88
9c:2f:9d  c0:35:32  94:08:53  e4:aa:ea  f4:6a:dd  f8:a2:d6  24:b2:b9  00:f4:8d
d0:39:57  e8:d0:fc  e0:4f:43  b8:1e:a4  70:08:94  58:8e:81  ec:1b:bd  58:00:e3
90:35:ea  5c:93:a2  64:6e:69  82:6b:f2
```

**Flock turned that hotspot off around December 2025** (flock-you's README):
cameras moved from AP to station mode and now only send probe requests, which no
Android app can see. So this list, which can only match access points, mostly
finds older cameras.

Android exposes only the BSSID — flock-you's promiscuous-mode `addr1` and
wildcard-probe tricks are not portable to a userspace Android app.

### 3.3 Vendor OUIs — enterprise / municipal surveillance

`data/targets/VendorOuis.kt`. All 37 verified against the IEEE registry (18
against the Wireshark `manuf` snapshot of 2026-08-28, Flock Safety's against
hwdata 0.411 on 2026-10-11, the 18 added in v0.6.0 against the IEEE MA-L, MA-M and
MA-S CSVs of 2026-11-01), so a hit names the manufacturer directly. Checked by
**both** the BLE and WiFi scanners. Since v0.6.0 a block can be 24 bits (MA-L), 28
(MA-M) or 36 (MA-S), written `58:e8:76:c` and `70:b3:d5:52:1`; the longest match
wins, so a small block inside a shared MA-L range names only its own holder.

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
| `00:23:bd` | Digital Ally | In-car and body video. **Police-exclusive.** |
| `00:09:bc` `00:16:ed` | Utility, Inc. (BodyWorn) | Body cams, in-car video and their activation remotes; `00:09:bc` carried the 10 captured BodyWorn remotes (§3.8). **Police-exclusive.** |
| `58:e8:76:c` (MA-M) | Kustom Signals | Police radar, lidar and in-car video. **Police-exclusive.** |
| `1c:82:59:d` (MA-M) | Applied Concepts (Stalker) | Police radar and lidar; also sports radar guns, so not police-exclusive. The Stalker DSR 2X+ has Wi-Fi. |
| `6c:18:11` | Decatur Electronics | Police radar; also sports radar. |
| `00:04:7d` `00:18:85` `00:1f:92` `4c:cc:34` `10:74:6f` `9c:86:2b` `b8:e2:8c` | Motorola Solutions (the last three its Malaysian arm) | Radios, Avigilon cameras, Vigilant ALPR. In all-cameras-are-beacons' logs 27 of 27 indoor hits were fixed building hardware and 6 road hits were at law-enforcement scenes, so not police-exclusive. |
| `08:3c:03:0` (MA-M) | Federal Signal SSG | Lightbars and sirens (police, fire, EMS). No field sighting. |
| `7c:83:34:4` `84:b3:86:5` (MA-M) | Fusus (Axon) | Camera hubs that share private cameras with police real-time crime centers. No field sighting. |
| `70:b3:d5:52:1` `70:b3:d5:f5:e` `70:b3:d5:1c:5` (MA-S) | Selex ES Inc. / ELSAG (Leonardo) | ALPR maker. No field sighting. |
| `00:17:3d` | Neology | ALPR and tolling. No field sighting. |
| `00:bf:15` `0c:bf:15` | Genetec | AutoVu ALPR and video management. No field sighting. |

**Police-exclusive subset** (ShotSpotter, WatchGuard, Digital Ally, Utility,
Kustom Signals) scores 75 on sight — same rationale as the Axon OUI: these
blocks appear on nothing consumer. The rest score 40.

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
own UUIDs, and FW 1.3.x its five. The screen-off scan filters list the five
own UUIDs and `1819`: a filter only decides which advertisements get delivered,
FW 1.1.x sends its three generics together, so one wakes the scan, and since
v0.6.0 the two saved slots go to Axon and Remote ID (§5.1).

### 3.5 Manufacturer-specific data

`data/targets/Manufacturers.kt`. Company ID **`0x09C8` (XUNTONG)** scores 60; a
`"TN"`-prefixed serial in the payload adds 20. It is the **Penguin battery
pack's** ID: it rides on 7,575 of the 17,410 `Penguin-` records in flock-you's
WiGLE set and on none of its 4,907 `FS Ext Battery` ones, and the camera and
Raven radios are LiteOn/Qualcomm and ESP32. Until v0.6.0 the code called it
Raven's maker. XUNTONG (a Shenzhen BLE-module maker by name) is probably the
pack's module supplier. A hit with no name is labelled "Flock Penguin battery".

The BLE scanner iterates **every** manufacturer-data entry in an advertisement,
not just the first — devices advertise several and the target is often not
first in the list. Only one company id is kept per observation, ranked XUNTONG
first, then any COMMERCIAL company id (§3.7), then whatever came first. Until
v0.5.18 the middle rank was missing, so a glasses or Echo id behind a
lower-numbered entry went unseen.

### 3.6 Name and SSID patterns

`data/targets/Patterns.kt`.

Since v0.6.0 every Flock pattern is anchored to the form real devices were seen
to use; the loose substrings before it matched home networks and missed real
hotspots.

- **BLE local names**: exactly `FS Ext Battery`; `^Penguin-\d{10}$` (all 17,410
  Penguin records in flock-you's WiGLE set have this form); `^FS-[0-9A-Fa-f]{4,12}$`
  (field capture at Flock sites; the bare `FS-` substring matched any name
  containing it); and `Flock` as a case-sensitive substring, which is how Raven
  units are discovered (GainSec BirdShot). `Pigvision` is gone: no capture
  anywhere shows a device using it, and flock-you's "Pigvision" file lists camera
  locations, not radio sightings. `FlockCam` is covered by `Flock`.
- **Penguin numeric**: a bare ten-digit name (narrowed from 8–12). Any gadget can
  carry one (a phone advertising `0102000000` matched in all-cameras-are-beacons'
  logs), so it stays a weak signal (15).
- **Flock hotspot**: `^flock-[0-9a-f]{6}$`, case-insensitive, 65, and +15 when the
  hex is the BSSID's last three bytes. flock-you's WiGLE set has 803 Flock SSIDs,
  every one in this form, 780 ending in the BSSID. The old `^Flock-[0-9A-Fa-f]{4}$`
  matched none of them, so until v0.6.0 they were caught only by the generic
  substring, at 50.
- **Flock Camera hotspot**: SSID starting `Flock Camera` (a camera's own hotspot,
  "Flock Camera net.", flock-you issue #43), 65.
- **Generic SSID** (50): exactly `Flock` (the network Raven units join, per
  GainSec's white paper — unless the BSSID is Espressif's, which is ESP32
  Marauder's decoy, §3.10); `^Penguin-\d{10}$`; the prefixes `flock-`, `flock_`
  and `FS_`; and the tokens `flocksafety`, `flockos`, `fs ext battery`. The old
  substrings `flock`, `Penguin` and `FS_` also matched "Flockhart Family WiFi",
  "PenguinNet" and anything containing "FS_".

### 3.7 COMMERCIAL / consumer gear

`data/targets/MicTargets.kt`. Families: `ECHO`, `RING`, `BLINK`, `GOOGLE`,
`SONOS`, `HIDDEN_CAM`, `GLASSES`. Every family but `HIDDEN_CAM` is also a *kind*
the ignore list can cover as a whole (§4.4); `HIDDEN_CAM` means "matched, but
nothing says what it is", and a rule hiding all of those would hide the ones
worth seeing.

Bluetooth SIG company IDs — every one re-verified against the SIG
assigned-numbers registry (4,035 entries) on 2026-09-21:

| ID | Vendor | Family |
|---|---|---|
| `0x00E0` | Google | GOOGLE |
| `0x0171` | Amazon.com Services | ECHO |
| `0x05A7` | **Sonos Inc** | SONOS |
| `0x01AB` | Meta Platforms, Inc. | GLASSES |
| `0x0D53` | Luxottica Group — Ray-Ban / Oakley Meta frames | GLASSES |
| `0x03C2` | Snapchat Inc — Spectacles | GLASSES |
| `0x060C` | Vuzix | GLASSES |

`0x058E` (Meta Platforms Technologies) was a GLASSES id until v0.6.0. It is what
Quest headsets send, and SquachWatch's two captures of real Ray-Ban Meta glasses
(2026-07 and 2026-10) sent `0x01AB` with the service UUID `0xFD5F` instead, so it
only ever labelled headsets "Smart glasses".

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
| `0000FD5F-…` (16-bit `FD5F`) | Meta Platforms Technologies — the one signature specific to Ray-Ban Meta glasses (SquachWatch's captures). Added in v0.6.0 | GLASSES |
| `7905FFF0-B5CE-4E99-A40F-4B1E122D00D0` | HeyCyan smart-glasses SDK primary service — Nilox Smart AI Glasses (sold by ALDI/Hofer) and other HeyCyan-based frames. Fixed on the software side, so it survives the randomised MACs and unstable names that defeat most glasses detection. | GLASSES |

Meta's other member UUIDs `0xFEB7` and `0xFEB8` and Snap's `0xFE45` (all SIG
registered) count as GLASSES too, but weigh like a company id (30), and only when
no company id matched: they are company-wide, not product-specific.

The three exact service UUIDs are also in the screen-off `ScanFilter` set (§5.1),
ahead of the company ids, because they are exact signatures and are what should
survive if the 16-slot list is trimmed. With COMMERCIAL and AIRCRAFT on it always
is: 3 Axon + 1 Remote ID + 1 XUNTONG + 6 Raven + 3 service UUIDs + 7 company ids
makes 21, so only Meta's `0x01AB` and Luxottica's `0x0D53` get a slot; Snap,
Vuzix, Amazon, Google and Sonos wait for the screen. Until v0.6.0 the five
glasses ids got slots and Axon none.

Name tokens: the case-sensitive substrings (`Spectacles`, `Ray-Ban`, `RayNeo`,
`Vuzix`, `XREAL`, `Rokid`, `echo-`, `FireTV`, `Doorbell`, `GoogleHome`,
`Google-Home`, `Chromecast`) and, matched case-insensitively against the
lowercased name, `rayban`, `ray-ban`, `ray ban`, `heycyan`.

Four short everyday words are matched more narrowly since v0.6.1. `Echo`,
`Amazon` and `Nest` count only as a word of their own (no letter or digit
before, no letter after: "Echo Dot", "Nest-1A2B", not "Nestor"), and `Ring` only
as such a word at the start of the name, because a "Galaxy Ring" is jewellery.
Through v0.6.0 they were plain substrings (`Ring-` and `Ring ` for Ring), so
"Nestor's Buds" was a Google Nest and an "AmazonBasics Mouse" an Echo.
The set-up SSIDs (`Amazon-`, `Echo-`, `Ring-`, `Ring_`, `Nest_`, `GoogleHome`,
`Chromecast`) are matched at the start of the SSID, in any case; matched
anywhere, `Ring-` sat in "Spring-Guest" and "Engineering-5G" and `Nest_` in
"Honest_WiFi". These examples were constructed to test the rule, not captured.

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

Ring's own blocks (v0.6.0, IEEE CSV 2026-11-01): the 13 MA-L registrations of
Ring LLC (`00:b4:63`, `18:7f:88`, `24:2b:d6`, `34:3e:a4`, `50:e4:67`, `54:e0:19`,
`5c:47:5e`, `64:9a:63`, `90:48:6c`, `9c:76:13`, `ac:9f:c3`, `c4:db:ad`,
`cc:3b:fb`) plus "Ring Solutions" `b0:09:da`, which sat in the Amazon set before;
all six "Blink by Amazon" blocks (`3c:a0:70`, `70:ad:43`, `74:13:48`, `74:ab:93`,
`c8:19:d8`, `f0:74:c1`) as a new BLINK family; and the glasses makers' Wi-Fi blocks,
Luxottica Group `98:59:49` and Snap, Inc. `98:a4:0e`.

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
or more methods agree on one device, +5 above −50 dBm, +8 for a pass (Bluetooth
only, §4.2).
Scores are **capped at 84** so a cluster of doorbells — or a passing pair of
Ray-Bans — can never read as ALPR-grade certainty. RED is reserved for
ALPR/Axon-grade evidence.

### 3.8 Axon and Utility — identifiers that survive a random MAC

`data/targets/AxonSignatures.kt`. From **all-cameras-are-beacons** ("ACAB",
github.com/soyboi1312/all-cameras-are-beacons, Apache-2.0): one observer's
field captures in Southern California, June–October 2026, counted in its
`firmware/lib/acab_core/axon_signatures.h` and `docs/signatures.md`; the
Utility remote from **nite-oui-collection** (nitekry, MIT). OVERWATCH takes
the identifiers, not the code. Checked in this order; the first that matches
names the device.

| Signature | Value | Evidence | Score |
|---|---|---|---|
| Body-cam tag | 128-bit service data (AD `0x21`) whose UUID reads `AXJANUSBWCDEVICE` once its little-endian bytes are reversed. Matched, as ACAB does, as ASCII `BWCDEVICE` in either byte order anywhere in the raw advert | ≥13 tagged MACs; five confirmed as body cams by eye at a San Diego street fair (2026-09-19). Blue2thprinting (Dark Mentor) logged the same advert by 2024-12-04, independently. Never seen on anything that isn't Axon | 90 |
| In-car tag | `METROPOLISDEVICE`, same scheme | 2 captures, leaning in-car | 80 |
| `0xFE6B` service data with a serial | TASER International's SIG member UUID, carrying ASCII serials (`D01A…` on body cams) | 7 devices, one confirmed by eye | 80 |
| `0xFE6C` service data with a serial | TASER International's other member UUID, the Axon Signal trigger layout | AxonCadabra's replay of a captured trigger; no real trigger has been captured. Serial `X87002FP4` is that tool and goes to HACKER (§3.10) | 80 |
| `0x034D` manufacturer data | TASER International's company ID, counted only as `0x02` + nine uppercase letters/digits (`X870054FW`). `X87…` is the Axon Signal Vehicle per Blue2thprinting's serial map, `X85…` the Fleet power unit | 1 capture, on `00:25:DF`. ACAB's off-block `0x034D` advert without that shape is why the shape is required | 80 |
| `0xFC81` / `0xFE6B` / `0xFE6C` listed, or carried as service data with no serial in it | Axon Enterprise's / TASER International's member UUIDs. A serial is a run of six or more uppercase letters and digits; through v0.6.0 any non-empty `0xFE6B` / `0xFE6C` payload scored as "with a serial" (80) | SIG registry (OUI-SPY AXEOFF keys on them) | 75 |
| `BodyWorn Remote XXXX` name | Utility's body-cam activation remote; XXXX is the last two bytes of its MAC | 10 MACs on Utility's `00:09:BC`, one observer, 2025-08 | 80 |
| MAC `00:25:df` | Axon Enterprise (IEEE) | 13 MACs in ACAB's serial logs | 80 |
| MAC `d8:1f:65` | IEEE lists the holder as **Private** | 10 MACs field-attributed to Axon, five confirmed by eye, and no non-Axon device among ~21,000 scanned. If one ever appears, this entry must come out | 80 |

The tag, `0xFE6B` and `0x034D` are in the screen-off filter set (§5.1), first,
so an Axon body cam is caught with the phone in a pocket. Until v0.6.0
OVERWATCH matched Axon only by `00:25:df`, which only works while the camera
uses its public address.

**Left out:** the SSID prefixes `AB2-` / `AB3-` / `AB4-` / `AXON-` that
SquachWatch lists (its README credits Gemini for them, and Axon's own docs say
its cameras' Wi-Fi is client-only); `E4:05:40` (unassigned) and `28:24:FF` (WNC,
a module maker), which SquachWatch files as Axon; the "Axon Networks" IEEE blocks,
an unrelated company.

### 3.9 Drones — Remote ID

`data/targets/RemoteId.kt`, `fusion/DroneTracker.kt`; filed under **AIRCRAFT**.
Layout from **opendroneid-core-c** and **receiver-android** (Apache-2.0), the
reference receiver for Android, which needs no root.

| Transport | Identifier | Android API |
|---|---|---|
| Bluetooth legacy | Service data for 16-bit `0xFFFA` ("ASTM Remote ID"): app code `0x0D`, a message counter, one 25-byte message. One message type per advert, so `DroneTracker` joins a transmitter's adverts | `ScanRecord.getServiceData()` |
| Bluetooth 5 long range | The same service data carrying a message pack (header `0xF_`, size `0x19`, up to 9 messages) | `ScanSettings.setLegacy(false)` and `setPhy(PHY_LE_ALL_SUPPORTED)`, set when the adapter reports extended advertising and the Coded PHY. Many phones receive long range unreliably |
| Wi-Fi beacon | Vendor-specific element 221, OUI `FA:0B:BC`, type `0x0D`, then a counter and a message pack | `ScanResult.getInformationElements()`, API 30+. Subject to the 4-scans-per-2-minutes throttle (§5.2) |

US drones comply under ASTM F3586, which accepts only Bluetooth (4 and 5
together) or Wi-Fi beacon; Wi-Fi NaN is not a US means, so it is not scanned.
**Police drones broadcast too:** state and local government aircraft register,
so Part 89 applies unless the FAA authorises a deviation (14 CFR 89.101, 89.105;
86 FR 4404), and the FAA's public-safety waiver FAQ (v9, 2025-09-02, Q25) says
drone-as-first-responder flights beyond line of sight need standard (built-in)
Remote ID, because add-on modules require line of sight (89.115).

Decoded: the Basic ID (serial when its ID type is 1, ANSI/CTA-2063-A) and the
Location message (latitude/longitude int32 × 10⁻⁷, geodetic altitude and height
each uint16 ÷ 2 − 1000 m, status). The System message also carries the
operator's or take-off position; OVERWATCH ignores it. The drone's position is
its own broadcast, put on the map as a violet dot; it is never logged and never
leaves the phone.

Since v0.6.1 the row reads "· 40 m up" from the **height** field (above take-off
or the ground), and "· alt 1650 m" from the altitude only when no height is sent.
Through v0.6.0 the altitude, which is measured from the WGS-84 ellipsoid, was
labelled "m up", so a drone parked in Denver would have read "1609 m up". Also
since v0.6.1 a drone is **one row**: it is keyed by its serial once it has sent
one, and the row it had under its transmitter's address until then is withdrawn.
The same drone heard over Bluetooth and Wi-Fi joins that row.

**Telling police from hobby drones: only the serial prefix works.** The first
four characters are the ICAO-assigned manufacturer code; the next is a length
character. Model prefixes were read off serials in the FAA's public Remote ID
Declarations of Compliance (`uasdoc.faa.gov`, November 2026):

| Prefix | Model | Tier | Score |
|---|---|---|---|
| `1914CR1D` / `1914CL2D` / any `1914` | BRINC Responder / LEMUR 2 / BRINC | police maker | 75 |
| `1668BR40`, `1668BR70` | Skydio X10, R10 | drone-as-first-responder | 65 |
| `1581F5BL`, `1581F5BM`, `1581F6QA`, `1581F6Q8`, `1581F8HH`, `1581F8HG` | DJI M30 / M30T Dock, M3D / M3TD Dock, Matrice 4D / 4TD Dock | drone-as-first-responder | 65 |
| `1668BE10`, `1581F5BK`, `1581F5FJ`, `1581F7K3`, `1748FEV3`, `1748FEV4`, `1588…`, `1839…`, `1867…`, `1898…`, `1701…` | Skydio X2E, DJI M30T / Mavic 3T / Matrice 4T, Autel EVO Max / Alpha, Parrot ANAFI, Teal, Hoverfly, Fotokite, Percepto | public safety / enterprise | 55 |
| anything else, or no serial | — | any drone | 45 |

A model is not an owner: utilities and security firms fly dock drones, which is
why the scores stop at 75 and the source is capped at 84. Remote ID is
unauthenticated, and ESP32 transmitters can broadcast any serial.

### 3.10 HACKER — attack and pentest gear

`data/targets/HackerTargets.kt`, `fusion/BleSpamDetector.kt`,
`scan/ClassicScanner.kt`. Gear that transmits *at* other radios. Every
identifier is from the tool's own source or vendor documentation (repo@commit
read 2026-11-01); several of those projects are GPL, so OVERWATCH uses the facts,
never the code. Scores add +5 above −50 dBm and are capped at **84**, like
COMMERCIAL.

| Signature | Value | Source | Score |
|---|---|---|---|
| Flipper Zero | Service UUID `0x3080` + case colour, i.e. `0x3080`–`0x3083` (0 = colour unknown, which SquachWatch misses) | flipperzero-firmware@9981d9d `serial_profile.c:54`, `furi_hal_version.h:34-39` | 60 |
| …its name | `Flipper <name>` (stock and Unleashed; Momentum drops the prefix) | `furi_hal_version.c:93-105`; Momentum `furi_hal_version.c:103-109` | +15 |
| …its address | `80:E1:26`, ST's company ID + the WB55 chip ID; public address, no manufacturer data. Not an IEEE block, so supporting evidence only. Wall of Flippers also checks `80:E1:27`, which nothing in the firmware explains | `furi_hal_version.c:107-116` | +10 |
| Flipper remote / BadUSB mode | HID service `0x1812` + `Control <name>` or `BadUSB <name>` + a `80:E1:26` address (all three: `0x1812` alone is any keyboard) | `hid_profile.c:409-426`, `bad_usb_hid.c:129-153` | 70 |
| nyanBOX | UUID `6e79616e-424f-582d-7365-727669636521` ("nyanBOX-service!") or name `nyanBOX-XXXX` | nyanBOX legacy source `nyanbox_advertiser.cpp:27-89` (MIT) | 60 |
| AxonCadabra | `0xFE6C` service data carrying serial `X87002FP4`: its replay of one captured Axon Signal trigger. Reported here and **not** as Axon, even from `00:25:DF` (the Flipper ports spoof that too) | AxonCadabra `MainActivity.kt:38-46` | 75 |
| iOS crash advert | Apple `0F 05 <flags> <action> <tag×3> 00 00 10 <len>` with `len` past the end of the payload. No real device sends a record that overruns its own advert | Momentum-Apps `ble_spam/protocols/continuity.c:406-425`; ESP32-Sour-Apple `sour-apple-esp32.ino:20-40` | 84 |
| AppleJuice templates | `0F 05 C1 <action> 60 4C 95 00 00 10 …` (a fixed authentication tag) or AirPods `07 19 07 <model> 20 75 AA 30 01 00 00 45 12 12 12` (fixed bytes in the encrypted field) | AppleJuice `applejuice.ino:36-65` | 84 |
| Pop-up spam flood | 40+ new addresses carrying pop-up adverts (Apple Proximity Pairing / Nearby Action, discoverable Fast Pair `0xFE2C`, Samsung `0x0075` buds/watch, Microsoft Swift Pair `0x0006`) within 10 s, after a 15 s warm-up at every scan (re)start. An address is "new" until 2 minutes after it was **last** heard; through v0.6.0 the 2 minutes ran from its first sighting, so every device that simply stayed in range was counted as new again (40 real AirPods in one train carriage would have raised a flood every two minutes) | Momentum-Apps `ble_spam`; Bruce `ble_spam.cpp`; Mayhem `ui_blespam.cpp` | 84 |
| One transmitter cycling devices | 4+ different Proximity Pairing models or Nearby Actions from one address within 10 s. Flipper's Apple modes keep one address per run; real AirPods have one model | `ble_spam.c:43-69, 232` | 84 |
| Default access points | `Pineapple_…` (WiFi Pineapple setup network; it disappears after setup), `pwned…` (ESP deauther), `BruceNet` / `BruceShell` / `BRUCE-UDP` (Bruce), `GhostNet` (GhostESP), `blackmagic` (Flipper Wi-Fi dev board), `O.MG`, `Nugget AP`, `wifiduck`, `Exploit` (ESPloitV2 / WHID Cactus), `Evil Crow RF v2`, P4wnP1's emoji SSID. All renamable | Hak5 docs; esp8266_deauther; Bruce `config.h:64`; GhostESP `settings_manager.c:220-221`; blackmagic-esp32-s2 `nvs-config.c:14-15`; o.mg.lol; USB-Nugget; WiFiDuck; ESPloitV2; EvilCrowRF-V2; P4wnP1 `defaults.go:19-38` | 55 |
| Evil-portal defaults | `Evil Portal`, `EvilPortal`, `EvilAP`, `NEMO Free WiFi`, `Evil-…` (Evil-M5Project, including `Evil‑NanoC6` with U+2011) | flipper-zero-evil-portal; GhostESP; m5stick-nemo; Evil-M5Project | 50 |
| Rogue-AP default BSSIDs | `00:00:00:xx:xx:xx` (wifiphisher without `-iNM`/`-iAM`; not the all-zero `00:00:00:00:00:00`, which is how a missing address is written, since v0.6.1), `00:11:22:33:44:00` (eaphammer), `BC:F6:85:03:36:5B` (WiFi Pumpkin 3) | wifiphisher `interfaces.py:932-943`; eaphammer `hostapd.ini:25-27`; wifipumpkin3 `config.ini:59-61` | 70 |
| Beacon spam | In one scan, 3+ of: Rick-roll SSIDs, an RSN element listing one cipher twice (Android shows `[WPA2-PSK-CCMP+CCMP]`), or BSSIDs with the multicast bit set | esp8266_deauther `Attack.h:194-208`; Marauder `WiFiScan.cpp:9666-9714`; AOSP `InformationElementUtil` | 75 |
| Many names, one BSSID | One BSSID advertising 5+ network names in one scan (Known Beacons, Karma). Untested on Android, so it may never fire | wifiphisher `knownbeacons.py` | 70 |
| ESP32 Marauder wardrive decoy | A network named exactly `Flock` from an Espressif address (`EspressifOuis.kt`: all 374 Espressif MA-L blocks, IEEE CSV 2026-11-01). Marauder beacons it with camera-style elements; before v0.6.0 OVERWATCH would have scored it about 100 as a Flock camera | Marauder `WiFiScan.cpp` ~6459 | 55 |
| Card skimmer | Bluetooth Classic name `HC-03`, `HC-05`, `HC-06`, `RN42`, `BT04-A` or `RNBT-…`, the serial modules gas-pump skimmers are built from. **Opt-in** (Settings → Look for card skimmers): a Classic discovery every 3 minutes, which pages modules that answer without a name | SparkFun Skimmer Scanner (MIT); ESP32 Marauder's skimmer detector | 65 |

**Not detectable on a phone:** the Pwnagotchi (its peer beacons carry no SSID
element, and Android's `wificond` drops such networks before any app sees them,
AOSP `scan_utils.cpp:155-160`), and deauth floods and the Pwnagotchi's spoofers,
which need monitor mode. **Deliberately not matched:** Flipper Devices' IEEE block
`0C:FA:22`, company ID `0x0E29` and UUID `0x308A`, which are all the BUSY Bar desk
sign (SquachWatch's bench test, 2026-10-14); Hak5 "OUIs" (Hak5 holds no IEEE
registration); bare Espressif addresses, which every ESP32 gadget carries;
generic portal names such as `Free Wifi`; the receive-only and wired tools
(HackRF, Ubertooth, Proxmark, Bash Bunny, Key Croc, Shark Jack, Packet
Squirrel); and the Pineapple Pager, which has no published signature.

### 3.11 TRACKER — item trackers away from their owner

`data/targets/TrackerTargets.kt`, `fusion/TrackerDwell.kt`. Rules from **AirGuard**
(seemoo-lab, Apache-2.0, v3.1.3), the open-source Android tracker detector, and
the IETF DULT draft. A tag beside its owner's phone is somebody's keys; one
separated from its owner and near you is the case that matters.

| Tracker | Rule | State |
|---|---|---|
| AirTag / Find My accessory | Apple `0x004C`, type `0x12` length `0x19` (separated; `0x02` is near the owner); status bits 4–5 = 1 AirTag, 2 Find My accessory (second-generation AirTags report this). 0 (an Apple device) and 3 (AirPods) are skipped | separated |
| Samsung SmartTag | Service data `0xFD5A`, first byte & `0xF8` = `0x10`; low three bits 1 just separated, 2 lost, 3 lost over 24 h | separated |
| DULT | Service data `0xFCB2` (Apple's member UUID, used for the cross-vendor standard): network-ID byte, then a byte whose low bit is 0 when separated (draft-ietf-dult-accessory-protocol-01 §3.4.2, §3.9) | separated |
| Google Find Hub | Service data `0xFEAA` with frame type `0x41` (unwanted-tracking-protection mode) or `0x40` (doesn't say). Eddystone's own frames (`0x00`–`0x30`) never count | separated / unknown |
| Chipolo | `0xFE33`; bit 0 of the second service-data byte is 1 after 30 minutes away from the owner, on firmware whose address starts `D8` or lower; newer firmware doesn't say | separated / unknown |
| Tile, Pebblebee | `0xFEED` / `0xFEEC` / `0xFD84`; `0xFA25`. No state field | unknown |

Scores: separated 45, unknown 30, +25 once in range without a break (gaps under
2 minutes) for 10 minutes; capped at 84. So a separated tag shows YELLOW at once
and ORANGE after 10 minutes, and a Tile shows only after 10 minutes. A tag that
rotates its address starts its 10 minutes again, which can delay an alert but
never invent one. **Not matched:** Samsung `0xFD69` (Galaxy phones and earbuds in
lost mode) and `0xFD59` (unregistered-tag onboarding).

**The stay is counted across screen-off (v0.6.1).** The screen-off scan carries
no tracker filter (§5.1), so with the phone in a pocket no tag is heard whether
it is there or not. Through v0.6.0 that silence counted as the tag leaving: any
pocket spell over 2 minutes started every count again, so a phone that was
mostly in a pocket, which is when a planted tag matters, never reached the
10-minute alert. Now the time the scanner could not hear trackers (screen off,
Bluetooth off, no scan running) is left out of the gap. A tag heard before the
phone went into the pocket and again when it came out is counted as there
throughout; one first heard after the spell starts from zero. The cost is the
opposite error: a tag that went away and came back within one pocket spell is
counted as having stayed. The alert is still raised only while the screen is
on, because that is the only time a tracker can be heard.

Each make in the table is a *kind* for the ignore list (§4.4): tags rotate their
address (an AirTag away from its owner about once a day), so a rule for one
address soon stops matching.

Android withholds iBeacon and Eddystone adverts from apps that declare
`neverForLocation` on `BLUETOOTH_SCAN`, as OVERWATCH does, but its default
deny-list exempts exactly Find Hub's frame types (AOSP `AdapterService`
`DEFAULT_LOCATION_DENYLIST_ADVERTISING_DATA` =
`⊈0016AAFE40/00FFFFFFF0,⊆0016AAFE/00FFFFFF,⊆00FF4C0002/00FFFFFFFF`), so they
arrive. Android's own unknown-tracker alerts (Play services) already cover AirTags
and Find Hub tags; SmartTag, Tile, older Chipolo and Pebblebee are what this adds
there, and on a phone without Play services it is the only check.

### 3.12 Considered and left out (v0.6.0 research)

Checked against the IEEE and Bluetooth SIG registries and the projects' own
field evidence, and rejected:

- **Motorola Solutions `0xFE04`** — its only field sightings were seven Openpath
  door readers (`oprpx…`, ACAB). **`0xFD8E`, `0x04EC`** (Motorola), **`0x0D5B`**
  (Axis), **`0x0AE9`** (FLIR), **`0xFC2C`** (Verkada): registered, never captured.
- **Verkada `0xFD3A`** — sent by phones running the Verkada Pass app.
- **Cradlepoint and Sierra Wireless routers** — in police cars, but ACAB logged
  154 Cradlepoint BSSIDs in four hours of ordinary driving. Agency-renamed SSIDs
  (`OPD 3484`) admit no rule.
- **Eye Spy's prefixes** — "Vigilant" `00:0E:58` is Sonos, "Flock" `D4:BB:E6` is
  Huawei and `3C:61:05` is Espressif.
- **nite-oui-collection's law-enforcement CSV** — 11 of its 15 OUIs belong to
  Honeywell, Nisca, Nintendo, Apple, Dell, Xiaomi, Samsung, GM or Axis.
- **`Pigvision`** (§3.6), **`Flock_Setup`** (no source anywhere) and the
  Samsung and Espressif blocks in the Flock lists (§3.1, §3.2).
- **Cellebrite `00:16:00`** — the same company's phone-transfer kiosks sit in
  carrier stores. **Harris Corporation's** old blocks predate its radio line.
- **LiveView Technologies surveillance trailers** emitted nothing at 2 m.

---

## 4. Scoring

Score 0–100 per observation; the on-screen tier is the **maximum live score**
across all sources. Radio events (BLE, WiFi, Commercial, Hacker, Tracker, drones)
expire 5 minutes after they were last heard. The camera map and Waze reports sit at fixed,
known positions, so they are re-scored at every fix and drop out as soon as they
are out of range (§2.1, §2.2); until v0.5.20 they too stayed up to 5 minutes,
with the distance they had when last in range. ADS-B aircraft are replaced as a
set at every poll since v0.6.1 (§2.3), and age out after 5 minutes only when the
polls fail.

**Peak hold (v0.6.1).** A radio device is re-scored at every advert, several
times a second, and the strong-signal bonus switches at −50 dBm. A device a few
dB either side of that line took the circle from RED to ORANGE and back at that
rate. Each device's highest score is now held for 10 s after it was last
reached; the row keeps the latest advert's label and signal strength. Sets that
are replaced whole (camera map, Waze, ADS-B) are not held, so a distance-driven
score always follows the latest fix.

**Vibration (v0.6.1).** A rise in the tier vibrates, unless the detection behind
it already vibrated for that tier or a higher one in the last 60 s
(`fusion/AlertGate.kt`). A different detection reaching the tier, or the same
one going higher than it has, always vibrates. Through v0.6.0 every upward
crossing vibrated, so one camera a few metres either side of a distance band, or
one device on a score boundary, buzzed again and again with nothing new to say.
The notification is likewise posted only when its words change, and at most once
a second; it used to be re-posted on every store update. Its level and its top
detection are both taken from one reading of the list, so the pair that waits
out that second is never half old and half new.

Both numbers (10 s, 60 s) are choices, not measurements.

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
- **Aircraft LADD** — +4. The operator asked public trackers to hide it. Only
  registry aircraft carry the flag, so it never meets the cap below; since
  v0.6.1 the code adds it before the cap all the same, where through v0.6.0 it
  was added after.
- **Unidentified aircraft cap** — 69, the top of YELLOW. Behaviour-only evidence
  never reaches ORANGE, let alone RED; circling could still be news or survey
  work.
- **Radio base weights.** BLE: vendor block 40 (Axon 80, police-exclusive vendor
  75), Flock module prefix 25, Axon body-cam tag 90, Axon SIG number with a serial,
  METROPOLIS or BodyWorn name 80, an Axon SIG UUID listed alone 75, name 45
  (Penguin numeric 15), XUNTONG maker id 60 (+20 with a `TN` serial), Raven 70 (90
  for three or more counted UUIDs). WiFi: vendor block 40 (Axon 80,
  police-exclusive 75), module prefix 25, Flock hotspot format 65 (+15 when it ends
  in the BSSID), Flock Camera hotspot 65, generic SSID 50. Methods on one device add
  up, capped at 100. HACKER (§3.10), TRACKER (§3.11) and drones (§3.9) have their
  own tables and are capped at 84.
- **Multi-method bonus** — +20 when two independent BLE/WiFi methods agree.
- **Strong RSSI** (> −50 dBm) +10; **pass** (rise-peak-fall, the code's
  `stationary`, Bluetooth only) +15. COMMERCIAL uses +10, +5 and +8 (§3.7).
- **What counts as a pass (v0.6.1)** — `fusion/RssiTracker.kt`. The signal of a
  fixed transmitter rises, peaks and falls as you go by; a device you carry does
  not do that. Readings are averaged into 1 s slots, the last 60 slots are cut
  into thirds, and it is a pass when the middle third averages at least 8 dB
  over the first third **and** over the last. It needs 6 slots and 3 readings in
  each third; a device unheard for 90 s starts again.
  The rule through v0.6.0 was flock-detection's, ported unchanged: among the
  last 15 raw readings, the strongest is neither first nor last and stands 6 dB
  over the weaker of those two. Android delivers every advert, several a second,
  so 15 readings covered a few seconds, and in simulation ordinary 2–4 dB jitter
  passed that test on 28–74% of checks with nobody moving. The +15 then lifted a bare module prefix (25) over the display floor on
  noise alone.
  **The new numbers are simulated, not measured** (log-distance path loss, 3 dB
  jitter, 3–5 dB slow fading): standing still for ten minutes reads as a pass in
  under 10% of runs at worst; walking past within 5 m in all of them, at 10 m
  in about three in four; driving past at 13 m/s within 15 m in over nine in
  ten. Faster passes give too few slots: at highway speed a roadside device is
  in range for only a few seconds. A signal that really swells and fades by
  8 dB while nobody moves (a body turning between phone and device) still reads
  as a pass; signal strength alone cannot tell those apart.
  **Wi-Fi has no pass bonus since v0.6.1.** A scan every 35 s gives three
  readings in a minute and a half, which is three numbers, not a shape.
- **Display floor** — a BLE, WiFi or COMMERCIAL hit scoring under 40 is dropped:
  it is never shown and never counts toward the tier (`ALARM_THRESHOLD` in
  `BleScanner` and `WifiScanner`).
  Most single methods clear it alone. Three don't. A Flock module prefix (25)
  shows on Bluetooth only with a pass (25 + 15), and on Wi-Fi never alone
  (25 + 10). A Penguin numeric name (15) needs both a strong signal and a pass
  (15 + 10 + 15), and so does a lone COMMERCIAL OUI or company id on Bluetooth
  (30 + 5 + 8); on Wi-Fi a lone COMMERCIAL OUI (30 + 5) never shows. So glasses
  that advertise only Meta's or Luxottica's company id show only very close, as
  you pass them; a matching name, the HeyCyan service UUID, or Ray-Ban Meta's `0xFD5F`
  (50) shows at any range the radio reaches.

### 4.3 Loiter detection

An aircraft is "loitering" when, over the last 15 minutes of tracked history:
at least **3 samples** spanning at least **4 minutes**, every one within
**5 km** of their own centroid, with the latest report at or below **12,000 ft**
and **200 kt**. A contact reporting `ground` altitude, or no speed, does not count
as low or slow.

A registry hit is reported wherever it is, within 15 km. An *unregistered*
aircraft is reported only when it is both loitering **and** within 8 km, so
ordinary traffic passing overhead never raises an alert.

### 4.4 The ignore list

`fusion/IgnoreRule.kt`, `data/settings/IgnoreList.kt`; added in v0.6.1. The
user's own gear (their glasses, their doorbell, the camera on their own street)
is a detection every time they come home. Each row in a source's list has a
crossed-out bell; rules are listed under **Settings → Ignored**, each with a
Remove button.

A rule belongs to one source and takes one of two forms:

| Form | Matches | Offered for |
|---|---|---|
| This one | The row's key: a Bluetooth address, a BSSID, a camera's OSM node id, an aircraft's ICAO hex, a drone's serial | every row that can be ignored |
| Every one of a kind | Every detection of that kind in that source | COMMERCIAL families except "possible hidden mic / cam" (§3.7), and TRACKER makes (§3.11) |

- **Applied where detections enter the store**, so an ignored one is never
  stored: it cannot raise the tier, vibrate, reach the notification or the
  bubble, or show in a list. A rule added during a scan removes its detections
  at once; a removed rule lets them back at the next sighting. An ignored camera
  stays on the map as a dot, since the map draws the camera data, not the alerts.
- **One source per rule.** The same doorbell heard over Bluetooth and over Wi-Fi
  is two rows and takes two rules.
- **A "this one" rule is only as stable as the address.** Trackers rotate theirs,
  and so do many phones, earbuds and wearables. When the address changes, the
  device alerts again; that is what "every one" is for, at the price of hiding
  other people's devices of that kind too. Whether Ray-Ban Meta glasses keep one
  address has not been tested on a real pair.
- **What cannot be ignored.** A Waze report: it is gone within the hour and never
  returns under the same id, so a rule would sit in the list doing nothing. A
  HACKER finding with no transmitter behind it (a pop-up flood, beacon spam, a
  crash advert): that is an attack under way, not a device somebody owns, and
  the HACKER switch turns those off.
- **Nothing a stranger broadcasts is trusted in the list.** A rule's label is the
  row's text, which can include a name the device chose. Control characters are
  removed and it is cut to 120 characters; the distance, altitude and "how long"
  parts are dropped so it still reads right next week.
- **Stored encrypted, on the phone only** (`SecureStore`, entry `ignore_list`):
  the addresses of your own gear and the ids of the cameras on your own street
  say between them whose phone this is and where it lives (§5.3).

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
OUI-prefix matching is one of OVERWATCH's main BLE methods (Axon's two blocks,
the 21 Flock module prefixes, the 37 vendor blocks). There is no filter that means "any MAC
starting with these three octets", and no filter that means "any device".

So the scanner switches strategy on `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF`:

| Screen | Scan | Covers | Loses |
|---|---|---|---|
| on | unfiltered | everything — OUI, name, UUID, manufacturer | — |
| off | filtered (≤16) | In order: Axon's body-cam tag, `0xFE6B` service data and `0x034D`; drone Remote ID (`0xFFFA` + `0x0D`, when AIRCRAFT is on); XUNTONG; Raven's five UUIDs and `1819`; then the Ray-Ban Meta, HeyCyan and Echo service UUIDs and the COMMERCIAL company ids as slots allow (§3.7) | OUI prefixes, name substrings, HACKER and TRACKER matching |

Filter slots are a hardware resource and chipsets differ; a common allocation is
16. Overflow is meant to fall back to software filtering, but since a scan that
silently returns nothing is this app's worst failure mode, the list is capped at
16 with surveillance signatures ordered ahead of consumer ones. Flock ALPR
coverage is unaffected in that window because the map source does not depend on
the radio. The screen-off scan has no tracker filter either; how the ten-minute
tracker rule counts across that gap is in §3.11.

Through v0.6.0 this section said the BLE row "states the reduced mode while the
screen is off". It did not. The scanner wrote a note for it ("Screen off —
filtered scan…"), but a row drew a message only for a source that had failed,
so the note was never on screen, and nobody reads a row with the screen off
anyway. Since v0.6.1 a row shows a note under a source that is up, and the BLE
row carries one in the single case where the reduced scan can be seen: it has
outlasted the screen coming back on, which happens when a change of mode is
held (§5.2).

**Off is followed at once, on after 2 s (v0.6.1).** Changing mode means
stopping one scan and starting another, and Android rations starts (§5.2). A
screen that wakes for a notification and sleeps again is two changes in a few
seconds. So when the screen comes on the scanner waits until it has stayed on
for 2 s, and then switches only if the mode it is in is the wrong one: a brief
wake costs no start. Going off is not waited for. The full scan delivers
nothing from that moment, and the phone may be asleep before a delayed step
would run, so that switch is made inside the screen-off broadcast. Through
v0.6.0 every screen event, on or off, restarted the scan at once. Seen on the
API 36 emulator: screen off to the filtered scan running in under a second,
screen on to the full scan in 2.2 s, and no start at all for a wake of under a
second.

**The scanner follows the Bluetooth switch (v0.6.1).** With Bluetooth off,
`startScan` and `stopScan` do not fail, they throw (`IllegalStateException: BT
Adapter is not turned ON`). Through v0.6.0 nothing caught that: with Bluetooth
off, the next screen change or STOP crashed the app (reproduced on an API 36
emulator). And nothing listened for Bluetooth coming back, so the scan it took
down stayed down until the next screen change, with the BLE row reading OK.
Now the scanner follows the adapter's state. Off puts "Bluetooth is off — turn
it on to scan" on the BLE row; on starts the scan again. A start that fails for
another reason is retried after 5, 10, 20, 40 and then every 60 s, and the row
says when. Before Android 12, Bluetooth results also need Location switched on;
the row says that too and the scan resumes when Location is back. Those last
two paths were not exercised: the emulator runs API 36 and its scans never
failed.

### 5.2 Other limits worth knowing

- **BLE start-rate limit** — Android refuses a scan when the app's last five
  finished scans all began within the last 30 s (`AppScanStats.isScanningTooFrequently`;
  defaults 5 and 30 s, which a vendor can change; read in AOSP main 2026-11-12).
  The refusal is silent. The framework's own comment is "don't report anything
  to the app", so the scan *appears* to start and delivers nothing. Each change
  of scan mode is a start, so three wake-and-sleep cycles inside 30 s get there:
  "screen transitions are rare enough to stay clear of it", this document's
  claim through v0.6.0, was wrong. Since v0.6.1 every start goes through
  `scan/ScanStartBudget.kt`: at most 4 in any 31 s, counted for the whole
  process so that a restarted service does not begin from nothing. A start over
  budget is held and the running scan stays as it is meanwhile. With no scan
  running the BLE row reads "Starting in Ns — Android limits how often a scan
  can start". With the reduced scan still running after the screen has come
  back on, the row stays up and carries the note "Full scan in Ns — Android
  limits how often a scan can start": for those seconds prefixes, names, HACKER
  and TRACKER are not matched. With the full scan still running after the
  screen has gone off, nothing is heard, and the tracker clock (§3.11) is
  stopped until the change is made. Seen on the API 36 emulator by switching
  the screen on and off: four starts, the fifth held for 21 s, the note on the
  row and gone again afterwards. The refusal itself was never provoked on a
  device; the budget is there so that it cannot be. The count lives in memory:
  a process that Android kills and restarts begins it again, and what catches a
  refused start then is the next bullet's two-minute rule.
- **BLE scan time limit** — a scan left running is not left alone either. When
  one has run for the stack's limit, 30 minutes from Android 8.1 to 13 and 10
  minutes from Android 14, `ScanManager.regularScanTimeout` sets it aside. A
  scan with no filter becomes "opportunistic": it hears only while some other
  app is scanning. A filtered scan was exempt up to Android 13; from 14 it is
  turned down to `SCAN_MODE_LOW_POWER`, which listens for 140 ms in every
  1400 where OVERWATCH's low-latency scan listens throughout. The app is told
  neither, and a foreground service is not exempt. Through v0.6.0 that meant
  the scan a pocketed phone depends on was at a tenth of its listening time
  from the tenth minute until the next screen change, and a phone left on a
  dashboard with the screen lit was hearing only what other apps' scans brought
  in, with the BLE row reading OK both times. A scan that is stopped and
  started gets its full time again, so since v0.6.1 the scanner looks at its
  scan every 30 s and starts it over when it is 8 minutes old. It also starts
  the full scan over when the screen is on and nothing at all has come from it
  for 2 minutes, which is what a refused start, or a scan set aside sooner
  than expected, looks like from the app. Each of these is one start against
  the budget above. Both the stack's timer and the 30 s look stop counting
  while the phone sleeps, and the 8 minutes are measured on a clock that does
  not, so the scanner's restart can come early and never late. Read in AOSP
  2026-11-13: `regularScanTimeout` in android-8.1.0_r1, 11.0.0_r1, 13.0.0_r1,
  14.0.0_r1 and main, and the 10 minutes also in 15.0.0_r1
  (`DEFAULT_SCAN_TIMEOUT_MILLIS`, a device-config value, `scan_timeout_millis`,
  that a vendor can change). The set-aside itself was never provoked on a
  device, and what a vendor's stack does was not looked at. What was seen, on
  the API 36 emulator, is the scanner's side: with nothing to hear, the full
  scan started over 120 s after it began, and with the screen off for nine
  minutes the filtered scan was started over once, at 8 minutes, the stack's
  own count of the app's scans going up by one.
- **WiFi scan throttling** — since Android 9, foreground apps get 4 scans per
  2 minutes (background: 1 per 30 min). OVERWATCH polls every 35 s ≈ 3.4 per
  2 min, deliberately just under.
- **`WifiManager.startScan()` is deprecated** and Google has stated the ability
  for apps to trigger scans will be removed in a future release. The scanner
  already treats it as best-effort: it registers for
  `SCAN_RESULTS_AVAILABLE_ACTION` and reads whatever the system last scanned, so
  when the trigger stops working the source degrades to system-paced results
  rather than failing.
- **A Wi-Fi scan that cannot hear says so (v0.6.1).** With Wi-Fi off (and the
  system's "Wi-Fi scanning" setting off too) or Location off, Android returns an
  empty or old list and no error. Through v0.6.0 the WIFI row was set once at
  Start and Location was never checked. It is now set every round: "WiFi is off
  — turn it on to scan", "Location is off — Android gives no WiFi results
  without it", or, after three scan requests refused in a row, "Android is
  refusing WiFi scans — only what the system itself scans is seen". The row
  clears by itself when the cause does.
- **Promiscuous-mode tricks are not portable.** flock-you's `addr1` and
  wildcard-probe techniques need monitor mode; a userspace Android app sees only
  what `WifiManager` surfaces, which is BSSID and SSID.
- **Extended and long-range advertising** is delivered only to a scan built with
  `setLegacy(false)`; since v0.6.0 the BLE scan sets it, and `PHY_LE_ALL_SUPPORTED`,
  when the adapter reports extended advertising and the Coded PHY, so drones'
  Bluetooth 5 Remote ID can arrive. Legacy adverts are still delivered.
- **Wi-Fi information elements** (`ScanResult.getInformationElements()`) are public
  from API 30. Drones' Wi-Fi Remote ID rides in one; on Android 8–10 it is not read.
- **`neverForLocation` has a price.** OVERWATCH declares it on `BLUETOOTH_SCAN`, so
  Android withholds adverts matching its location deny-list: iBeacons and Eddystone
  beacons. The default list exempts Eddystone frame types `0x40`–`0x4F`, which is
  what lets Find Hub trackers through (§3.11).
- **Bluetooth Classic discovery** (the opt-in skimmer check) runs about 12 s every 3
  minutes; on many phones it pauses BLE results while it runs. Classic has no
  address randomisation, and devices that answer without their name get a name
  request, which shows them the phone's Bluetooth address. Since v0.6.1 the
  switch is read before each discovery, so turning the check or HACKER off
  stops the transmitting at once (through v0.6.0 it only stopped the listening
  until the next Start); Bluetooth being off skips a round where it used to
  cancel the check for the whole scan; and stopping cancels only a discovery
  OVERWATCH began itself in the last 15 s, not one the system's Bluetooth page
  is running to pair somebody's headphones.
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
| Android's log (logcat) | nothing positional and no account details: no coordinates, cell names, request URLs or Waze account name. v0.5.17 and earlier logged the exact fix on every camera-map fetch. v0.5.18 still quoted a malformed reply from Overpass, the aircraft feeds or OpenWeb Ninja whole (Android's org.json puts its entire input in a syntax error, so: the positions of the cameras, aircraft or police around you), and logged the direct backend's account name; since v0.5.19 those lines carry the error's class only. Since v0.6.1 the direct backend no longer logs the text of an error Waze's server sent, which is the server's to word | not the app's to delete: it lives in RAM, rotates, and clears on reboot. One gap remains outside the app's code: osmdroid logs a tile's number when that tile fails to download |

Tiles stay by default because OpenStreetMap's tile usage policy asks apps to
cache tiles rather than re-download them. They live in one SQLite database that
every MapView shares through osmdroid's static connection, and a MapView can
still be attached when scanning stops. So the file is never deleted out from
under it. Tiles are purged through that same connection, then `VACUUM` rewrites
the file, so deleted rows don't linger in free pages.

**The ignore list (v0.6.1) is the one record meant to stay.** It is kept until
its rules are removed, across stops and restarts, and it holds what was ignored:
device addresses, camera node ids, aircraft hex codes, and the label each row
showed (§4.4). It is stored the way the Waze key is: AES-GCM under a key in the
Android Keystore, in app-private preferences (`data/settings/SecureStore.kt`).
The app is excluded from Android's backups (`allowBackup="false"`, empty
extraction rules), so the list does not follow you to a new phone. Removing the
last rule removes the entry.

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

None of the radio sources sends anything online: drones' positions, tracker
states and hacker-gear names stay on the phone. The one radio action that shows
nearby devices more than a scan request does is the opt-in skimmer check (§5.2).

Before v0.5.18 the ADS-B feeds got the fix itself (~11 m), and the Overpass and
OpenWeb Ninja boxes were centred exactly on it; before v0.5.16 Waze direct got it
too. The location fix itself comes from Google Play services' fused provider;
whether that consults Google's servers depends on the phone's location settings,
not on OVERWATCH.

Two things about that fix changed in v0.6.1. **A scan starts from a cached fix
only if it is at most 2 minutes old**; otherwise the online sources wait for
the first live one, usually seconds. Through v0.6.0 any last-known fix would
do. It can be hours old and a city away, and the first polls go out the moment
any fix exists: Overpass, the ADS-B feeds and Waze were asked about the place
the phone last had a fix, and the paid Waze feed spent its one request there
and showed nothing for the 4 minutes until its next.

**The scan needs precise location**, as it always has: Android gives no Wi-Fi
results without it, and "Approximate" is a position coarsened to an area of
about 3 km² (Android's documentation), which every distance in §4.1 would then
be measured from. What changed is the asking. Precise and approximate are now
requested together, and someone who picks "Approximate" is told what is wrong:
"OVERWATCH needs precise location — tap START and choose Precise", or, once
Android has stopped offering the choice, "Location is set to approximate — open
app settings and turn on precise location". Through v0.6.0 precise location was
asked for alone, and "Approximate" was taken as a refusal for good: the app said
"Permissions permanently denied — open app settings to grant", and START opened
the settings page (the v0.6.0 release APK on the API 36 emulator, 2026-11-13).
Android's documentation says a request for precise location alone is ignored
from Android 12, and the Android 14 permission controller's source drops such a
request without a dialog; on the API 36 emulator the dialog, with its choice of
Precise or Approximate, appeared all the same. Asking for both is right either
way.

Which of the two messages applies cannot be asked of Android in the usual way.
After "Approximate" is chosen, `shouldShowRequestPermissionRationale` answers
false for precise location, the same answer it gives after "don't ask again",
although the next request does show a dialog. (On the API 36 emulator the
precise permission carries no user-set flag at that point; the approximate one
does.) An app that trusts the answer sends the user to its settings page one
step early, as v0.6.0 did. Now an approximate
grant that has just been made counts as "can still ask", and one that survives
a second request counts as final. Seen on the API 36 emulator, from a reset
permission state: "Approximate" in the first dialog gives the first message;
START then brings up Android's "Change OVERWATCH's location access from
approximate to precise?"; "Change to precise location" starts the scan, and
"Keep approximate location" gives the second message, after which no dialog is
shown again. Precise is what the app measures with. What each online source is
sent is still the table above.

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

**What the emulator is good for, and not.** It hears no Bluetooth advertisers,
so nothing radio-driven can be seen end to end on it: no BLE detection, no
pass bonus, no tracker stay, no vibration on a device whose score flaps. What it
does show is the scanners' lifecycle under a running scan:

```sh
adb shell svc bluetooth disable|enable            # the BLE row, and the v0.6.0 crash
adb shell svc wifi disable|enable                 # the WIFI row
adb shell cmd location set-location-enabled false|true
adb shell input keyevent KEYCODE_SLEEP|KEYCODE_WAKEUP    # scan mode changes
adb shell appops set org.soulstone.overwatch SYSTEM_ALERT_WINDOW deny|allow
adb shell dumpsys bluetooth_manager               # per app: scans started, and the last few
adb logcat -s BleScanner                          # one line per scan start, and per start held
```

`dumpsys bluetooth_manager` is how to count scan starts against the limit in
§5.2. Because nothing is ever heard, the full scan is started over every 2
minutes there (the second rule under "BLE scan time limit" in §5.2), which is
that rule seen working and nothing to worry about. The first-run permission
dialogs can be replayed without reinstalling:

```sh
P=org.soulstone.overwatch
adb shell am force-stop $P
for perm in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do
  adb shell pm revoke $P android.permission.$perm
  adb shell pm clear-permission-flags $P android.permission.$perm user-set user-fixed
done
```

Scoring,
parsing, the ignore rules, the pass rule and the alert gate have no Android in
them and were tested on the JVM with the app's compiled classes; those tests are
not in the repository yet.

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
- **v0.6.0's signature research** (identifiers only; none of this code is in
  OVERWATCH): **all-cameras-are-beacons** (soyboi1312, Apache-2.0) — Axon and
  Flock field captures, and the false-positive work behind dropping Espressif;
  **nite-oui-collection** (nitekry, MIT) — the BodyWorn remote;
  **Blue2thprinting** (Dark Mentor, proprietary; facts only) — independent Axon
  sighting and the X85/X87 serial map; **flock-you** (MIT) — its WiGLE datasets
  and README; **opendroneid-core-c** and **receiver-android** (Apache-2.0) — Remote
  ID; the FAA's public Remote ID declarations — drone serial prefixes;
  **AirGuard** (seemoo-lab, Apache-2.0) and the IETF DULT draft — trackers;
  **flipperzero-firmware**, **Momentum** (GPL-3.0), **Wall of Flippers** (MIT),
  **nyanBOX** (MIT), **ESP32 Marauder** (MIT), **AppleJuice** (Apache-2.0),
  **ESP32-Sour-Apple**, **Momentum-Apps ble_spam**, **Bruce** (AGPL-3.0),
  **esp8266_deauther** and **WiFiDuck** (MIT), **wifiphisher**, **eaphammer**,
  **wifipumpkin3** and the other tools in §3.10 — HACKER; **SquachWatch-CYD**
  (GPL-3.0) — the comparison that started it; the IEEE MA-L/MA-M/MA-S CSVs and
  the Bluetooth SIG assigned numbers (2026-11-01) for every block and number.
- **highway-radar-sabre-plus** (MIT) — the Waze RT protocol layer. The only
  reference that is *vendored* rather than studied: `scan/wazert/*.java` (apart
  from `WazeRtFetcher.java`, OVERWATCH's own driver modelled on upstream's) and
  `proto/waze.proto` are that project's code, carried with its licence.
