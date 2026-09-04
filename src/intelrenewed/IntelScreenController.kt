package intelrenewed

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin
import com.fs.starfarer.api.ui.ButtonAPI
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.TextFieldAPI
import com.fs.starfarer.api.ui.UIComponentAPI
import com.fs.starfarer.api.ui.UIPanelAPI
import com.fs.starfarer.api.util.Misc
import intelrenewed.IntelScreenInjector.Companion.findDescendant
import intelrenewed.IntelScreenInjector.Companion.hasMethod
import intelrenewed.uiframework.AreaCheckbox
import intelrenewed.uiframework.Button
import intelrenewed.uiframework.CustomPanel
import intelrenewed.uiframework.Font
import intelrenewed.uiframework.ReflectionUtils.getFieldsMatching
import intelrenewed.uiframework.ReflectionUtils.invoke
import intelrenewed.uiframework.Text
import intelrenewed.uiframework.TextField
import intelrenewed.uiframework.bottom
import intelrenewed.uiframework.drawBorder
import intelrenewed.uiframework.getChildrenCopy
import intelrenewed.uiframework.left
import intelrenewed.uiframework.onClick
import intelrenewed.uiframework.playSound
import intelrenewed.uiframework.right
import intelrenewed.uiframework.top
import intelrenewed.uiframework.width
import org.lwjgl.opengl.GL11

/**
 * Everything that happens to the intel list screen while it is open.
 *
 * Each frame the injector hands over the game's intel panel. When the game has (re)built its row
 * list — it does so on opening, on every category click, on every intel button — the controller:
 *  1. pulls the rows of hidden entries (and of entries not matching the search) out of the list,
 *  2. drops the matching icons from the map,
 *  3. rebuilds the category bar with our own set and counts ([TagBar]).
 * The game's own list, rows and buttons are reused; nothing is drawn twice and no intel object is
 * touched.
 *
 * On top of that it adds a small strip over the map's top edge — search box, Customize button,
 * Hide-entry / Hide-kind for the selected entry, and a Show-hidden toggle — and owns the Customize
 * window ([CustomizePanel]).
 *
 * The screen is recognised by identity of its row list: the game recreates that list (and every
 * child of the panel, ours included) when the screen is resized or reopened, and that is exactly
 * when we must inject again.
 */
object IntelScreenController {

    private val log = Global.getLogger(IntelScreenController::class.java)

    private const val ROW_H = 22f
    private const val STRIP_PAD = 4f

    private var boundPanel: UIPanelAPI? = null
    private var boundList: Any? = null
    private var lastSignature = ""

    private var strip: CustomPanelAPI? = null
    private var searchField: TextFieldAPI? = null
    private var hideEntryBtn: ButtonAPI? = null
    private var hideKindBtn: ButtonAPI? = null
    private var showHiddenBox: ButtonAPI? = null
    private var lastHideEntryText = ""
    private var lastHideKindText = ""

    private var failureCount = 0

    // --- Entry points --------------------------------------------------------------------------

    fun process(panel: UIPanelAPI) {
        val list = panel.invoke("getList") ?: return
        if (list !== boundList || panel !== boundPanel) bind(panel, list)

        // The search box is the source of truth for the search text while it exists.
        searchField?.let { f ->
            val t = runCatching { f.text }.getOrNull() ?: ""
            if (t != ViewState.searchText) ViewState.searchText = t
        }

        val tags = findTags(panel)
        val sig = signature(list, tags)
        if (sig != lastSignature) {
            applyFilter(panel, list, tags)
            lastSignature = signature(list, tags)
        }

        updateStripButtons(panel)
        CustomizePanel.advance(panel)
    }

    /** The intel tab is no longer open: forget the panel so the next opening injects afresh. */
    fun notifyTabClosed() {
        if (boundPanel == null && boundList == null) return
        boundPanel = null
        boundList = null
        lastSignature = ""
        strip = null
        searchField = null
        hideEntryBtn = null
        hideKindBtn = null
        showHiddenBox = null
        CustomizePanel.forget()
        ViewState.customizeOpen = false      // closing the tab closes the window too
    }

