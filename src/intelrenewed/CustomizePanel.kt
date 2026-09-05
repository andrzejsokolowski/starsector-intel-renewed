package intelrenewed

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin
import com.fs.starfarer.api.ui.ButtonAPI
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.TextFieldAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.ui.UIPanelAPI
import com.fs.starfarer.api.util.Misc
import intelrenewed.IntelPrefs.CategoryState
import intelrenewed.uiframework.AreaCheckbox
import intelrenewed.uiframework.Button
import intelrenewed.uiframework.CustomPanel
import intelrenewed.uiframework.Font
import intelrenewed.uiframework.Text
import intelrenewed.uiframework.TextField
import intelrenewed.uiframework.bottom
import intelrenewed.uiframework.drawBorder
import intelrenewed.uiframework.left
import intelrenewed.uiframework.right
import intelrenewed.uiframework.top
import intelrenewed.uiframework.onClick
import intelrenewed.uiframework.playSound
import org.lwjgl.input.Keyboard
import org.lwjgl.opengl.GL11
import java.awt.Color

/**
 * The Customize window: a dimmed full-screen catcher over the intel screen with a centred box that
 * lists every category (Show / No button / Hide all) on the left and every kind of entry (Hidden
 * toggle, with the mod it comes from and how many there are) on the right, plus the "hide empty
 * categories" and "show hidden" switches and a restore-everything button.
 *
 * Choices take effect the moment they are clicked: the preference stores bump their revision and
 * the controller re-applies the filter on the next frame. The lists themselves are only rebuilt
 * when the window is opened or the kind filter text changes, so counts can lag until reopened.
 */
object CustomizePanel {

    private val log = Global.getLogger(CustomizePanel::class.java)

    private const val ROW_H = 24f
    private const val PAD = 16f

    private var host: UIPanelAPI? = null
    private var modal: CustomPanelAPI? = null
    private var kindsBox: CustomPanelAPI? = null
    private var kindFilter: TextFieldAPI? = null
    private var lastKindFilter = ""

    // Geometry of the open box, kept so the kinds list can be rebuilt in place.
    private var boxLeft = 0f
    private var boxTop = 0f
    private var boxW = 0f
    private var boxH = 0f
    private var kindsTop = 0f
    private var kindsLeft = 0f
    private var kindsW = 0f
    private var kindsH = 0f

    private class KindRow(val id: String, val name: String, val mod: String, val count: Int)
    private class CategoryRow(val id: String, val name: String, val count: Int)

    // --- Open / close --------------------------------------------------------------------------

    fun toggle(panel: UIPanelAPI) {
        if (modal != null) close() else open(panel)
    }

    fun open(panel: UIPanelAPI) {
        close()
        host = panel
        runCatching { build(panel) }.onFailure {
            log.error("Intel Renewed: could not open the Customize window.", it)
            close()          // removes the half-built window if it already reached the screen
        }
        ViewState.customizeOpen = modal != null
    }

    fun close() {
        val m = modal
        val h = host
        if (m != null && h != null) runCatching { h.removeComponent(m) }
        modal = null
        kindsBox = null
        kindFilter = null
        ViewState.customizeOpen = false
    }

    /** The screen was rebuilt underneath us; drop the stale references (the controller reopens). */
    fun forget() {
        modal = null
        kindsBox = null
        kindFilter = null
        host = null
    }

    /** Per frame while open: react to the kind filter text. */
    fun advance(panel: UIPanelAPI) {
        if (modal == null) return
        val text = runCatching { kindFilter?.text }.getOrNull() ?: ""
        if (text != lastKindFilter) {
            lastKindFilter = text
            rebuildKinds()
        }
    }

    // --- Data ----------------------------------------------------------------------------------

