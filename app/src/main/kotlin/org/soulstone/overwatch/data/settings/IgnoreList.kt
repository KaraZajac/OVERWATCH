package org.soulstone.overwatch.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.soulstone.overwatch.fusion.DetectionEvent
import org.soulstone.overwatch.fusion.IgnoreRule
import org.soulstone.overwatch.fusion.IgnoreRules
import org.soulstone.overwatch.fusion.IgnoreSet

/**
 * The user's ignore list: devices, cameras and aircraft they have asked not to be
 * alerted about, kept across restarts.
 *
 * It is stored through [SecureStore] rather than plain preferences. The entries
 * are the addresses of the user's own gear and the ids of the cameras on their own
 * street, which between them say who this phone belongs to and where it lives.
 *
 * [matches] is read by scanner threads on every detection, so it works from an
 * immutable [IgnoreSet] swapped in whole; [add] and [remove] come from the UI.
 */
class IgnoreList private constructor(private val appContext: Context) {

    @Volatile
    private var set = IgnoreSet(emptyList())

    private val _rules = MutableStateFlow(set.rules)
    /** Newest first. */
    val rules: StateFlow<List<IgnoreRule>> = _rules.asStateFlow()

    /** False while the stored list has not been read: the read can fail (the
     *  Keystore is a system service, and now and then it does not answer). */
    private var loaded = false

    init { load() }

    fun matches(event: DetectionEvent): Boolean = set.matches(event)

    /** Adds [rule]; one that covers the same detections as an existing rule replaces it. */
    @Synchronized
    fun add(rule: IgnoreRule) {
        load()
        commit(listOf(rule) + set.rules.filter { it.id != rule.id })
    }

    @Synchronized
    fun remove(rule: IgnoreRule) {
        load()
        val kept = set.rules.filter { it.id != rule.id }
        if (kept.size != set.rules.size) commit(kept)
    }

    /**
     * Read the stored list, unless that has been done. Tried again before each
     * change: through v0.6.2 a read that failed at launch left the list empty,
     * and the first rule added after it was saved over every rule there had been.
     * A list that stays unreadable is written over all the same, so that Ignore
     * still works.
     */
    private fun load() {
        if (loaded) return
        when (val read = SecureStore.read(appContext, STORE_NAME)) {
            is SecureStore.Read.Absent -> loaded = true
            is SecureStore.Read.Value -> {
                val stored = IgnoreRules.decode(read.text)
                // Anything added while it could not be read stays, in front.
                val mine = set.rules
                val all = mine + stored.filter { s -> mine.none { it.id == s.id } }
                set = IgnoreSet(all)
                _rules.value = all
                loaded = true
            }
            is SecureStore.Read.Unreadable -> Unit
        }
    }

    private fun commit(rules: List<IgnoreRule>) {
        set = IgnoreSet(rules)
        _rules.value = rules
        // An empty string removes the entry, so an emptied list leaves nothing behind.
        SecureStore.put(appContext, STORE_NAME, if (rules.isEmpty()) "" else IgnoreRules.encode(rules))
    }

    companion object {
        private const val STORE_NAME = "ignore_list"

        @Volatile
        private var instance: IgnoreList? = null

        fun get(context: Context): IgnoreList =
            instance ?: synchronized(this) {
                instance ?: IgnoreList(context.applicationContext).also { instance = it }
            }
    }
}