    /** Called by the injector when a frame's processing threw. Logged a handful of times, then quiet. */
    fun reportFailure(t: Throwable) {
        failureCount++
        if (failureCount <= 5) log.error("Intel Renewed: intel screen processing failed (${failureCount}/5 logged).", t)
    }

    /** Ask for the filter to be re-applied on the next frame (after a preference change). */
    fun invalidate() {
        lastSignature = ""
    }

    // --- Binding -------------------------------------------------------------------------------

    private fun bind(panel: UIPanelAPI, list: Any) {
        boundPanel = panel
        boundList = list
        lastSignature = ""
        strip = null
        searchField = null
        hideEntryBtn = null
        hideKindBtn = null
        showHiddenBox = null
        CustomizePanel.forget()
        IrDebug.dumpTree(panel, "EventsPanel")
        runCatching { injectStrip(panel) }.onFailure { log.error("Intel Renewed: could not add the search strip.", it) }
        if (ViewState.customizeOpen) CustomizePanel.open(panel)
    }

    // --- Signature -----------------------------------------------------------------------------

    private fun signature(list: Any, tags: Any?): String {
        val items = runCatching { list.invoke("getItems") as? List<*> }.getOrNull() ?: emptyList<Any>()
        var identity = 0L
        for (it in items) identity = identity * 31 + System.identityHashCode(it)
        val selected = runCatching { (tags?.invoke("getSelected") as? Collection<*>)?.size }.getOrNull() ?: -1
        return "${items.size}|$identity|${IntelPrefs.revision}|${EntryHides.revision}|" +
            "${ViewState.searchText}|${ViewState.showHidden}|$selected"
    }

    // --- Filtering -----------------------------------------------------------------------------

    private fun shouldHide(intel: IntelInfoPlugin): Boolean =
        !ViewState.showHidden && IntelRules.isHiddenContent(intel)

    private fun applyFilter(panel: UIPanelAPI, list: Any, tags: Any?) {
        val items = runCatching { list.invoke("getItems") as? List<*> }.getOrNull() ?: return
        val query = ViewState.searchText
        val toRemove = ArrayList<Any>()
        for (item in items) {
            if (item == null) continue
            val intel = intelOfItem(item) ?: continue
            if (shouldHide(intel) || !IntelRules.matchesSearch(intel, query)) toRemove.add(item)
        }
        if (toRemove.isNotEmpty()) {
            runCatching {
                list.invoke("suspendRecompute")
                for (item in toRemove) list.invoke("removeItem", item)
                list.invoke("resumeRecompute")
                list.invoke("collapseEmptySlots", true)
            }.onFailure { log.error("Intel Renewed: could not remove rows from the intel list.", it) }
        }
        runCatching { pruneMapIcons(panel) }.onFailure { log.warn("Intel Renewed: could not prune map icons.", it) }

        if (tags != null && needsBarRebuild()) {
            val counted = IntelRules.listableIntel().filter { !shouldHide(it) }
            TagBar.rebuild(tags, counted)
        }
    }

    private fun needsBarRebuild(): Boolean =
        !IntelPrefs.nothingHidden() || IntelPrefs.hideEmptyCategories || EntryHides.count() > 0

    /** A list item is the button wrapping a row; the row knows its entry. */
    private fun intelOfItem(item: Any): IntelInfoPlugin? {
        (runCatching { item.invoke("getInfo") }.getOrNull() as? IntelInfoPlugin)?.let { return it }
        val row = runCatching { item.invoke("getButtonPanel") }.getOrNull() ?: return null
        return runCatching { row.invoke("getInfo") }.getOrNull() as? IntelInfoPlugin
    }

