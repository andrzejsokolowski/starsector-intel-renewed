package intelrenewed

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin
import com.fs.starfarer.api.ui.UIPanelAPI
import intelrenewed.uiframework.ReflectionUtils.invoke
import intelrenewed.uiframework.getChildrenCopy
import java.util.IdentityHashMap

/**
 * Identity and naming of intel entries, in the terms the player sees.
 *
 * A **kind** is one class of intel entry: every "Trait Suggestion" in the game is one kind, every
 * "Simulator Update" another. Kinds are keyed by the class name ([kindId]), which is what the
 * preferences store. Players never see that name: a kind is shown by the title its entries carry on
 * screen ([kindName]) and, where it can be worked out, the mod that adds it ([sourceMod]).
 *
 * Titles are read the way the screen draws them and cached per entry for the length of a game
 * ([resetForGame] clears the caches on load).
 */
object IntelKinds {

    private val log = Global.getLogger(IntelKinds::class.java)

    private val titleCache = IdentityHashMap<IntelInfoPlugin, String>()

    /** Package prefix -> mod name, built from every enabled mod's plugin class. */
    private var modRoots: List<Pair<String, String>> = emptyList()
    private val sourceCache = HashMap<String, String>()

    /** Package roots so common that they say nothing about which mod a class comes from. */
    private val GENERIC_ROOTS = setOf(
        "data", "data.scripts", "data.scripts.plugins", "data.scripts.campaign", "data.scripts.world",
        "com", "com.fs", "com.fs.starfarer", "com.fs.starfarer.api", "com.fs.starfarer.api.impl",
        "com.fs.starfarer.api.impl.campaign", "org", "net", "scripts", "src",
    )

    fun resetForGame() {
        titleCache.clear()
        sourceCache.clear()
        modRoots = runCatching { buildModRoots() }.getOrElse {
            log.warn("Intel Renewed: could not map mods to their packages; entries will not show a source mod.", it)
            emptyList()
        }
    }

    // --- Identity ------------------------------------------------------------------------------

    /** The stable key of an entry's kind: its class name. */
    fun kindId(intel: IntelInfoPlugin): String = intel.javaClass.name

    // --- Titles --------------------------------------------------------------------------------

