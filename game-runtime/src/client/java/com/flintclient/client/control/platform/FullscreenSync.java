package com.flintclient.client.control.platform;

import com.flintclient.client.FlintLog;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.model.GameOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 26.x's side of Flint's Borderless fullscreen: Minecraft has its own Exclusive Fullscreen switch (off: a borderless
 * window, composed by Windows like any window), so Flint's switch is that one, inverted, kept in step both ways. Flint's
 * decides when the options load (before the window exists; 26.1 and 26.2 read it only then) and whenever it changes; a change
 * the player makes in Minecraft's own Video Settings (a screen is open) becomes Flint's. A mod that sets Minecraft's switch at
 * start-up without the player (Sodium 0.9 sets exclusive by default) doesn't change Flint's. 1.21.x has no such switch: its
 * overlay of this class does nothing (FullscreenModeMixin makes the window there).
 */
public final class FullscreenSync {
	/** Minecraft's switch as Flint last left or saw it; null until the first tick. */
	private static @Nullable Boolean seen;

	private FullscreenSync() {
	}

	/** From Options.load: Minecraft's switch follows Flint's before the window is made. */
	public static void optionsLoaded(Options options) {
		options.exclusiveFullscreen().set(!ControlState.settings().gameBool(GameOptions.BORDERLESS_FULLSCREEN));
	}

	/** Each client tick (a field check unless one of the two switches changed). */
	static void tick(Minecraft mc) {
		boolean exclusive = mc.options.exclusiveFullscreen().get();
		boolean borderless = ControlState.settings().gameBool(GameOptions.BORDERLESS_FULLSCREEN);
		if (seen != null && exclusive != seen && Mc263ControlPlatform.currentScreen() != null) {
			// The player switched Minecraft's own option: that is their choice from now on.
			ControlState.settings().setGame(GameOptions.BORDERLESS_FULLSCREEN, !exclusive);
			FlintLog.LOGGER.info("Flint: Borderless fullscreen {} (Minecraft's Exclusive Fullscreen switched in its settings)", exclusive ? "off" : "on");
			seen = exclusive;
			return;
		}
		if (exclusive == borderless) {
			// Flint's switch changed (or something else set Minecraft's): Minecraft's follows, and is saved for the next start.
			mc.options.exclusiveFullscreen().set(!borderless);
			mc.options.save();
			FlintLog.LOGGER.info("Flint: Minecraft's Exclusive Fullscreen {} (Borderless fullscreen {})", borderless ? "off" : "on", borderless ? "on" : "off");
		}
		seen = mc.options.exclusiveFullscreen().get();
	}
}
