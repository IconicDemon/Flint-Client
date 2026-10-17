package com.flintclient.client.control.menu;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.ui.kit.Kit;

/**
 * Opening and closing: in over 180 ms (a fade and a small rise), out over 120 ms (a fade and a slight drop), both on
 * an ease-out curve so they start at once. With Flint's Animations off (or reduced motion followed) it is instant. A close can be turned round half-way and carries on from how it looks.
 * Pure arithmetic, no allocation.
 */
final class MenuMotion {
	private static final float OPEN_SECONDS = 0.18F;
	private static final float CLOSE_SECONDS = 0.12F;

	/** 0 closed … 1 open, linear in time. */
	private float t;
	private boolean opening;

	void open() {
		turn(true);
		if (instant()) {
			t = 1;
		}
	}

	void close() {
		turn(false);
		if (instant()) {
			t = 0;
		}
	}

	/** Moves on by {@code seconds}; true once a close has finished. */
	boolean advance(float seconds) {
		if (instant()) {
			t = opening ? 1 : 0;
		} else if (opening) {
			t = Math.min(1, t + seconds / OPEN_SECONDS);
		} else {
			t = Math.max(0, t - seconds / CLOSE_SECONDS);
		}
		return !opening && t <= 0;
	}

	boolean opening() {
		return opening;
	}

	/** How open it looks, 0..1 (each direction runs the ease-out on its own progress). */
	float shown() {
		return opening ? easeOut(t) : 1 - easeOut(1 - t);
	}

	/** Layout units the window sits below its place: rising in, dropping slightly out. */
	float offset(float riseUnits, float dropUnits) {
		return moves() ? (opening ? riseUnits : dropUnits) * (1 - shown()) : 0;
	}

	private void turn(boolean open) {
		if (open == opening) {
			return;
		}
		float look = shown();
		opening = open;
		if (t > 0 && t < 1) {
			// The two directions read the curve differently: find the point that looks the same (bisection).
			float lo = 0;
			float hi = 1;
			for (int i = 0; i < 24; i++) {
				t = (lo + hi) / 2;
				if (shown() < look) {
					lo = t;
				} else {
					hi = t;
				}
			}
		}
	}

	private static float easeOut(float x) {
		float u = 1 - Math.clamp(x, 0, 1);
		return 1 - u * u * u;
	}

	/** Animations off, or reduced motion in effect: no movement at all. */
	private static boolean instant() {
		return Kit.reduced() || !ControlState.settings().animations();
	}

	private static boolean moves() {
		return !instant();
	}
}
