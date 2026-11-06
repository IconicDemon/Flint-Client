package com.flintclient.client.control.fx;

import com.mojang.blaze3d.pipeline.RenderTarget;

/**
 * Color Saturation's grading on Minecraft 1.21.1: not available (the module says so, {@code McCompat.unavailable}). The
 * settings are kept so they apply unchanged on the versions that have the pass.
 */
public final class ColorGrade {
	private static boolean enabled;

	private ColorGrade() {
	}

	public static void set(boolean on, float hueDegrees, float saturationAmount, float brightnessAmount, float contrastAmount, boolean grey) {
		enabled = on;
	}

	static float clamp(float v) {
		return Float.isNaN(v) ? 0 : Math.max(-1, Math.min(1, v));
	}

	public static boolean active() {
		return false;
	}

	public static boolean available() {
		return false;
	}

	public static void afterWorld(RenderTarget main) {
	}

	public static void release() {
	}
}