    /** The map draws an icon for every entity in its intel-icon list; each carries its entry. */
    private fun pruneMapIcons(panel: UIPanelAPI) {
        val mapHolder = panel.invoke("getMap") ?: return
        val map = mapHolder.invoke("getMap") ?: return
        val entities = map.invoke("getIntelData") as? List<*> ?: return
        if (entities.isEmpty()) return
        val query = ViewState.searchText
        for (ent in ArrayList(entities)) {
            if (ent == null) continue
            val custom = runCatching { ent.invoke("getCustomData") as? Map<*, *> }.getOrNull() ?: continue
            val data = custom["intelIconData"] ?: continue
            val intel = runCatching {
                data.getFieldsMatching(type = IntelInfoPlugin::class.java).firstOrNull()?.get(data)
            }.getOrNull() as? IntelInfoPlugin ?: continue
            if (shouldHide(intel) || !IntelRules.matchesSearch(intel, query)) {
                runCatching { map.invoke("removeIntelData", data) }
            }
        }
    }

    private fun findTags(panel: UIPanelAPI): Any? =
        findDescendant(panel) { it.hasMethod("getAllTags") && it.hasMethod("addLineBreakToCurrentGroup") }

    // --- Selected entry ------------------------------------------------------------------------

    fun selectedIntel(panel: UIPanelAPI): IntelInfoPlugin? =
        runCatching { panel.invoke("getSelectedRow")?.invoke("getInfo") as? IntelInfoPlugin }.getOrNull()

    /** The display name for the kind of [intel], from the titles of every listed entry of that kind. */
    fun kindNameFor(intel: IntelInfoPlugin): String {
        val id = IntelKinds.kindId(intel)
        val titles = IntelRules.listableIntel().filter { IntelKinds.kindId(it) == id }.map { IntelKinds.title(it) }
        return IntelKinds.kindName(if (titles.isEmpty()) listOf(IntelKinds.title(intel)) else titles)
    }

    fun toggleKindHidden(intel: IntelInfoPlugin) {
        val id = IntelKinds.kindId(intel)
        IntelPrefs.setKindHidden(id, kindNameFor(intel), !IntelPrefs.isKindHidden(id))
        runCatching { playSound("ui_button_pressed") }
    }

    fun toggleEntryHidden(intel: IntelInfoPlugin) {
        EntryHides.setHidden(intel, !EntryHides.isHidden(intel))
        runCatching { playSound("ui_button_pressed") }
    }

    // --- The strip over the map ----------------------------------------------------------------

