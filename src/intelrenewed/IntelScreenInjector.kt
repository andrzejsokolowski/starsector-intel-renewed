package intelrenewed

import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CoreUITabId
import com.fs.starfarer.api.ui.UIComponentAPI
import com.fs.starfarer.api.ui.UIPanelAPI
import com.fs.starfarer.campaign.CampaignState
import com.fs.state.AppDriver
import intelrenewed.uiframework.ReflectionUtils.getMethodsMatching
import intelrenewed.uiframework.ReflectionUtils.invoke
import intelrenewed.uiframework.getChildrenCopy

/**
 * Watches the campaign core UI and, while the intel tab is open, hands its list screen to
 * [IntelScreenController] for filtering and decoration.
 *
 * Same structure as the refit hook in Hullmods - Renewed: run while paused, only on the INTEL tab,
 * grab the core UI (also when it lives inside a docking dialog), then find the intel tab by a stable
 * method name (`getEventsPanel`) rather than by its obfuscated class name.
 */
class IntelScreenInjector : EveryFrameScript {

    override fun isDone(): Boolean = false
    override fun runWhilePaused(): Boolean = true

    override fun advance(amount: Float) {
        val sector = Global.getSector() ?: return
        val ui = sector.campaignUI ?: return
        if (ui.currentCoreTab != CoreUITabId.INTEL) {
            IntelScreenController.notifyTabClosed()
            return
        }

        val state = AppDriver.getInstance().currentState
        if (state !is CampaignState) return

        val dialog = runCatching { state.invoke("getEncounterDialog") }.getOrNull()
        val core = runCatching {
            (if (dialog != null) dialog.invoke("getCoreUI") else state.invoke("getCore")) as? UIPanelAPI
        }.getOrNull() ?: return

        val intelTab = findDescendant(core) { it.hasMethod(MARKER_METHOD) } ?: return
        val events = runCatching { intelTab.invoke("getEventsPanel") as? UIPanelAPI }.getOrNull() ?: return
        runCatching { IntelScreenController.process(events) }
            .onFailure { IntelScreenController.reportFailure(it) }
    }

    companion object {
        /** Unique to the intel tab among core UI panels; survives obfuscation. */
        private const val MARKER_METHOD = "getEventsPanel"

        fun UIComponentAPI.hasMethod(name: String): Boolean = getMethodsMatching(name).isNotEmpty()

        /** Depth-first search of the UI tree for the first component matching [predicate]. */
        fun findDescendant(root: UIComponentAPI, predicate: (UIComponentAPI) -> Boolean): UIComponentAPI? {
            if (predicate(root)) return root
            if (root is UIPanelAPI) {
                for (child in runCatching { root.getChildrenCopy() }.getOrDefault(emptyList())) {
                    findDescendant(child, predicate)?.let { return it }
                }
            }
            return null
        }
    }
}
