package intelrenewed

import com.fs.starfarer.api.Global
import lunalib.lunaSettings.LunaSettings
import lunalib.lunaSettings.LunaSettingsListener
import org.lazywizard.lazylib.JSONUtils
import java.awt.Color

/**
 * Reads the mod's LunaSettings page (`data/config/LunaSettings.csv`) into plain fields and keeps
 * them in sync while the game runs.
 *
 *  - **Mute popups** — whether hidden entries also lose their bottom-left message popups.
 *  - **Appearance** — outline colour and background opacity of the Customize window, read per frame
 *    by its render callback so edits show up immediately.
 *  - **Wipe saved preferences** — an action masquerading as a setting: LunaSettings has no button
 *    field type, so it is a boolean we act on and then reset to `false` (see [handleWipeRequest]).
 *
 * Every read falls back to the hardcoded default, so a missing field, a broken CSV, or an absent
 * LunaLib degrades to the built-in behaviour instead of failing.
 */
object IrSettings {

    private const val MOD_ID = IntelRenewedModPlugin.MOD_ID

    private const val KEY_MUTE_POPUPS = "ir_mute_popups"
    private const val KEY_RIGHT_CLICK_HIDING = "ir_right_click_hiding"
    private const val KEY_BORDER_COLOR = "ir_border_color"
    private const val KEY_PANEL_OPACITY = "ir_panel_opacity"
    private const val KEY_WIPE_PREFS = "ir_wipe_prefs"

    /** The LunaSettings store for this mod, under `saves/common/`. */
    private const val LUNA_SETTINGS_FILE = "LunaSettings/$MOD_ID.json"

    private val log = Global.getLogger(IrSettings::class.java)

    private val DEFAULT_BORDER_COLOR = Color(128, 204, 255)
    private const val DEFAULT_PANEL_OPACITY = 0.92f

    // --- Live values ----------------------------------------------------------------------------

    var mutePopups = true; private set
    var rightClickHiding = true; private set
    var borderColor: Color = DEFAULT_BORDER_COLOR; private set
    var panelOpacity: Float = DEFAULT_PANEL_OPACITY; private set

    // --- Wiring ---------------------------------------------------------------------------------

    /** Called once from the mod plugin at application load. */
    fun init() {
        if (!isLunaAvailable()) {
            log.warn("Intel Renewed: LunaLib not enabled; using built-in defaults for every setting.")
            return
        }
        reload()
        handleWipeRequest()
        runCatching {
            if (!LunaSettings.hasSettingsListenerOfClass(Listener::class.java)) {
                LunaSettings.addSettingsListener(Listener())
            }
        }.onFailure { log.error("Intel Renewed: could not register the LunaSettings listener.", it) }
    }

    private fun isLunaAvailable(): Boolean =
        runCatching { Global.getSettings().modManager.isModEnabled("lunalib") }.getOrDefault(false)

    private class Listener : LunaSettingsListener {
        override fun settingsChanged(modID: String) {
            if (modID != MOD_ID) return
            reload()
            handleWipeRequest()
        }
    }

    // --- Reading --------------------------------------------------------------------------------

    private fun reload() {
        mutePopups = bool(KEY_MUTE_POPUPS, true)
        rightClickHiding = bool(KEY_RIGHT_CLICK_HIDING, true)
        borderColor = runCatching { LunaSettings.getColor(MOD_ID, KEY_BORDER_COLOR) }.getOrNull() ?: DEFAULT_BORDER_COLOR
        panelOpacity = (runCatching { LunaSettings.getFloat(MOD_ID, KEY_PANEL_OPACITY) }.getOrNull()
            ?: DEFAULT_PANEL_OPACITY).coerceIn(0f, 1f)
    }

    private fun bool(key: String, fallback: Boolean): Boolean =
        runCatching { LunaSettings.getBoolean(MOD_ID, key) }.getOrNull() ?: fallback

    // --- "Wipe saved preferences" pseudo-button --------------------------------------------------

    /**
     * The flag is disarmed **before** the wipe. If it could not be written back, a wipe here would
     * run again at every launch and keep eating new preferences, so the wipe is skipped and the
     * request left visible instead.
     */
    private fun handleWipeRequest() {
        if (!bool(KEY_WIPE_PREFS, false)) return
        if (!disarmWipeFlag()) {
            log.error(
                "Intel Renewed: could not turn the 'Wipe saved preferences' setting back off, so the wipe " +
                    "was skipped (it would otherwise repeat on every launch). Turn it off in LunaSettings."
            )
            return
        }
        IntelPrefs.wipeAll()
    }

    private fun disarmWipeFlag(): Boolean = runCatching {
        val json = JSONUtils.loadCommonJSON(LUNA_SETTINGS_FILE)
        if (!json.has(KEY_WIPE_PREFS)) return@runCatching false
        json.put(KEY_WIPE_PREFS, false)
        json.save()
        LunaSettings.SettingsCreator.refresh(MOD_ID)
        !bool(KEY_WIPE_PREFS, true)
    }.getOrDefault(false)
}
