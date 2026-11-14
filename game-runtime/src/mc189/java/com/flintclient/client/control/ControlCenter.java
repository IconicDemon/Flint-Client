package com.flintclient.client.control;

import com.flintclient.client.mc.KeyEvent;
import com.flintclient.client.mc.MouseButtonEvent;
import com.flintclient.client.platform.KeyInput;
import com.flintclient.client.platform.MenuHost;
import org.jspecify.annotations.Nullable;

/**
 * The Right Shift menu's key as Flint's main menu asks about it, on Minecraft 1.8.9: Flint's own key binding ({@link MenuHost}),
 * and the window it opens over the main menu, which comes back to it. In a world the menu key opens the HUD page instead
 * ({@code legacy.Flint.afterInput}). The 26.x ControlCenter (key handling, the HUD hooks) isn't compiled on 1.8.9.
 */
public final class ControlCenter {
	private ControlCenter() {
	}

	/** {@code event} is the menu key. */
	public static boolean isOpenKey(KeyEvent event) {
		return MenuHost.isOpenKey(KeyInput.of(event.key()));
	}

	/** The mouse button is the menu key (when it is bound to one). */
	public static boolean isOpenKey(MouseButtonEvent event) {
		return MenuHost.isOpenButton(event.x(), event.y(), event.button());
	}

	/** Opens the Right Shift window over {@code parent}; closing it comes back there. */
	public static void requestOpen(@Nullable Object parent) {
		if (!MenuHost.failed()) {
			MenuHost.openWindow(parent);
		}
	}
}
