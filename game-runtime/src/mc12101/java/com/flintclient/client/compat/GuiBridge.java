package com.flintclient.client.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 1.21.1's {@code Gui} is the HUD, as in 26.1, and the screen, the overlay and the toasts live on
 * {@code Minecraft} itself; 26.2 and later split that into {@code Minecraft.gui} (screens) and {@code gui.hud} (the HUD).
 * Loom adds this interface to {@code Gui} at compile time ({@code src/mc12101/loom/fabric.mod.json}) and
 * {@code mixin.GuiBridgeMixin} adds it at run time, so the shared sources' {@code mc.gui.screen()},
 * {@code mc.gui.setScreen(…)}, {@code McCompat.hud().isHidden()} and the rest work unchanged.
 */
public interface GuiBridge {
	/** 1.21.1 draws subtitles with the HUD; nothing is deferred. */
	default void extractDeferredSubtitles() {
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

	/** The toasts (1.21.1's ToastComponent is what 1.21.2 renamed ToastManager). */
	default ToastComponent toastManager() {
		return Minecraft.getInstance().getToasts();
	}

	/** As 26.2's {@code Hud.isHidden}: the HUD is hidden with F1. */
	default boolean isHidden() {
		return Minecraft.getInstance().options.hideGui;
	}
}