    private fun kindRows(filter: String): List<KindRow> {
        val listable = IntelRules.listableIntel()
        val byKind = LinkedHashMap<String, MutableList<IntelInfoPlugin>>()
        for (intel in listable) byKind.getOrPut(IntelKinds.kindId(intel)) { ArrayList() }.add(intel)
        val rows = ArrayList<KindRow>()
        for ((id, list) in byKind) {
            val name = IntelKinds.kindName(list.map { IntelKinds.title(it) }).ifBlank { IntelKinds.humanizeClassName(id) }
            rows.add(KindRow(id, name, IntelKinds.sourceMod(id), list.size))
        }
        for ((id, storedName) in IntelPrefs.hiddenKinds()) {
            if (byKind.containsKey(id)) continue
            rows.add(KindRow(id, storedName.ifBlank { IntelKinds.humanizeClassName(id) }, IntelKinds.sourceMod(id), 0))
        }
        val q = filter.trim().lowercase()
        val filtered = if (q.isEmpty()) rows else rows.filter {
            it.name.lowercase().contains(q) || it.mod.lowercase().contains(q)
        }
        return filtered.sortedWith(compareBy<KindRow> { it.name.lowercase() }.thenBy { it.mod })
    }

    private fun categoryRows(): List<CategoryRow> {
        val counts = TagBar.categoryCounts(IntelRules.listableIntel())
        for ((id, _) in IntelPrefs.rememberedCategoryNames()) counts.putIfAbsent(id, 0)
        return counts.map { (id, count) ->
            val remembered = IntelPrefs.rememberedCategoryNames()[id]
            val name = IntelRules.categoryDisplayName(id).let { if (it == id && !remembered.isNullOrBlank()) remembered else it }
            CategoryRow(id, name, count)
        }.sortedWith(compareBy<CategoryRow> { it.id !in IntelRules.ALWAYS_SHOWN_BUTTONS }.thenBy { it.name.lowercase() })
    }

    // --- Building ------------------------------------------------------------------------------

    private fun build(panel: UIPanelAPI) {
        val base = Misc.getBasePlayerColor()
        val bg = Misc.getDarkPlayerColor()
        val bright = Misc.getBrightPlayerColor()
        val gray = Misc.getGrayColor()

        val pw = panel.position.width
        val ph = panel.position.height
        boxW = minOf(1180f, pw - 60f).coerceAtLeast(600f)
        boxH = minOf(780f, ph - 60f).coerceAtLeast(400f)
        boxLeft = (pw - boxW) / 2f
        boxTop = (ph - boxH) / 2f

        panel.CustomPanel(pw, ph) { plugin ->
            modal = this
            plugin.renderBelow { a ->
                GL11.glColor4f(0f, 0f, 0f, 0.62f * a)                 // dim the screen behind us
                GL11.glRectf(plugin.left, plugin.bottom, plugin.right, plugin.top)
                val l = plugin.left + boxLeft
                val r = l + boxW
                val t = plugin.top - boxTop
                val b = t - boxH
                GL11.glColor4f(0f, 0f, 0f, IrSettings.panelOpacity * a)
                GL11.glRectf(l, b, r, t)
                val c = IrSettings.borderColor
                GL11.glColor4f(c.red / 255f, c.green / 255f, c.blue / 255f, a)
                drawBorder(l, t, r, b)
            }
            // Swallow everything that is not ours so nothing underneath reacts.
            plugin.onClick { e -> if (e.isLMBDownEvent || e.isRMBDownEvent) e.consume() }
            plugin.onScroll { e -> e.consume() }
            plugin.onKeyDown { e ->
                if (e.eventValue == Keyboard.KEY_ESCAPE) { close(); e.consume() }
            }

            var y = boxTop + PAD
            val x = boxLeft + PAD
            val innerW = boxW - 2f * PAD

            // Close first, so it exists even if something below fails to build.
            Button("Close", bright, bg, width = 110f, height = 26f) {
                position.inTL(x + innerW - 110f, y)
                onClick { close() }
            }
            runCatching {

            Text("CUSTOMIZE INTEL SCREEN", Font.VICTOR_14, bright) { position.inTL(x, y + 4f) }
            y += 34f

            Text(summaryLine(), Font.VICTOR_14, gray) { position.inTL(x, y) }
            y += 18f
            Text("In the list: Shift + right-click hides one entry, Ctrl + right-click hides every entry " +
                "of that kind. These work even when the search strip is out of the way.",
                Font.VICTOR_14, gray) { position.inTL(x, y) }
            y += 22f

            // Switches and the big red button.
            val switchW = 250f
            AreaCheckbox("Hide empty categories", base, bg, bright, switchW, ROW_H, leftAlign = true) {
                position.inTL(x, y)
                isChecked = IntelPrefs.hideEmptyCategories
                onClick { IntelPrefs.hideEmptyCategories = isChecked }
            }
            AreaCheckbox("Show hidden entries for now", base, bg, bright, switchW, ROW_H, leftAlign = true) {
                position.inTL(x + switchW + 10f, y)
                isChecked = ViewState.showHidden
                onClick { ViewState.showHidden = isChecked }
            }
            Button("Unhide entries hidden in this save", base, bg, width = 260f, height = ROW_H) {
                position.inTL(x + 2f * (switchW + 10f), y)
                onClick { EntryHides.clearAll(); runCatching { playSound("ui_button_pressed") }; reopen() }
            }
            Button("Restore everything", Misc.getNegativeHighlightColor(), bg, width = 170f, height = ROW_H) {
                position.inTL(x + innerW - 170f, y)
                onClick {
                    IntelPrefs.wipeAll(); EntryHides.clearAll()
                    runCatching { playSound("ui_button_pressed") }
                    reopen()
                }
            }
            y += ROW_H + 14f

            // Two columns.
            val colGap = 20f
            val leftW = (innerW - colGap) * 0.4f
            val rightW = innerW - colGap - leftW
            val listsTop = y + 30f
            val listsH = boxTop + boxH - PAD - listsTop

            Text("CATEGORIES", Font.VICTOR_14, bright) { position.inTL(x, y + 4f) }
            Text("Show / No button / Hide all", Font.VICTOR_14, gray) { position.inTL(x + 160f, y + 4f) }
            buildCategoriesBox(this, x, listsTop, leftW, listsH, base, bg, bright, gray)

            val rx = x + leftW + colGap
            Text("KINDS OF ENTRIES", Font.VICTOR_14, bright) { position.inTL(rx, y + 4f) }
            Text("Filter:", Font.VICTOR_14) { position.inTL(rx + rightW - 260f, y + 4f) }
            kindFilter = TextField(210f, ROW_H, Font.VICTOR_14) {
                position.inTL(rx + rightW - 210f, y)
                text = lastKindFilter
            }
            kindsLeft = rx; kindsTop = listsTop; kindsW = rightW; kindsH = listsH
            kindsBox = buildKindsBox(this, base, bg, bright, gray)
            }.onFailure {
                log.error("Intel Renewed: the Customize window could not be filled in.", it)
                Text("Something went wrong building this window; see starsector.log.", Font.VICTOR_14,
                    Misc.getNegativeHighlightColor()) { position.inTL(x, boxTop + 60f) }
            }
        }.apply { position.inTL(0f, 0f) }
    }

