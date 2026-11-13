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
    private var set = IgnoreSet(IgnoreRules.decode(SecureStore.get(appContext, STORE_NAME)))

    private val _rules = MutableStateFlow(set.rules)
    /** Newest first. */
    val rules: StateFlow<List<IgnoreRule>> = _rules.asStateFlow()

    fun matches(event: DetectionEvent): Boolean = set.matches(event)

    /** Adds [rule]; one that covers the same detections as an existing rule replaces it. */
    @Synchronized
    fun add(rule: IgnoreRule) {
        commit(listOf(rule) + set.rules.filter { it.id != rule.id })
    }

    @Synchronized
    fun remove(rule: IgnoreRule) {
        val kept = set.rules.filter { it.id != rule.id }
        if (kept.size != set.rules.size) commit(kept)
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
