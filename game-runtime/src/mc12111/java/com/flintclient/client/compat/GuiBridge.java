package com.flintclient.client.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 1.21.11's {@code Gui} is the HUD, as in 26.1, and the screen, the overlay and the toasts live on
 * {@code Minecraft} itself; 26.2 and later split that into {@code Minecraft.gui} (screens) and {@code gui.hud} (the HUD).
 * Loom adds this interface to {@code Gui} at compile time ({@code src/mc12111/loom/fabric.mod.json}) and
 * {@code mixin.GuiBridgeMixin} adds it at run time, so the shared sources' {@code mc.gui.screen()},
 * {@code mc.gui.setScreen(…)}, {@code McCompat.hud().isHidden()} and the rest work unchanged: each answers what 26.2's
 * {@code Gui} or {@code Hud} does.
 */
public interface GuiBridge {
	/** 26.1's name for drawing the subtitles a screen deferred. */
	default void extractDeferredSubtitles() {
		((net.minecraft.client.gui.Gui) (Object) this).renderDeferredSubtitles();
	}

	default @Nullable Screen screen() {
		return Minecraft.getInstance().screen;
	}

	default void setScreen(@Nullable Screen screen) {
		Minecraft.getInstance().setScreen(screen);
	}

	default @Nullable Overlay overlay() {
		return Minecraft.getInstance().getOverlay();
	}

	/** As 26.2's {@code Gui.isPausing}: a pause screen or a pausing overlay is shown. */
	default boolean isPausing() {
		Minecraft minecraft = Minecraft.getInstance();
		Screen screen = minecraft.screen;
		Overlay overlay = minecraft.getOverlay();
		return screen != null && screen.isPauseScreen() || overlay != null && overlay.isPauseScreen();
	}

	default ToastManager toastManager() {
		return Minecraft.getInstance().getToastManager();
	}

	/** As 26.2's {@code Hud.isHidden}: the HUD is hidden with F1 (26.1 keeps that in the options). */
	default boolean isHidden() {
		return Minecraft.getInstance().options.hideGui;
	}
}
