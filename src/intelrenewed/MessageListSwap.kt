package intelrenewed

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.ui.UIComponentAPI
import com.fs.starfarer.api.ui.UIPanelAPI
import com.fs.starfarer.campaign.CampaignState
import intelrenewed.uiframework.ReflectionUtils.set
import intelrenewed.uiframework.bottom
import intelrenewed.uiframework.left
import intelrenewed.uiframework.parent

/**
 * Replaces the campaign screen's popup list with [MutedMessageList], so hidden entries never get
 * a popup (card or sound). Best effort and version-gated: the replacement names obfuscated game
 * classes, so it is only tried on game versions it was built against, and any failure leaves the
 * game's own list in place (popups are then removed after the fact by [PopupMuter]).
 */
object MessageListSwap {

    private val log = Global.getLogger(MessageListSwap::class.java)

    /** Game versions whose popup-list class names this build was checked against. */
    private val SUPPORTED_VERSIONS = setOf("0.98a-RC8")

    private const val FIELD_NAME = "messageListV3"

    fun tryInstall(state: CampaignState, current: Any) {
        if (current.javaClass.name == MutedMessageList::class.java.name) return
        val version = runCatching { Global.getSettings().versionString }.getOrNull() ?: ""
        if (SUPPORTED_VERSIONS.none { version.contains(it) }) {
            log.info("Intel Renewed: game version '$version' not checked for the popup swap; popups of hidden entries are removed after the fact instead.")
            return
        }
        runCatching {
            val old = current as UIComponentAPI
            val parent: UIPanelAPI = old.parent ?: throw IllegalStateException("popup list has no parent")
            val pos = old.position
            val w = pos.width
            val h = pos.height
            // Same spot, expressed relative to the parent's bottom-left the way the game placed it.
            val dx = old.left - parent.left
            val dy = old.bottom - parent.bottom

            val replacement = MutedMessageList()
            parent.removeComponent(old)
            val p = parent.addComponent(replacement)
            p.setSize(w, h)
            p.inBL(dx, dy)
            state.set(FIELD_NAME, replacement)
            log.info("Intel Renewed: popup list replaced; hidden entries will not ping.")
        }.onFailure {
            log.warn("Intel Renewed: could not replace the popup list; popups of hidden entries are removed after the fact instead (their sound may still play).", it)
        }
    }
}
