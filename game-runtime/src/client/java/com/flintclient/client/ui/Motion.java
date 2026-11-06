package com.flintclient.client.ui;

import com.flintclient.client.platform.Clock;
import com.flintclient.client.platform.Services;

/**
 * Frame-time-aware easing for Flint's UI. Values approach their target exponentially using the real
 * time since the last frame, so a transition takes the same time at 30 or 240 FPS and picks up from
 * wherever it is when the target changes (no restarts or snapping).
 */
public final class Motion {
	/** Hover and focus colour changes. */
	public static final float HOVER = 0.075F;
	/** The quick settle after a press. */
	public static final float PRESS = 0.11F;

	private static long lastFrameNanos;
	private static float frameSeconds;

	private Motion() {
	}

	/** Called once at the start of each Flint frame (by {@link FlintFrame#begin}). */
	public static void tickFrame() {
		long now = Clock.nanos();
		frameSeconds = lastFrameNanos == 0 ? 0 : Math.min((now - lastFrameNanos) / 1.0E9F, 0.1F);
		lastFrameNanos = now;
	}

	/** Seconds since the previous frame (capped at 0.1 s, so a stall never jumps animations). */
	public static float frameSeconds() {
		return frameSeconds;
	}

	/** Moves {@code current} towards {@code target}; {@code tau} is the time constant in seconds. */
	public static float approach(float current, float target, float tau) {
		return approach(current, target, tau, reduced());
	}

	/**
	 * {@link #approach(float, float, float)} with the reduced-motion decision made by the caller (the kit
	 * applies its own preferences to it); {@code reduced} jumps straight to the target.
	 */
	public static float approach(float current, float target, float tau, boolean reduced) {
		if (reduced || tau <= 0) {
			return target;
		}
		float next = current + (target - current) * (1.0F - (float) Math.exp(-frameSeconds / tau));
		return Math.abs(target - next) < 0.002F ? target : next;
	}

	/**
	 * The game's reduced-motion signal ({@link Services#reducedMotion}): on 26.x a panorama speed of 0 (Minecraft has no "reduce
	 * motion" setting), so Flint then switches transitions off and shows everything immediately.
	 */
	public static boolean reduced() {
		return Services.reducedMotion();
	}

	/**
	 * Moves {@code current} towards {@code target} at a constant rate: 0 to 1 in {@code millis} of real frame time (the same at
	 * 30 or 360 FPS; a change of target mid-way turns back from where it is). Draw the result through an ease
	 * ({@link #smooth}, {@link #easeOut}) for a fade that starts and ends softly.
	 */
	public static float toward(float current, float target, float millis) {
		if (reduced() || millis <= 0) {
			return target;
		}
		float delta = frameSeconds * 1000.0F / millis;
		return current < target ? Math.min(target, current + delta) : Math.max(target, current - delta);
	}

	/** Smoothstep: eases in and out (hover fades). */
	public static float smooth(float t) {
		float u = Math.clamp(t, 0.0F, 1.0F);
		return u * u * (3.0F - 2.0F * u);
	}

	/** Ease-out cubic for one-shot entrances. */
	public static float easeOut(float t) {
		float u = 1.0F - Math.clamp(t, 0.0F, 1.0F);
		return 1.0F - u * u * u;
	}
}
