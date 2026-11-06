package com.flintclient.client.compat;

import com.flintclient.client.control.platform.Mc263BetterF3;
import com.flintclient.client.mixin.bridge.DebugScreenOverlayAccessor;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * 1.21.9's {@code DebugScreenEntryList} as Custom F3 uses it: whether the F3 screen is open, and opening or closing it. On
 * 1.21.1 that is the debug overlay's own switch.
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

	private static DebugScreenOverlayAccessor overlay() {
		return (DebugScreenOverlayAccessor) Minecraft.getInstance().gui.getDebugOverlay();
	}

	/** Whether F3 is open (whether or not the HUD is hidden). */
	public boolean isF3Visible() {
		return overlay().flint$renderDebug();
	}

	public void toggleF3Visible() {
		Minecraft.getInstance().gui.getDebugOverlay().toggleOverlay();
	}

	@Override
	public void flint$setOverlay(boolean visible) {
		overlay().flint$setRenderDebug(visible);
	}
}
