package intelrenewed;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;

/**
 * Entry point for Intel Renewed.
 *
 * <p>Reads the LunaSettings page at application load, and on every game load registers the two
 * transient scripts that do the work: {@link IntelScreenInjector} (filters and decorates the intel
 * tab while it is open) and {@link PopupMuter} (keeps popups of hidden entries off the screen).</p>
 */
public class IntelRenewedModPlugin extends BaseModPlugin {

    public static final String MOD_ID = "intel_renewed";

    @Override
    public void onApplicationLoad() throws Exception {
        IrSettings.INSTANCE.init();
        Global.getLogger(IntelRenewedModPlugin.class).info("Intel Renewed: application loaded.");
    }

    @Override
    public void onGameLoad(boolean newGame) {
        IntelKinds.INSTANCE.resetForGame();
        EntryHides.INSTANCE.pruneStale();
        // Transient: not saved with the campaign, so they are re-added cleanly on every load.
        Global.getSector().addTransientScript(new IntelScreenInjector());
        Global.getSector().addTransientScript(new PopupMuter());
    }
}