    /**
     * Two short rows in the map's top-right corner, next to the game's own STARSCAPE / SHOW FUEL
     * RANGE buttons: search + Customize, then Hide entry / Hide kind / Show hidden.
     */
    private fun injectStrip(panel: UIPanelAPI) {
        val mapComp = panel.invoke("getMap") as? UIComponentAPI ?: return
        val base = Misc.getBasePlayerColor()
        val bg = Misc.getDarkPlayerColor()
        val bright = Misc.getBrightPlayerColor()

        // Leave the game's own STARSCAPE / SHOW FUEL RANGE buttons alone: start right of them.
        var occupiedRight = mapComp.left + 300f
        forEachDescendant(mapComp) { c ->
            val t = runCatching { c.invoke("getText") as? String }.getOrNull()
            if (t != null && (t.contains("fuel", true) || t.contains("starscape", true))) {
                occupiedRight = maxOf(occupiedRight, c.right)
            }
        }

        val available = mapComp.right - occupiedRight - 16f
        val w = minOf(560f, available).coerceAtLeast(300f)
        val h = ROW_H * 2f + STRIP_PAD * 3f
        val leftScreen = mapComp.right - 8f - w

        strip = panel.CustomPanel(w, h) { plugin ->
            plugin.renderBelow { alpha ->
                GL11.glColor4f(0f, 0f, 0f, 0.6f * alpha)
                GL11.glRectf(plugin.left, plugin.bottom, plugin.right, plugin.top)
                val c = IrSettings.borderColor
                GL11.glColor4f(c.red / 255f, c.green / 255f, c.blue / 255f, 0.8f * alpha)
                drawBorder(plugin.left, plugin.top, plugin.right, plugin.bottom)
            }
            // Keep clicks and wheel scrolls off the map underneath.
            plugin.onClick { e -> if (e.isLMBDownEvent || e.isRMBDownEvent) e.consume() }
            plugin.onScroll { e -> e.consume() }

            val pad = STRIP_PAD
            val inner = w - 2f * pad
            val row1 = pad
            val row2 = pad + ROW_H + pad

            // Row 1: "Search" label, the box, Customize.
            val labelW = 52f
            val customizeW = 104f
            val gap = 6f
            Text("Search:", Font.VICTOR_14) { position.inTL(pad, row1 + 3f) }
            val fieldW = inner - labelW - customizeW - 2f * gap
            searchField = TextField(fieldW, ROW_H, Font.VICTOR_14) {
                position.inTL(pad + labelW + gap, row1)
                text = ViewState.searchText
            }
            Button("Customize", bright, bg, width = customizeW, height = ROW_H, font = Font.VICTOR_14) {
                position.inTL(pad + labelW + gap + fieldW + gap, row1)
                onClick { CustomizePanel.toggle(panel) }
            }

            // Row 2: act on the selected entry, and the momentary reveal.
            val thirdW = (inner - 2f * gap) / 3f
            hideEntryBtn = Button("Hide entry", base, bg, width = thirdW, height = ROW_H, font = Font.VICTOR_14) {
                position.inTL(pad, row2)
                onClick { selectedIntel(panel)?.let { toggleEntryHidden(it) } }
            }
            hideKindBtn = Button("Hide kind", base, bg, width = thirdW, height = ROW_H, font = Font.VICTOR_14) {
                position.inTL(pad + thirdW + gap, row2)
                onClick { selectedIntel(panel)?.let { toggleKindHidden(it) } }
            }
            showHiddenBox = AreaCheckbox("Show hidden", base, bg, bright, thirdW, ROW_H, font = Font.VICTOR_14) {
                position.inTL(pad + 2f * (thirdW + gap), row2)
                isChecked = ViewState.showHidden
                onClick { ViewState.showHidden = isChecked }
            }
            lastHideEntryText = "Hide entry"
            lastHideKindText = "Hide kind"
        }.apply {
            position.inTL(leftScreen - panel.left, (panel.top - mapComp.top).coerceAtLeast(0f) + 6f)
        }
    }

    /** Enables the two Hide buttons only with a selected entry, and words them for its state. */
    private fun updateStripButtons(panel: UIPanelAPI) {
        val entryBtn = hideEntryBtn ?: return
        val kindBtn = hideKindBtn ?: return
        val sel = selectedIntel(panel)
        val enabled = sel != null
        runCatching { entryBtn.isEnabled = enabled; kindBtn.isEnabled = enabled }
        val entryText = if (sel != null && EntryHides.isHidden(sel)) "Unhide entry" else "Hide entry"
        val kindText = if (sel != null && IntelPrefs.isKindHidden(IntelKinds.kindId(sel))) "Unhide kind" else "Hide kind"
        if (entryText != lastHideEntryText) { lastHideEntryText = entryText; runCatching { entryBtn.text = entryText } }
        if (kindText != lastHideKindText) { lastHideKindText = kindText; runCatching { kindBtn.text = kindText } }
        showHiddenBox?.let { box -> if (box.isChecked != ViewState.showHidden) runCatching { box.isChecked = ViewState.showHidden } }
    }

    /** Width of the strip, so the Customize window can avoid it. */
    fun stripWidth(): Float = strip?.width ?: 0f

    /** Visits every component under [root], depth first. */
    private fun forEachDescendant(root: UIComponentAPI, visit: (UIComponentAPI) -> Unit) {
        visit(root)
        if (root is UIPanelAPI) {
            for (child in runCatching { root.getChildrenCopy() }.getOrDefault(emptyList())) forEachDescendant(child, visit)
        }
    }
}
