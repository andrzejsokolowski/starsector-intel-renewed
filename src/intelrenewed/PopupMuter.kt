package intelrenewed

import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin
import com.fs.starfarer.campaign.CampaignState
import com.fs.state.AppDriver
import intelrenewed.uiframework.ReflectionUtils.invoke

/**
 * Keeps the bottom-left intel popups of hidden entries off the screen.
 *
 * Every popup goes through one list owned by the campaign screen. Two layers:
 *  1. **Swap** (best effort, see [MessageListSwap]): on the first frame the game's popup list is
 *     replaced by a subclass that refuses popups for hidden entries before anything is created, so
 *     neither the card nor its sound happens.
 *  2. **Sweep** (always): every frame the popups currently in the list are checked and any card for
 *     a hidden entry is faded out and marked done, so the game's own housekeeping removes it. This
 *     covers the case where the swap could not be done (a game version where its class names moved).
 */
class PopupMuter : EveryFrameScript {

    private val log = Global.getLogger(PopupMuter::class.java)

    private var swapAttempted = false
    private var sweepFailures = 0

    override fun isDone(): Boolean = false
    override fun runWhilePaused(): Boolean = true

    override fun advance(amount: Float) {
        if (!IrSettings.mutePopups) return
        val state = AppDriver.getInstance().currentState as? CampaignState ?: return
        val list = runCatching { state.invoke("getMessageList") }.getOrNull() ?: return

        if (!swapAttempted) {
            swapAttempted = true
            // Linking the swap class can itself fail on a game version whose class names moved.
            runCatching { MessageListSwap.tryInstall(state, list) }.onFailure {
                log.warn("Intel Renewed: popup swap unavailable on this game version; using the after-the-fact sweep.", it)
            }
        }
        runCatching { sweep(state) }.onFailure {
            sweepFailures++
            if (sweepFailures <= 3) log.error("Intel Renewed: could not sweep intel popups.", it)
        }
    }

    private fun sweep(state: CampaignState) {
        // Re-read: the swap may have replaced the list this frame.
        val list = state.invoke("getMessageList") ?: return
        val cards = list.invoke("getMessages") as? List<*> ?: return
        if (cards.isEmpty()) return
        for (card in ArrayList(cards)) {
            if (card == null) continue
            val intel = runCatching { card.invoke("getIntel") as? IntelInfoPlugin }.getOrNull() ?: continue
            if (!IntelRules.isPopupMuted(intel)) continue
            // Fade it out now and tell it its time is up; the list drops it on its next advance.
            runCatching { card.invoke("getFader")?.invoke("forceOut") }
            runCatching { card.invoke("setElapsed", 9999f) }
        }
    }
}
