package org.soulstone.overwatch.data.targets

import android.content.Context
import android.util.Log
import org.soulstone.overwatch.R

/**
 * Known law-enforcement aircraft, keyed by the ICAO 24-bit address that every
 * ADS-B transmission carries.
 *
 * Backed by `res/raw/le_aircraft.csv`, generated at development time by
 * `scripts/gen-le-aircraft.py` from ADSBexchange's registry-derived database.
 * ~1,970 entries, ~83 KB; it ships with the APK and is never downloaded, so
 * this source works with no key, no account and no extra network call beyond
 * the live position feed itself.
 */
object LeAircraft {

    private const val TAG = "LeAircraft"

    data class Entry(
        val icaoHex: String,
        val owner: String,
        /** FAA "Limiting Aircraft Data Displayed" — the operator asked to be
         *  hidden from public trackers, which is itself worth knowing. */
        val ladd: Boolean
    )

    @Volatile private var table: Map<String, Entry>? = null

    /** Parsed on first use and cached; the file is small enough to hold whole. */
    fun load(context: Context): Map<String, Entry> {
        table?.let { return it }
        return synchronized(this) {
            table ?: parse(context).also { table = it }
        }
    }

    fun lookup(context: Context, icaoHex: String): Entry? =
        load(context)[icaoHex.trim().lowercase()]

    private fun parse(context: Context): Map<String, Entry> = try {
        val out = HashMap<String, Entry>(2048)
        context.resources.openRawResource(R.raw.le_aircraft).bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val parts = line.split(',')
                if (parts.size < 2) continue
                val hex = parts[0].trim().lowercase()
                if (hex.length != 6) continue
                out[hex] = Entry(
                    icaoHex = hex,
                    owner = parts[1].trim(),
                    ladd = parts.getOrNull(2)?.trim() == "1"
                )
            }
        }
        Log.i(TAG, "Loaded ${out.size} law-enforcement aircraft")
        out
    } catch (e: Exception) {
        // A missing or corrupt asset must not take the scanner down; without
        // the table every contact simply falls back to behaviour-only scoring.
        Log.w(TAG, "Failed to load aircraft table: ${e.message}")
        emptyMap()
    }
}