    private fun summaryLine(): String {
        val kinds = IntelPrefs.hiddenKinds().size
        val cats = IntelPrefs.hiddenCategories().size
        val buttons = IntelPrefs.buttonHiddenCategories().size
        val entries = EntryHides.count()
        return "Hidden: $kinds kinds, $cats categories with their entries, $buttons category buttons, " +
            "$entries single entries in this save. Nothing is deleted; everything here can be turned back on."
    }

    /** Closes and reopens on the same screen so every row reflects the new state. */
    private fun reopen() {
        val h = host ?: return
        open(h)
    }

    // --- Categories column ---------------------------------------------------------------------

    private fun buildCategoriesBox(
        parent: CustomPanelAPI, x: Float, top: Float, w: Float, h: Float,
        base: Color, bg: Color, bright: Color, gray: Color,
    ) {
        val rows = categoryRows()
        val box = parent.CustomPanel(w, h) { boxPlugin ->
            val tm = createUIElement(w, h, true)
            tm.position.inTL(0f, 0f)
            val rowW = w - 26f
            val btnW = 74f
            val btnGap = 4f
            val labelW = rowW - 3f * btnW - 2f * btnGap - 8f
            var y = 4f
            if (rows.isEmpty()) {
                tm.addPara("(no categories)", 0f).position.inTL(2f, y)
                y += 20f
            }
            for (row in rows) {
                val state = IntelPrefs.categoryState(row.id)
                val marker = row.id in IntelRules.MARKER_TAGS
                val label = tm.addPara("${row.name} (${row.count})", 0f)
                label.position.inTL(2f, y + 4f)
                if (state != CategoryState.SHOWN) label.setColor(gray)
                val boxes = ArrayList<Pair<CategoryState, ButtonAPI>>(3)
                fun radio(text: String, s: CategoryState, index: Int, enabled: Boolean) {
                    val cb = tm.AreaCheckbox(text, base, bg, bright, btnW, ROW_H - 4f, font = Font.VICTOR_10) {
                        position.inTL(labelW + 8f + index * (btnW + btnGap), y)
                    }
                    cb.isChecked = state == s
                    cb.isEnabled = enabled
                    cb.onClick {
                        IntelPrefs.setCategoryState(row.id, row.name, s)
                        boxes.forEach { (st, b) -> b.isChecked = st == s }
                        label.setColor(if (s == CategoryState.SHOWN) base else gray)
                        runCatching { playSound("ui_button_pressed") }
                    }
                    boxes.add(s to cb)
                }
                radio("Show", CategoryState.SHOWN, 0, true)
                radio("No button", CategoryState.BUTTON_HIDDEN, 1, true)
                radio("Hide all", CategoryState.HIDDEN, 2, !marker)
                y += ROW_H + 2f
            }
            tm.setHeightSoFar(y + 8f)
            addUIElement(tm)
            wireWheel(boxPlugin, tm, h)
        }
        box.position.inTL(x, top)
    }

