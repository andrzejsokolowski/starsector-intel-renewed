package intelrenewed

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin
import com.fs.starfarer.api.impl.campaign.ids.Tags
import java.awt.Color

/**
 * The rules that turn the player's choices ([IntelPrefs], [EntryHides]) into what the intel screen
 * shows, plus what the game itself knows about categories (the tag definitions in
 * `data/config/tag_data.json`, merged across every mod).
 *
 * "Hidden content" means an entry that is gone from the screen, the map and the popups:
 *  - its kind is hidden, or
 *  - one of its real categories is hidden with everything in it (the marker tags New, Important,
 *    Accepted and Story never count for this), or
 *  - the entry itself was hidden in this save.
 *
 * Entries their own mod marks as hidden are not the mod's business and are never listed anywhere.
 */
object IntelRules {

    /** The game forces these three buttons to exist even with nothing in them; so do we. */
    val ALWAYS_SHOWN_BUTTONS: Set<String> = setOf(Tags.INTEL_NEW, Tags.INTEL_IMPORTANT, Tags.INTEL_MAJOR_EVENT)

    /** Markers rather than categories: hiding one never hides the entries carrying it. */
    val MARKER_TAGS: Set<String> = setOf(Tags.INTEL_NEW, Tags.INTEL_IMPORTANT, Tags.INTEL_ACCEPTED, Tags.INTEL_STORY)

    private val log = Global.getLogger(IntelRules::class.java)

    // --- Tags of an entry ----------------------------------------------------------------------

    /** The categories an entry files itself under. Entries must cope with a null map, per the API. */
    fun rawTags(intel: IntelInfoPlugin): Set<String> =
        runCatching { intel.getIntelTags(null) }.getOrNull() ?: emptySet()

    /** [rawTags] plus the Important / New markers, the way the tag bar counts them. */
    fun displayTags(intel: IntelInfoPlugin): Set<String> {
        val tags = LinkedHashSet(rawTags(intel))
        if (runCatching { intel.isImportant }.getOrDefault(false)) tags.add(Tags.INTEL_IMPORTANT)
        if (runCatching { intel.isNew }.getOrDefault(false)) tags.add(Tags.INTEL_NEW)
        return tags
    }

    // --- Hiding --------------------------------------------------------------------------------

    /** True when the entry is one the game would list at all (its own mod does not hide it). */
    fun isListable(intel: IntelInfoPlugin): Boolean = !(runCatching { intel.isHidden }.getOrDefault(true))

    fun isKindHidden(intel: IntelInfoPlugin): Boolean = IntelPrefs.isKindHidden(IntelKinds.kindId(intel))

    fun isCategoryHidden(intel: IntelInfoPlugin): Boolean {
        val hidden = IntelPrefs.hiddenCategories()
        if (hidden.isEmpty()) return false
        for (t in rawTags(intel)) if (t !in MARKER_TAGS && t in hidden) return true
        return false
    }

    fun isEntryHidden(intel: IntelInfoPlugin): Boolean = EntryHides.isHidden(intel)

    fun isHiddenContent(intel: IntelInfoPlugin): Boolean =
        isKindHidden(intel) || isCategoryHidden(intel) || isEntryHidden(intel)

    /** Popups are muted for hidden content, unless the player turned that off in the settings. */
    fun isPopupMuted(intel: IntelInfoPlugin): Boolean =
        IrSettings.mutePopups && isHiddenContent(intel)

    /** All entries the game would list, i.e. the intel manager's minus the ones their mods hide. */
    fun listableIntel(): List<IntelInfoPlugin> {
        val all = runCatching { Global.getSector()?.intelManager?.intel }.getOrNull() ?: return emptyList()
        return all.filter { it != null && isListable(it) }
    }

    // --- Search --------------------------------------------------------------------------------

    /** Case-insensitive match of the search text against the entry's title, kind and categories. */
    fun matchesSearch(intel: IntelInfoPlugin, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        if (IntelKinds.title(intel).lowercase().contains(q)) return true
        for (t in rawTags(intel)) if (categoryDisplayName(t).lowercase().contains(q)) return true
        return false
    }

    // --- Category definitions (tag_data.json + factions) ---------------------------------------

    class TagSpec(
        val id: String,
        val name: String,
        val color: Color?,
        val width: Float,
        val putFirst: Boolean,
        val sort: Float,
    )

    private var specCache: Map<String, TagSpec>? = null

    /** Every category the game (and every mod) defines in `tag_data.json`, keyed by tag id. */
    fun tagSpecs(): Map<String, TagSpec> {
        specCache?.let { return it }
        val specs = LinkedHashMap<String, TagSpec>()
        runCatching {
            val json = Global.getSettings().getMergedJSON("data/config/tag_data.json")
            val keys = json.keys()
            while (keys.hasNext()) {
                val id = keys.next() as? String ?: continue
                val obj = json.optJSONObject(id) ?: continue
                val name = if (obj.isNull("name")) id else obj.optString("name", id).ifBlank { id }
                val color = readColor(obj.opt("color"))
                val width = if (obj.isNull("width")) 0f else obj.optDouble("width", 0.0).toFloat()
                val putFirst = !obj.isNull("putFirst") && obj.optBoolean("putFirst", false)
                val sort = if (obj.isNull("sort")) 0f else obj.optDouble("sort", 0.0).toFloat()
                specs[id] = TagSpec(id, name, color, width, putFirst, sort)
            }
        }.onFailure { log.warn("Intel Renewed: could not read tag_data.json; category names fall back to their ids.", it) }
        specCache = specs
        return specs
    }

    /** A colour entry is either `[r,g,b,a]` or the name of a colour in settings.json. */
    private fun readColor(value: Any?): Color? = runCatching {
        when (value) {
            is org.json.JSONArray -> {
                if (value.length() < 3) return null
                val a = if (value.length() > 3) value.getInt(3) else 255
                Color(value.getInt(0), value.getInt(1), value.getInt(2), a)
            }
            is String -> if (value.isBlank()) null else Global.getSettings().getColor(value)
            else -> null
        }
    }.getOrNull()

    /** The name a category button shows: its definition's name, else a same-named faction, else the id. */
    fun categoryDisplayName(tagId: String): String {
        tagSpecs()[tagId]?.let { return it.name }
        val faction = runCatching { Global.getSector()?.getFaction(tagId) }.getOrNull()
        if (faction != null) {
            val n = runCatching { faction.displayName }.getOrNull()
            if (!n.isNullOrBlank()) return n.replaceFirstChar { it.uppercase() }
        }
        return tagId
    }
}
