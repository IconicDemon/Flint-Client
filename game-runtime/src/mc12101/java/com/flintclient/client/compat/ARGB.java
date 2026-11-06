package com.flintclient.client.compat;

/** Packed 0xAARRGGBB colours: the helpers 1.21.2 added as {@code net.minecraft.util.ARGB}, which 1.21.1 doesn't have. */
public final class ARGB {
	private ARGB() {
	}

	public static int alpha(int argb) {
		return argb >>> 24;
	}

	public static int red(int argb) {
		return argb >> 16 & 0xFF;
	}

	public static int green(int argb) {
		return argb >> 8 & 0xFF;
	}

	public static int blue(int argb) {
		return argb & 0xFF;
	}

	public static int color(int alpha, int red, int green, int blue) {
		return (alpha & 0xFF) << 24 | (red & 0xFF) << 16 | (green & 0xFF) << 8 | blue & 0xFF;
	}

	public static int color(int red, int green, int blue) {
		return color(255, red, green, blue);
	}

	/** {@code rgb} with the alpha {@code alpha} (0..255). */
	public static int color(int alpha, int rgb) {
		return alpha << 24 | rgb & 0xFFFFFF;
	}

	/** {@code rgb} with the alpha {@code alpha} (0..1). */
	public static int color(float alpha, int rgb) {
		return as8BitChannel(alpha) << 24 | rgb & 0xFFFFFF;
	}

	public static int white(float alpha) {
		return as8BitChannel(alpha) << 24 | 0xFFFFFF;
	}

	public static int opaque(int argb) {
		return argb | 0xFF000000;
	}

	public static int multiply(int a, int b) {
		if (a == -1) {
			return b;
		}
		if (b == -1) {
			return a;
		}
		return color(alpha(a) * alpha(b) / 255, red(a) * red(b) / 255, green(a) * green(b) / 255, blue(a) * blue(b) / 255);
	}

	public static int scaleRGB(int argb, float scale) {
		return color(alpha(argb), Math.clamp((int) (red(argb) * scale), 0, 255), Math.clamp((int) (green(argb) * scale), 0, 255),
			Math.clamp((int) (blue(argb) * scale), 0, 255));
	}

	public static int lerp(float t, int from, int to) {
		return color(lerpChannel(t, alpha(from), alpha(to)), lerpChannel(t, red(from), red(to)), lerpChannel(t, green(from), green(to)),
			lerpChannel(t, blue(from), blue(to)));
	}

	private static int lerpChannel(float t, int from, int to) {
		return from + (int) Math.floor(t * (to - from));
	}

	public static int colorFromFloat(float alpha, float red, float green, float blue) {
		return color(as8BitChannel(alpha), as8BitChannel(red), as8BitChannel(green), as8BitChannel(blue));
	}

	public static int as8BitChannel(float value) {
		return (int) Math.floor(value * 255.0F);
	}

	public static float alphaFloat(int argb) {
		return alpha(argb) / 255.0F;
	}

	public static float redFloat(int argb) {
		return red(argb) / 255.0F;
	}

	public static float greenFloat(int argb) {
		return green(argb) / 255.0F;
	}

	public static float blueFloat(int argb) {
		return blue(argb) / 255.0F;
	}

	/** 0xAARRGGBB to NativeImage's 0xAABBGGRR, and back (the same swap). */
	public static int toABGR(int argb) {
		return argb & 0xFF00FF00 | (argb & 0xFF0000) >> 16 | (argb & 0xFF) << 16;
	}

	public static int fromABGR(int abgr) {
		return toABGR(abgr);
	}
}
