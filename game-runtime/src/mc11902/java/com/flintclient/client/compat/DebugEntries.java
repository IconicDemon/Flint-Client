package com.flintclient.client.compat;

import com.flintclient.client.control.platform.Mc263BetterF3;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Custom F3 on Minecraft 1.19.2's fixed F3 screen: its open switch is the options' renderDebug (1.21.9's debug entries don't
 * exist; the custom_f3 module says so, ModuleAvailability), which F3 toggles and the debug screen reads.
 */
public final class DebugEntries implements Mc263BetterF3.Entries {
	private static final DebugEntries INSTANCE = new DebugEntries();

	private DebugEntries() {
	}

	/** The F3 screen's switch, or null before the HUD exists. */
	public static @Nullable DebugEntries get() {
		Minecraft mc = Minecraft.getInstance();
		return mc.gui == null ? null : INSTANCE;
	}

	/** Whether F3 is open (whether or not the HUD is hidden). */
	public boolean isF3Visible() {
		return Minecraft.getInstance().options.renderDebug;
	}

	public void toggleF3Visible() {
		Minecraft mc = Minecraft.getInstance();
		mc.options.renderDebug = !mc.options.renderDebug;
	}

	@Override
	public void flint$setOverlay(boolean visible) {
		Minecraft.getInstance().options.renderDebug = visible;
	}
}
