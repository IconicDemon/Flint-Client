package com.flintclient.client.compat.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;

/**
 * The bridge between 1.21.1's raw input calls and the shared sources' events: an override in 1.21.1's form makes the
 * event (the line's rules write that), and a call with an event on one of Minecraft's own widgets goes through here.
 */
public final class Events {
	/** Presses of the same button closer together than this (milliseconds) make a double click, as 1.21.9's MouseHandler. */
	private static final long DOUBLE_CLICK_MS = 250;
	private static long lastClickTime;
	private static int lastClickButton = -1;
	private static double lastClickX;
	private static double lastClickY;
	private static long lastAskedAt = Long.MIN_VALUE;
	private static boolean lastWasDouble;

	private Events() {
	}

	/** The modifier keys held now (GLFW's bits: shift 1, control 2, alt 4, super 8). */
	public static int modifiers() {
		long window = Minecraft.getInstance().getWindow().getWindow();
		int mods = 0;
		if (InputConstants.isKeyDown(window, InputConstants.KEY_LSHIFT) || InputConstants.isKeyDown(window, InputConstants.KEY_RSHIFT)) {
			mods |= 1;
		}
		if (InputConstants.isKeyDown(window, InputConstants.KEY_LCONTROL) || InputConstants.isKeyDown(window, InputConstants.KEY_RCONTROL)) {
			mods |= 2;
		}
		if (InputConstants.isKeyDown(window, InputConstants.KEY_LALT) || InputConstants.isKeyDown(window, InputConstants.KEY_RALT)) {
			mods |= 4;
		}
		if (InputConstants.isKeyDown(window, InputConstants.KEY_LWIN) || InputConstants.isKeyDown(window, InputConstants.KEY_RWIN)) {
			mods |= 8;
		}
		return mods;
	}

	public static MouseButtonEvent mouse(double x, double y, int button) {
		return new MouseButtonEvent(x, y, new MouseButtonInfo(button, modifiers()));
	}

	/**
	 * Whether a press of {@code button} at x, y is the second of a double click (the same button within 250 ms, a few units
	 * apart). A press is asked about by each screen and widget it passes through, so the answer is kept for that press.
	 */
	public static boolean doubleClick(double x, double y, int button) {
		long now = Util.getMillis();
		if (now == lastAskedAt && button == lastClickButton && x == lastClickX && y == lastClickY) {
			return lastWasDouble;
		}
		lastWasDouble = button == lastClickButton && now - lastClickTime < DOUBLE_CLICK_MS && Math.abs(x - lastClickX) < 4
			&& Math.abs(y - lastClickY) < 4;
		lastClickTime = lastWasDouble ? 0 : now;
		lastAskedAt = now;
		lastClickButton = button;
		lastClickX = x;
		lastClickY = y;
		return lastWasDouble;
	}

	public static KeyEvent key(int key, int scancode, int modifiers) {
		return new KeyEvent(key, scancode, modifiers);
	}

	public static CharacterEvent character(char c, int modifiers) {
		return new CharacterEvent(c, modifiers);
	}

	// ---------- calls with an event on Minecraft's own widgets and screens ----------

	public static boolean keyPressed(GuiEventListener target, KeyEvent event) {
		return target.keyPressed(event.key(), event.scancode(), event.modifiers());
	}

	public static boolean keyReleased(GuiEventListener target, KeyEvent event) {
		return target.keyReleased(event.key(), event.scancode(), event.modifiers());
	}

	public static boolean charTyped(GuiEventListener target, CharacterEvent event) {
		boolean any = false;
		for (char c : Character.toChars(event.codepoint())) {
			any |= target.charTyped(c, event.modifiers());
		}
		return any;
	}

	public static boolean mouseClicked(GuiEventListener target, MouseButtonEvent event, boolean doubleClick) {
		return target.mouseClicked(event.x(), event.y(), event.button());
	}

	public static boolean mouseReleased(GuiEventListener target, MouseButtonEvent event) {
		return target.mouseReleased(event.x(), event.y(), event.button());
	}

	public static boolean mouseDragged(GuiEventListener target, MouseButtonEvent event, double dx, double dy) {
		return target.mouseDragged(event.x(), event.y(), event.button(), dx, dy);
	}
}
