package com.flintclient.client.control.model;

/**
 * Hue, saturation and value, the colour picker's three axes (its square is saturation across and value down, its bar the
 * hue). Pure arithmetic on {@code 0xRRGGBB} colours, the same on every Minecraft line.
 */
public final class Hsv {
	private Hsv() {
	}

	/** {@code 0xRRGGBB} for a hue in degrees (any number; it wraps) and a saturation and value from 0 to 1. */
	public static int rgb(float hue, float saturation, float value) {
		float h = ((hue % 360) + 360) % 360 / 60;
		float s = Math.clamp(saturation, 0, 1);
		float v = Math.clamp(value, 0, 1);
		float c = v * s;
		float x = c * (1 - Math.abs(h % 2 - 1));
		float m = v - c;
		float r;
		float g;
		float b;
		switch ((int) h) {
			case 0 -> { r = c; g = x; b = 0; }
			case 1 -> { r = x; g = c; b = 0; }
			case 2 -> { r = 0; g = c; b = x; }
			case 3 -> { r = 0; g = x; b = c; }
			case 4 -> { r = x; g = 0; b = c; }
			default -> { r = c; g = 0; b = x; }
		}
		return channel(r + m) << 16 | channel(g + m) << 8 | channel(b + m);
	}

	private static int channel(float f) {
		return Math.clamp(Math.round(f * 255), 0, 255);
	}

	/**
	 * {@code {hue, saturation, value}} for {@code 0xRRGGBB}. A grey has no hue of its own: it keeps {@code keepHue}, so the
	 * bar doesn't jump when the square's thumb reaches its left edge or its bottom.
	 */
	public static float[] of(int rgb, float keepHue) {
		float r = (rgb >> 16 & 0xFF) / 255F;
		float g = (rgb >> 8 & 0xFF) / 255F;
		float b = (rgb & 0xFF) / 255F;
		float max = Math.max(r, Math.max(g, b));
		float min = Math.min(r, Math.min(g, b));
		float delta = max - min;
		float hue = keepHue;
		if (delta > 0) {
			if (max == r) {
				hue = 60 * (((g - b) / delta) % 6);
			} else if (max == g) {
				hue = 60 * ((b - r) / delta + 2);
			} else {
				hue = 60 * ((r - g) / delta + 4);
			}
			hue = (hue + 360) % 360;
		}
		float saturation = max == 0 ? 0 : delta / max;
		return new float[] {hue, saturation, max};
	}
}
