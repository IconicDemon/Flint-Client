package com.flintclient.client.ui.kit;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.Motion;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;

/**
 * Shared kit state and drawing helpers: the bound {@link KitPrefs}, the focus ring and the outline width,
 * and allocation-free number formatting for values drawn every frame.
 */
public final class Kit {
	/** Minecraft numbers mouse buttons from 1 (SDL): left 1, middle 2, right 3. */
	public static final int LEFT_BUTTON = InputConstants.MOUSE_BUTTON_LEFT;
	public static final int RIGHT_BUTTON = InputConstants.MOUSE_BUTTON_RIGHT;
	/** How long the pointer rests on a control before its tooltip shows (keyboard focus shows it at once). */
	public static final long TOOLTIP_DELAY_MILLIS = 350;
	/** U+2212, the typographic minus (Inter has it; it is as wide as a digit). */
	public static final char MINUS = '−';
	/** The longest {@link #format} result: a minus and 19 digits, or a minus, 18 digits, the point and one decimal. */
	public static final int FORMAT_CHARS = 21;

	private static KitPrefs prefs = KitPrefs.DEFAULT;

	private Kit() {
	}

	public static KitPrefs prefs() {
		return prefs;
	}

	/**
	 * Follows {@code prefs} from now on: interface size, contrast and motion for kit screens and whatever
	 * else asks {@link #reduced()} (the HUD). Flint's main menu keeps following Minecraft's own signal
	 * ({@link Motion#reduced()}) alone, so these settings never change how it moves. Render thread.
	 */
	public static void bind(KitPrefs prefs) {
		Kit.prefs = prefs;
	}

	/**
	 * True when the kit's transitions are skipped and everything is shown at once: "Animations" is off, or
	 * "Follow Minecraft's reduced motion" is on and Minecraft signals it ({@link Motion#reduced()}).
	 */
	public static boolean reduced() {
		return motionReduced(prefs, Motion.reduced());
	}

	/** {@link Motion#approach(float, float, float)} under the kit's motion preferences ({@link #reduced()}). */
	public static float approach(float current, float target, float tau) {
		return Motion.approach(current, target, tau, reduced());
	}

	static boolean motionReduced(KitPrefs prefs, boolean minecraftReduced) {
		return !prefs.animationsEnabled() || (prefs.followMinecraftReducedMotion() && minecraftReduced);
	}

	/** Thin outlines: one pixel, two from 6 pixels per unit up (as the menu's borders). */
	public static int hairline(Canvas c) {
		return Math.max(1, Math.round(c.scale() / 4.0F));
	}

	/**
	 * The keyboard focus ring: a one-pixel ember outline (two in high contrast) {@link Tokens#FOCUS_GAP} units
	 * outside the control, its radius following the control's, faded in by {@code amount}. Never shown for
	 * the pointer (nodes ease {@code focus} only for keyboard focus).
	 */
	public static void focusRing(Canvas c, float x, float y, float w, float h, float radius, float amount) {
		if (amount <= 0.01F) {
			return;
		}
		float g = Tokens.FOCUS_GAP;
		int x0 = c.px(x - g);
		int y0 = c.px(y - g);
		int x1 = c.px(x + w + g);
		int y1 = c.px(y + h + g);
		int width = prefs.highContrast() ? 2 : 1;
		c.ring(x0, y0, x1 - x0, y1 - y0, c.px(radius + g), width, Palette.withAlpha(Tokens.ACCENT, amount));
	}

	/** The click Minecraft's own buttons make. */
	public static void clickSound() {
		AbstractWidget.playButtonClickSound(Minecraft.getInstance().getSoundManager());
	}

	/**
	 * Writes {@code value} with {@code decimals} decimals (0..3) into {@code out} and returns the length:
	 * no allocation, for numbers redrawn every frame. Negative values use {@link #MINUS}. {@code out} needs
	 * room for {@link #FORMAT_CHARS} characters to hold any value (huge ones saturate at {@code Long.MAX_VALUE}
	 * in the last decimal place); a shorter buffer is enough for values known to be shorter.
	 */
	public static int format(float value, int decimals, char[] out) {
		int places = Math.clamp(decimals, 0, 3);
		long factor = places == 0 ? 1 : places == 1 ? 10 : places == 2 ? 100 : 1000;
		long scaled = Math.round(Math.abs((double) value) * factor);
		int n = 0;
		if (value < 0 && scaled != 0) {
			out[n++] = MINUS;
		}
		long whole = scaled / factor;
		long fraction = scaled % factor;
		// Whole part, most significant digit first.
		long divisor = 1;
		while (whole / divisor >= 10) {
			divisor *= 10;
		}
		for (; divisor > 0; divisor /= 10) {
			out[n++] = (char) ('0' + (whole / divisor) % 10);
		}
		if (places > 0) {
			out[n++] = '.';
			for (long d = factor / 10; d > 0; d /= 10) {
				out[n++] = (char) ('0' + (fraction / d) % 10);
			}
		}
		return n;
	}
}
