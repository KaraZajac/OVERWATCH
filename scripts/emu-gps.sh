#!/bin/bash
# Feed a location to an Android emulator, for testing OVERWATCH's
# location-driven sources (DeFlock, Waze).
#
#   scripts/emu-gps.sh <lat> <lon> [serial] [seconds]
#   scripts/emu-gps.sh 38.8324 -77.1062              # Springfield VA, 120s
#   scripts/emu-gps.sh 40.7128 -74.0060 emulator-5558 300
#
# Why not `adb emu geo fix`: it returns OK and frequently changes nothing.
# Verified 2026-09-16 on emulator 37.1.11.0 — a fresh AVD and a 2-day-old one
# both stayed pinned at a stale Northern California fix through dozens of
# `geo fix` calls and raw `geo nmea` sentences, every one answered OK. The
# same trap is recorded in the DUCAT project's notes. Android's own test
# provider bypasses the emulator console entirely and lands immediately.
#
# Two things have to be true or the app still sees nothing:
#   1. `appops set --uid 0` (NOT `--uid shell`, which fails with
#      "uid 2000 not allowed to perform MOCK_LOCATION").
#   2. Something must actually engage the GPS provider. OVERWATCH requests
#      PRIORITY_HIGH_ACCURACY, so starting a scan is enough; with a BALANCED
#      request Play services parks the fused provider at ProviderRequest[OFF]
#      and no fix is ever delivered.
#
# Note the argument orders differ between tools and it is easy to transpose:
# this script and `set-test-provider-location` take LAT,LON; `geo fix` takes
# LON first. Verify with:
#   adb -s <serial> shell dumpsys location | grep -m1 "last location"
set -e
LAT=${1:?usage: emu-gps.sh <lat> <lon> [serial] [seconds]}
LON=${2:?usage: emu-gps.sh <lat> <lon> [serial] [seconds]}
SERIAL=${3:-emulator-5558}
SECONDS_TOTAL=${4:-120}
export PATH=$PATH:${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools

adb -s "$SERIAL" shell settings put secure location_mode 3 >/dev/null 2>&1 || true
adb -s "$SERIAL" shell appops set --uid 0 android:mock_location allow >/dev/null 2>&1 || true
adb -s "$SERIAL" shell cmd location providers add-test-provider gps >/dev/null 2>&1 || true
adb -s "$SERIAL" shell cmd location providers set-test-provider-enabled gps true >/dev/null 2>&1 || true

echo "feeding $LAT,$LON to $SERIAL for ${SECONDS_TOTAL}s (every 3s)…"
END=$((SECONDS + SECONDS_TOTAL))
while [ $SECONDS -lt $END ]; do
  # A test-provider location is one-shot; re-send so it never ages out from
  # under a scanner that only polls every few minutes.
  adb -s "$SERIAL" shell cmd location providers set-test-provider-location gps \
      --location "$LAT,$LON" >/dev/null 2>&1 || true
  sleep 3
done
echo "done. verify: adb -s $SERIAL shell dumpsys location | grep -m1 'last location'"
