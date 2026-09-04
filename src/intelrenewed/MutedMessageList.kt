package intelrenewed

import com.fs.starfarer.api.campaign.comm.CommMessageAPI
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin

/**
 * The game's bottom-left popup list, with one change: a popup for a hidden entry is refused before
 * the card is built or its sound is played. Every popup overload in the game funnels through the
 * three-argument call overridden here.
 *
 * This is the one place the mod binds to the game's obfuscated class names, so it is only
 * installed on game versions it was built against (see [MessageListSwap]); elsewhere the
 * after-the-fact sweep in [PopupMuter] does the job without the sound.
 */
class MutedMessageList : com.fs.starfarer.campaign.comms.`super`() {

    override fun addMessage(
        intel: IntelInfoPlugin?,
        action: CommMessageAPI.MessageClickAction?,
        custom: Any?,
    ): com.fs.starfarer.campaign.comms.C? {
        if (intel != null && runCatching { IntelRules.isPopupMuted(intel) }.getOrDefault(false)) return null
        return super.addMessage(intel, action, custom)
    }
}
