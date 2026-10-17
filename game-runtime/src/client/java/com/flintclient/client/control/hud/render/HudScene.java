package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.model.ModuleRegistry;
import org.jspecify.annotations.Nullable;

/**
 * Where each module of a layout was drawn in the last HUD editor frame, in GUI units, in registry order
 * ({@link HudRenderer#drawScene}): drawn with its content, shown as an outline (it has nothing to show here,
 * with the reason), or off. The editor hit-tests, snaps and outlines with these rectangles, so what it moves
 * is exactly what the HUD draws. Reused for the whole editor session.
 */
public final class HudScene {
	public static final int OFF = 0;
	/** Drawn with its real content. */
	public static final int SHOWN = 1;
	/** On, but with nothing to show here (ping in singleplayer, no effects): the editor draws an outline. */
	public static final int OUTLINE = 2;

	private final int[] state = new int[ModuleRegistry.size()];
	private final float[] rects = new float[4 * ModuleRegistry.size()];
	private final @Nullable String[] reasons = new String[ModuleRegistry.size()];

	public int size() {
		return state.length;
	}

	/** {@link #OFF}, {@link #SHOWN} or {@link #OUTLINE} for the module at registry index {@code i}. */
	public int state(int i) {
		return state[i];
	}

	public float x(int i) {
		return rects[4 * i];
	}

	public float y(int i) {
		return rects[4 * i + 1];
	}

	public float width(int i) {
		return rects[4 * i + 2];
	}

	public float height(int i) {
		return rects[4 * i + 3];
	}

	/** Why an {@link #OUTLINE} module shows nothing ("Shows on multiplayer servers"), else null. */
	public @Nullable String reason(int i) {
		return reasons[i];
	}

	/** The topmost module (drawn last) whose rectangle contains the point, or -1. */
	public int hit(float px, float py) {
		for (int i = state.length - 1; i >= 0; i--) {
			if (state[i] != OFF && px >= x(i) && py >= y(i) && px < x(i) + width(i) && py < y(i) + height(i)) {
				return i;
			}
		}
		return -1;
	}

	void set(int i, int value, float x, float y, float w, float h, @Nullable String reason) {
		state[i] = value;
		rects[4 * i] = x;
		rects[4 * i + 1] = y;
		rects[4 * i + 2] = w;
		rects[4 * i + 3] = h;
		reasons[i] = reason;
	}

	void off(int i) {
		state[i] = OFF;
		reasons[i] = null;
	}
}