    /**
     * The title the entry shows in the list. Read from the entry's own small-description title
     * first (what nearly every entry uses as its list title too); if that is blank, the entry is
     * asked to draw its list line into a throwaway panel and the first text it writes is taken; as a
     * last resort the kind's class name is turned into words.
     */
    fun title(intel: IntelInfoPlugin): String {
        titleCache[intel]?.let { return it }
        val t = runCatching { readTitle(intel) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: humanizeClassName(kindId(intel))
        titleCache[intel] = t
        return t
    }

    private fun readTitle(intel: IntelInfoPlugin): String? {
        runCatching { intel.smallDescriptionTitle }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        // Draw the list line off-screen and take the first text written, exactly as the row shows it.
        val panel = Global.getSettings().createCustom(IntelInfoPlugin.LIST_ITEM_TEXT_WIDTH, 80f, null)
        val tm = panel.createUIElement(IntelInfoPlugin.LIST_ITEM_TEXT_WIDTH, 80f, false)
        intel.createIntelInfo(tm, IntelInfoPlugin.ListInfoMode.INTEL)
        val children = runCatching { (tm as UIPanelAPI).getChildrenCopy() }.getOrNull() ?: return null
        for (c in children) {
            val text = runCatching { c.invoke("getText") as? String }.getOrNull()?.trim()
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }

    /** "RepSuggestionPopupEvent" -> "Rep Suggestion Popup Event". Only used when an entry has no title. */
    fun humanizeClassName(kindId: String): String {
        val simple = kindId.substringAfterLast('.').substringBefore('$')
        val words = StringBuilder()
        for ((i, ch) in simple.withIndex()) {
            if (i > 0 && ch.isUpperCase() && !simple[i - 1].isUpperCase()) words.append(' ')
            words.append(ch)
        }
        return words.toString().replace('_', ' ').trim().ifEmpty { simple }
    }

    // --- Kind names ----------------------------------------------------------------------------

    /**
     * A name for a kind, derived from the titles of its entries: the words they all share at the end
     * ("Halbmond Trait Suggestion" + "Gae Bolg Trait Suggestion" -> "Trait Suggestion") or at the
     * start ("Bounty: Kill X" + "Bounty: Kill Y" -> "Bounty"). With a single entry its title is the
     * name. If the titles share nothing, the first title is used.
     */
    fun kindName(titles: List<String>): String {
        val clean = titles.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (clean.isEmpty()) return ""
        if (clean.size == 1) return clean[0]
        val wordLists = clean.map { it.split(Regex("\\s+")) }
        val suffix = commonSuffix(wordLists)
        val prefix = commonPrefix(wordLists)
        val best = if (suffix.size >= prefix.size) suffix else prefix
        val joined = best.joinToString(" ").trim().trimEnd(':', '-', ',', ';').trim()
        return if (joined.length >= 3) joined else clean[0]
    }

    private fun commonSuffix(lists: List<List<String>>): List<String> {
        val minLen = lists.minOf { it.size }
        var n = 0
        while (n < minLen) {
            val w = lists[0][lists[0].size - 1 - n]
            if (lists.all { it[it.size - 1 - n].equals(w, ignoreCase = true) }) n++ else break
        }
        return lists[0].takeLast(n)
    }

    private fun commonPrefix(lists: List<List<String>>): List<String> {
        val minLen = lists.minOf { it.size }
        var n = 0
        while (n < minLen) {
            val w = lists[0][n]
            if (lists.all { it[n].equals(w, ignoreCase = true) }) n++ else break
        }
        return lists[0].take(n)
    }

    // --- Source mod ----------------------------------------------------------------------------

    /** The name of the mod that adds a kind, or "" when it cannot be worked out. */
    fun sourceMod(kindId: String): String {
        sourceCache[kindId]?.let { return it }
        val pkg = kindId.substringBeforeLast('.', "")
        var best = ""
        var bestLen = -1
        for ((root, name) in modRoots) {
            if ((pkg == root || pkg.startsWith("$root.")) && root.length > bestLen) {
                best = name; bestLen = root.length
            }
        }
        if (best.isEmpty() && (pkg.startsWith("com.fs.starfarer.api") || pkg.startsWith("com.fs.starfarer"))) {
            best = "Starsector"
        }
        sourceCache[kindId] = best
        return best
    }

    /**
     * Every enabled mod's plugin class gives away its package, e.g. `exerelin.plugins.ExerelinModPlugin`
     * -> roots `exerelin.plugins` and `exerelin`. Generic roots shared by many mods (`data.scripts`) are
     * skipped, and a root claimed by two different mods is dropped as ambiguous.
     */
    private fun buildModRoots(): List<Pair<String, String>> {
        val claims = LinkedHashMap<String, String?>()   // root -> mod name, null = ambiguous
        val mods = Global.getSettings().modManager.enabledModsCopy ?: return emptyList()
        for (mod in mods) {
            val plugin = runCatching { mod.modPluginClassName }.getOrNull() ?: continue
            val name = runCatching { mod.name }.getOrNull() ?: continue
            val pkg = plugin.substringBeforeLast('.', "")
            if (pkg.isEmpty()) continue
            val parts = pkg.split('.')
            for (i in parts.indices) {
                val root = parts.subList(0, i + 1).joinToString(".")
                if (root in GENERIC_ROOTS) continue
                val existing = claims[root]
                claims[root] = if (existing == null && !claims.containsKey(root)) name
                else if (existing == name) name else null
            }
        }
        return claims.entries.mapNotNull { (root, name) -> if (name != null) root to name else null }
    }
}
