package org.soulstone.overwatch.fusion

import org.json.JSONArray
import org.json.JSONObject

/**
 * One thing the user has asked not to be alerted about.
 *
 * A rule belongs to one [source], because that is how detections are keyed: the
 * same doorbell heard over Bluetooth and over Wi-Fi is two rows and takes two rules.
 *
 * @param match [Match.DEVICE] covers the one device, camera or aircraft whose event
 *        key is [value]. [Match.KIND] covers every detection whose
 *        [DetectionEvent.kind] has the id [value].
 * @param label what to call it in the list in Settings.
 */
data class IgnoreRule(
    val source: DetectionSource,
    val match: Match,
    val value: String,
    val label: String,
    val addedMs: Long
) {
    enum class Match { DEVICE, KIND }

    /** Two rules with the same id cover the same detections, whatever their labels. */
    val id: String get() = idOf(match, source, value)

    /** The address, camera id, aircraft hex or serial behind a [Match.DEVICE] rule:
     *  the event key without the scanner's prefix. Longest prefix first, because a
     *  MAC has colons of its own and "hack:names:aa:bb:…" also starts with "hack:". */
    val identifier: String get() =
        if (match != Match.DEVICE) value
        else KEY_PREFIXES.firstOrNull { value.startsWith(it) }?.let { value.removePrefix(it) } ?: value

    companion object {
        private val KEY_PREFIXES = listOf(
            "blespam:cycle:", "hack:names:", "hack:", "skim:", "mic:", "trk:", "air:", "rid:", "osm:"
        )
        internal fun idOf(match: Match, source: DetectionSource, value: String) =
            "${match.name}|${source.name}|$value"
    }
}

/** An immutable set of rules, built once per change so [matches] is a hash lookup
 *  that any scanner thread can call without a lock. */
class IgnoreSet(val rules: List<IgnoreRule>) {
    private val ids: Set<String> = rules.mapTo(HashSet()) { it.id }

    fun matches(e: DetectionEvent): Boolean {
        if (ids.isEmpty()) return false
        if (IgnoreRule.idOf(IgnoreRule.Match.DEVICE, e.source, e.key) in ids) return true
        val kind = e.kind ?: return false
        return IgnoreRule.idOf(IgnoreRule.Match.KIND, e.source, kind.id) in ids
    }
}

object IgnoreRules {

    private val MAC_AT_END = Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
    private val DISTANCE_TAIL = Regex("\\s@\\s\\d+\\s?m\\b.*$")
    private val ALTITUDE_TAIL = Regex("\\s·\\s(?:\\d+\\sm up|alt -?\\d+\\sm)$")
    private const val MAX_LABEL = 120

    /**
     * Whether a row gets an ignore button at all.
     *
     * Not a Waze report: it is gone within the hour and never comes back under the
     * same id, so a rule for one would sit in the list forever doing nothing. Not a
     * HACKER finding with no transmitter behind it (a pop-up flood, a beacon flood):
     * that is an attack under way, not a device somebody owns, and the HACKER switch
     * is the way to turn those off.
     */
    fun canIgnore(e: DetectionEvent): Boolean = when (e.source) {
        DetectionSource.WAZE -> false
        DetectionSource.HACKER -> MAC_AT_END.containsMatchIn(e.key)
        else -> true
    }

    /** A rule for this one device, camera or aircraft. */
    fun deviceRule(e: DetectionEvent, nowMs: Long): IgnoreRule =
        IgnoreRule(e.source, IgnoreRule.Match.DEVICE, e.key, stableLabel(e), nowMs)

    /** A rule for every detection of this event's kind, or null if it has none. */
    fun kindRule(e: DetectionEvent, nowMs: Long): IgnoreRule? {
        val kind = e.kind ?: return null
        return IgnoreRule(e.source, IgnoreRule.Match.KIND, kind.id, kind.label, nowMs)
    }

    /**
     * The row's label without the parts that were only true at that moment — a
     * distance, an altitude, how long a tracker had been near — so the entry in
     * Settings still reads right next week.
     */
    fun stableLabel(e: DetectionEvent): String {
        val kind = e.kind
        val text = when {
            e.source == DetectionSource.TRACKER && kind != null ->
                "${kind.label} (${e.key.removePrefix("trk:")})"
            else -> e.label.replace(DISTANCE_TAIL, "").replace(ALTITUDE_TAIL, "")
        }
        return clean(text)
    }

    /** Labels carry names that a nearby device chose for itself, so nothing a
     *  stranger broadcasts gets to put a line break or a page of text in Settings. */
    private fun clean(s: String): String =
        s.filter { !it.isISOControl() }.trim().take(MAX_LABEL).ifEmpty { "Unnamed" }

    fun encode(rules: List<IgnoreRule>): String {
        val arr = JSONArray()
        for (r in rules) {
            arr.put(
                JSONObject()
                    .put("source", r.source.name)
                    .put("match", r.match.name)
                    .put("value", r.value)
                    .put("label", r.label)
                    .put("added", r.addedMs)
            )
        }
        return arr.toString()
    }

    /** Reads what [encode] wrote. An entry this version cannot make sense of (a
     *  source that has since been renamed, a damaged record) is skipped rather than
     *  taking the rest of the list down with it. */
    fun decode(text: String?): List<IgnoreRule> {
        if (text.isNullOrBlank()) return emptyList()
        val arr = try { JSONArray(text) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<IgnoreRule>(arr.length())
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val source = DetectionSource.values().firstOrNull { it.name == o.optString("source") } ?: continue
            val match = IgnoreRule.Match.values().firstOrNull { it.name == o.optString("match") } ?: continue
            val value = o.optString("value")
            if (value.isEmpty()) continue
            val rule = IgnoreRule(source, match, value, clean(o.optString("label")), o.optLong("added", 0L))
            if (seen.add(rule.id)) out.add(rule)
        }
        return out
    }
}
