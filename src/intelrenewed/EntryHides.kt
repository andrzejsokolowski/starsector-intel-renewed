package intelrenewed

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin

/**
 * Per-**save** storage of single entries the player hid ("this one lore entry, not the whole kind").
 *
 * An entry has no id of its own, so it is recognised by a fingerprint of its kind, its title and the
 * moment it became known to the player, kept in the save's persistent data. A fingerprint whose entry
 * has left the game is dropped on the next load ([pruneStale]).
 */
object EntryHides {

    private const val KEY = "intel_renewed_hidden_entries"

    private val log = Global.getLogger(EntryHides::class.java)

    /** Bumped on every change; the screen re-applies its filter when it sees a new value. */
    var revision = 0
        private set

    @Suppress("UNCHECKED_CAST")
    private fun store(create: Boolean): MutableSet<String>? {
        val data = runCatching { Global.getSector()?.persistentData }.getOrNull() ?: return null
        (data[KEY] as? MutableSet<String>)?.let { return it }
        if (!create) return null
        val fresh = HashSet<String>()
        data[KEY] = fresh
        return fresh
    }

    fun fingerprint(intel: IntelInfoPlugin): String {
        val stamp = runCatching { intel.playerVisibleTimestamp }.getOrNull() ?: 0L
        return IntelKinds.kindId(intel) + "|" + IntelKinds.title(intel) + "|" + stamp
    }

    fun isHidden(intel: IntelInfoPlugin): Boolean {
        val s = store(create = false) ?: return false
        if (s.isEmpty()) return false
        return s.contains(fingerprint(intel))
    }

    fun setHidden(intel: IntelInfoPlugin, hidden: Boolean) {
        val s = store(create = true) ?: return
        val changed = if (hidden) s.add(fingerprint(intel)) else s.remove(fingerprint(intel))
        if (changed) revision++
    }

    fun count(): Int = store(create = false)?.size ?: 0

    fun clearAll() {
        val s = store(create = false) ?: return
        if (s.isNotEmpty()) { s.clear(); revision++ }
    }

    /** Drops fingerprints that no longer match any entry the player has. Called on game load. */
    fun pruneStale() {
        val s = store(create = false) ?: return
        if (s.isEmpty()) return
        runCatching {
            val live = HashSet<String>()
            for (intel in Global.getSector().intelManager.intel) live.add(fingerprint(intel))
            val before = s.size
            s.retainAll(live)
            if (s.size != before) {
                revision++
                log.info("Intel Renewed: dropped ${before - s.size} hidden-entry marks whose entries are gone.")
            }
        }.onFailure { log.warn("Intel Renewed: could not prune hidden-entry marks.", it) }
    }
}
