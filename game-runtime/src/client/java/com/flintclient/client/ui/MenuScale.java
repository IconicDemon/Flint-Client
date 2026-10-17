package com.flintclient.client.ui;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * The size Flint's own menus (the main menu and its pages) are drawn at: never smaller than Minecraft's automatic GUI
 * scale for the window. A player who picked a small GUI scale for playing (3 on a large screen, say) would otherwise
 * get a menu a fraction of its intended size; a larger GUI scale than automatic is kept as chosen.
 *
 * <p>A menu screen works in "menu units": its {@code width} and {@code height} are the window in menu units
 * ({@link #adopt}), it draws inside one {@code Canvas.beginAtGuiScale(graphics, pixels())} frame, and mouse positions
 * from Minecraft (in GUI units) are converted with {@link #toMenu}. When the player's GUI scale is automatic or larger,
 * menu units are GUI units and nothing changes.
 */
public final class MenuScale {
	private MenuScale() {
	}

	/** Pixels per menu unit: the window's GUI scale, or Minecraft's automatic one when that is larger. */
	public static int pixels() {
		Minecraft minecraft = Minecraft.getInstance();
		Window window = minecraft.getWindow();
		int gui = Math.max(1, window.getGuiScale());
		int auto = Math.max(1, window.calculateScale(0, minecraft.isEnforceUnicode()));
		return Math.max(gui, auto);
	}

	/** Sets {@code screen}'s width and height to the window in menu units (from its {@code init()}). */
	public static void adopt(Screen screen) {
		Window window = Minecraft.getInstance().getWindow();
		int px = pixels();
		screen.width = Math.max(1, window.getWidth() / px);
		screen.height = Math.max(1, window.getHeight() / px);
	}

	/** A position in GUI units (as Minecraft reports the mouse) in menu units. */
	public static double toMenu(double gui) {
		return gui * Math.max(1, Minecraft.getInstance().getWindow().getGuiScale()) / pixels();
	}

	public static MouseButtonEvent toMenu(MouseButtonEvent event) {
		return new MouseButtonEvent(toMenu(event.x()), toMenu(event.y()), event.buttonInfo());
	}
}
