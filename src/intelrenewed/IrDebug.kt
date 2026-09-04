package intelrenewed

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.ui.UIComponentAPI
import com.fs.starfarer.api.ui.UIPanelAPI
import intelrenewed.uiframework.ReflectionUtils.invoke
import intelrenewed.uiframework.getChildrenCopy
import intelrenewed.uiframework.height
import intelrenewed.uiframework.width
import intelrenewed.uiframework.x
import intelrenewed.uiframework.y

/**
 * Developer aid: dumps the intel screen's UI component tree (class, screen rect, any text) to
 * starsector.log. Off by default; flip [ENABLED] to find geometry when something looks misplaced.
 */
object IrDebug {

    const val ENABLED = false

    private val log = Global.getLogger(IrDebug::class.java)

    fun dumpTree(root: UIComponentAPI, label: String) {
        if (!ENABLED) return
        log.info("===== Intel Renewed UI dump: $label =====")
        walk(root, 0)
        log.info("===== Intel Renewed UI dump end =====")
    }

    private fun walk(c: UIComponentAPI, depth: Int) {
        if (depth > 12) return
        val cls = c.javaClass.name.substringAfterLast('.')
        val rect = runCatching { "x=${c.x.toInt()} y=${c.y.toInt()} w=${c.width.toInt()} h=${c.height.toInt()}" }
            .getOrDefault("rect=?")
        val text = runCatching { c.invoke("getText") as? String }.getOrNull()
        val extra = if (!text.isNullOrBlank()) " text='$text'" else ""
        log.info("  ".repeat(depth) + "$cls [$rect]$extra")
        if (c is UIPanelAPI) for (child in runCatching { c.getChildrenCopy() }.getOrDefault(emptyList())) {
            walk(child, depth + 1)
        }
    }
}
