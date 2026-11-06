package com.flintclient.client.ui;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * The size Flint's own menus (the main menu and its pages) are drawn at, independent of Minecraft's GUI scale: it follows the
 * window's height, about 370 menu units tall (a maximized 1440p window: 3.7, a 1080p one: 2.7), in tenth steps, from 2
 * (1280×720: small text stays readable; smaller windows go lower only to fit) to 3.7 (the menu stays a menu, not a poster). A player's GUI scale, automatic or chosen, changes nothing
 * here: Minecraft's automatic scale on a large screen (5 or 6) made every part of the menu far larger than it was designed
 * for.
 *
 * <p>A menu screen works in "menu units": its {@code width} and {@code height} are the window in menu units
 * ({@link #adopt}), it draws inside one {@code Canvas.beginAtGuiScale(graphics, pixels())} frame (which rounds every edge to a
 * whole pixel and rasterises text for its exact size), and mouse positions from Minecraft (in GUI units) are converted with
 * {@link #toMenu}.
 */
public final class MenuScale {
	/** The menu's height in units the scale aims for. */
	static final float DESIGN_HEIGHT = 370.0F;
	/** The width it needs at least (the column, the top bar's two ends and the What's new card side by side). */
	static final float MIN_WIDTH = 520.0F;
	/** The layout's smallest height in units (Minecraft's own smallest GUI is 240 tall). */
	static final float MIN_HEIGHT = 240.0F;
	/** Below this the menu's small text (its version line, a card's second lines) gets too small to read. */
	static final float MIN_SCALE = 2.0F;
	static final float MAX_SCALE = 3.7F;
	/** Scales come in tenths. */
	static final float STEPS = 10.0F;

	private MenuScale() {
	}

	/** Pixels per menu unit for the current window (fractional: tenth steps). */
	public static float pixels() {
		Window window = Minecraft.getInstance().getWindow();
		return forWindow(window.getWidth(), window.getHeight());
	}

	/**
	 * Pixels per menu unit for a window of {@code width} × {@code height} pixels: its height over {@link #DESIGN_HEIGHT}, in
	 * tenth steps, from 2 to 3.7; a window too narrow for that, or too small for 2, gets the largest tenth step that still fits
	 * the layout's least size (never under 1).
	 */
	static float forWindow(int width, int height) {
		float wanted = Math.round(height / DESIGN_HEIGHT * STEPS) / STEPS;
		float scale = Math.clamp(wanted, MIN_SCALE, MAX_SCALE);
		float fits = (float) Math.floor(Math.min(width / MIN_WIDTH, height / MIN_HEIGHT) * STEPS) / STEPS;
		return Math.max(1.0F, Math.min(scale, fits));
	}

	/** Sets {@code screen}'s width and height to the window in menu units (from its {@code init()}). */
	public static void adopt(Screen screen) {
		Window window = Minecraft.getInstance().getWindow();
		float px = pixels();
		screen.width = Math.max(1, (int) (window.getWidth() / px));
		screen.height = Math.max(1, (int) (window.getHeight() / px));
	}

	/** A position in GUI units (as Minecraft reports the mouse) in menu units. */
	public static double toMenu(double gui) {
		return gui * Math.max(1, Minecraft.getInstance().getWindow().getGuiScale()) / pixels();
	}

	public static MouseButtonEvent toMenu(MouseButtonEvent event) {
		return new MouseButtonEvent(toMenu(event.x()), toMenu(event.y()), event.buttonInfo());
	}
}