    // --- Kinds column --------------------------------------------------------------------------

    private fun buildKindsBox(parent: CustomPanelAPI, base: Color, bg: Color, bright: Color, gray: Color): CustomPanelAPI {
        val rows = kindRows(lastKindFilter)
        val w = kindsW
        val h = kindsH
        val box = parent.CustomPanel(w, h) { boxPlugin ->
            val tm = createUIElement(w, h, true)
            tm.position.inTL(0f, 0f)
            val rowW = w - 26f
            val btnW = 90f
            val labelW = rowW - btnW - 12f
            var y = 4f
            if (rows.isEmpty()) {
                tm.addPara(if (lastKindFilter.isBlank()) "(no entries)" else "(nothing matches the filter)", 0f)
                    .position.inTL(2f, y)
                y += 20f
            }
            for (row in rows) {
                val hidden = IntelPrefs.isKindHidden(row.id)
                val countText = if (row.count == 0) "none in this game" else "${row.count}"
                val modText = if (row.mod.isBlank()) "" else " - ${row.mod}"
                val label = tm.addPara("${row.name}$modText  ($countText)", 0f)
                label.position.inTL(2f, y + 4f)
                label.setColor(if (hidden) gray else base)
                val cb = tm.AreaCheckbox("Hidden", base, bg, bright, btnW, ROW_H - 4f, font = Font.VICTOR_10) {
                    position.inTL(labelW + 12f, y)
                }
                cb.isChecked = hidden
                cb.onClick {
                    IntelPrefs.setKindHidden(row.id, row.name, cb.isChecked)
                    label.setColor(if (cb.isChecked) gray else base)
                    runCatching { playSound("ui_button_pressed") }
                }
                y += ROW_H + 2f
            }
            tm.setHeightSoFar(y + 8f)
            addUIElement(tm)
            wireWheel(boxPlugin, tm, h)
        }
        box.position.inTL(kindsLeft, kindsTop)
        return box
    }

    private fun rebuildKinds() {
        val m = modal ?: return
        kindsBox?.let { runCatching { m.removeComponent(it) } }
        kindsBox = runCatching {
            buildKindsBox(m, Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(), Misc.getBrightPlayerColor(), Misc.getGrayColor())
        }.onFailure { log.error("Intel Renewed: could not rebuild the kinds list.", it) }.getOrNull()
    }

    /**
     * The nested scroller never gets the mouse wheel routed to it by the engine, so we drive it:
     * capture scroll events on the box and move the scroller's offset. Content must be added
     * before `addUIElement`, otherwise the scroll range is zero (LunaLib does it the same way).
     */
    private fun wireWheel(plugin: intelrenewed.uiframework.ExtendableCustomUIPanelPlugin, tm: TooltipMakerAPI, viewH: Float) {
        val scroller = tm.externalScroller
        plugin.onScroll { event ->
            val s = scroller ?: return@onScroll
            val maxOffset = (tm.heightSoFar - viewH).coerceAtLeast(0f)
            if (maxOffset <= 0f) { event.consume(); return@onScroll }
            val dir = if (event.eventValue > 0) -1f else 1f   // wheel up -> toward top
            s.yOffset = (s.yOffset + dir * 64f).coerceIn(0f, maxOffset)
            event.consume()
        }
    }
}
