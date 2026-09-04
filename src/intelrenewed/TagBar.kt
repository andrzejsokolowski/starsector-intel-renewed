package intelrenewed

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin
import com.fs.starfarer.api.impl.campaign.ids.Tags
import intelrenewed.uiframework.ReflectionUtils.getFieldsMatching
import intelrenewed.uiframework.ReflectionUtils.invoke
import java.awt.Color

/**
 * Rebuilds the row of category buttons at the bottom of the intel screen with our own set, order
 * and counts, using the game's own tag widget so the result looks native.
 *
 * This mirrors, step for step, what the game does when it builds that bar (count the tags over the
 * listed entries, force New / Important / Major events and every `putFirst` definition to exist,
 * colour by definition or faction, sort pinned ones first, line-break between the pinned run and
 * the rest), with three differences: hidden entries are not counted, hidden categories are left
 * out, and zero-count buttons are dropped when the player asked for that.
 */
object TagBar {

    private const val PAD = 10f

    private val log = Global.getLogger(TagBar::class.java)

    private class Entry(val id: String, val count: Int) {
        var name: String = id
        var width = 0f
        var putFirst = false
        var sort = 0f
        var isFaction = false
        var baseColor: Color? = null
        var textColor: Color? = null
        var darkColor: Color? = null
        val sortKey: Float get() = sort + (if (putFirst) -10000f else 0f) + (if (isFaction) 10000f else 0f)
    }

    /**
     * The categories the bar should show for [visible] (the entries left after our filter), with
     * counts, before hidden/empty ones are dropped. Exposed for the Customize window.
     */
    fun categoryCounts(visible: List<IntelInfoPlugin>): LinkedHashMap<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        for (intel in visible) for (t in IntelRules.displayTags(intel)) counts[t] = (counts[t] ?: 0) + 1
        for (t in IntelRules.ALWAYS_SHOWN_BUTTONS) counts.putIfAbsent(t, 0)
        for (s in IntelRules.tagSpecs().values) if (s.putFirst) counts.putIfAbsent(s.id, 0)
        return counts
    }

    /** Rebuilds [tags] (the game's tag widget) for [visible]. Returns false if anything went wrong. */
    fun rebuild(tags: Any, visible: List<IntelInfoPlugin>): Boolean = runCatching {
        val prevSelected = (tags.invoke("getSelected") as? Collection<*>)?.filterIsInstance<String>()?.toSet()
            ?: emptySet()

        val specs = IntelRules.tagSpecs()
        val entries = categoryCounts(visible).map { (id, count) -> makeEntry(id, count, specs[id]) }

        val buttonHidden = IntelPrefs.buttonHiddenCategories()
        val hidden = IntelPrefs.hiddenCategories()
        val hideEmpty = IntelPrefs.hideEmptyCategories
        val kept = entries
            .filter { it.id !in buttonHidden && it.id !in hidden }
            .filter { it.count > 0 || it.id in IntelRules.ALWAYS_SHOWN_BUTTONS || !hideEmpty }
            .sortedWith(compareBy<Entry> { it.sortKey }.thenBy { it.name })

        tags.invoke("reset")
        tags.invoke("beginGroup", false, null, 0f)
        var prevPutFirst = false
        var prevPlain = false
        for (e in kept) {
            val plain = !e.putFirst && !e.isFaction
            if (!e.putFirst && prevPutFirst) tags.invoke("addLineBreakToCurrentGroup", PAD)
            else if (!prevPlain && prevPutFirst && e.isFaction) tags.invoke("addLineBreakToCurrentGroup", PAD)
            tags.invoke("addTag", e.id, e.name, e.count, e.width, e.textColor, e.baseColor, e.darkColor)
            markLastAdded(tags, e)
            prevPutFirst = e.putFirst
            prevPlain = plain
        }
        tags.invoke("addGroup", PAD)

        val keptIds = kept.map { it.id }.toSet()
        val sel = prevSelected.filter { it in keptIds }
        if (sel.isEmpty()) tags.invoke("checkAll") else tags.invoke("check", ArrayList(sel))
        true
    }.onFailure { log.error("Intel Renewed: could not rebuild the category bar.", it) }.getOrDefault(false)

    private fun makeEntry(id: String, count: Int, spec: IntelRules.TagSpec?): Entry {
        val e = Entry(id, count)
        if (spec != null) {
            e.putFirst = spec.putFirst
            e.sort = spec.sort
            e.baseColor = spec.color
            if (id == Tags.INTEL_NEW && spec.color != null) {
                e.textColor = spec.color
                e.darkColor = darken(spec.color, 0.35f)
            }
            e.width = spec.width
            e.name = spec.name
        } else {
            val faction = runCatching { Global.getSector()?.getFaction(id) }.getOrNull()
            if (faction != null) {
                e.isFaction = true
                e.sort = 0f
                e.baseColor = runCatching { faction.baseUIColor }.getOrNull()
                e.width = 0f
                val n = runCatching { faction.displayName }.getOrNull()
                if (!n.isNullOrBlank()) e.name = n.replaceFirstChar { it.uppercase() }
            }
        }
        return e
    }

    private fun darken(c: Color, mult: Float): Color = Color(
        (c.red * mult).toInt().coerceIn(0, 255),
        (c.green * mult).toInt().coerceIn(0, 255),
        (c.blue * mult).toInt().coerceIn(0, 255),
        c.alpha,
    )

    /**
     * The game stamps two flags (pinned, faction) on each entry after adding it; they are only used
     * to decide line breaks, which we handle ourselves, so this is best effort: the entry's two
     * boolean fields are set in declaration order and any failure is ignored.
     */
    private fun markLastAdded(tags: Any, e: Entry) {
        runCatching {
            val last = tags.invoke("getLastAddedTag") ?: return
            val flags = last.getFieldsMatching(type = java.lang.Boolean.TYPE)
            if (flags.size >= 2) {
                flags[0].set(last, e.putFirst)
                flags[1].set(last, e.isFaction)
            }
        }
    }
}
