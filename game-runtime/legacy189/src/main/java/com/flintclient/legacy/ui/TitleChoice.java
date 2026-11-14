package com.flintclient.legacy.ui;

import com.flintclient.client.ui.screen.FlintTitleScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.menu.TitleScreen;
import org.apache.logging.log4j.LogManager;
import org.jspecify.annotations.Nullable;

/**
 * Which title screen Minecraft 1.8.9 shows: the one the player chose, Flint's main menu (the shared {@link FlintTitleScreen}, as
 * on every line) or Minecraft's own. Called for every screen shown ({@code MinecraftMixin}). Render thread.
 */
public final class TitleChoice {
	private static boolean warned;

	private TitleChoice() {
	}

	/**
	 * The screen to show in place of {@code screen}: Minecraft's title screen ({@code TitleScreen} itself, or nothing while no
	 * world is open, which Minecraft turns into it) becomes Flint's main menu when the player chose it and it runs here; Flint's
	 * menu becomes Minecraft's when the player chose that. Any other screen is shown as it is.
	 */
	public static @Nullable Screen screenFor(@Nullable Screen screen, boolean noWorld) {
		boolean minecrafts = screen == null ? noWorld : screen.getClass() == TitleScreen.class;
		if (minecrafts) {
			if (com.flintclient.client.mc.Screen.titleBroken()) {
				return screen;
			}
			try {
				Screen chosen = FlintTitleScreen.titleFor(new com.flintclient.client.mc.TitleScreen());
				return chosen instanceof FlintTitleScreen ? chosen : screen;
			} catch (RuntimeException | LinkageError e) {
				if (!warned) {
					warned = true;
					LogManager.getLogger("Flint").error("Flint: its main menu couldn't start; showing Minecraft's title screen", e);
				}
				return screen;
			}
		}
		if (screen instanceof FlintTitleScreen && (FlintTitleScreen.vanillaChosen() || !FlintTitleScreen.fits() || com.flintclient.client.mc.Screen.titleBroken())) {
			return new TitleScreen();
		}
		return screen;
	}
}
