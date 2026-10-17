package com.flintclient.client.control.feature;

/**
 * Light Overlay's arithmetic: which light a marker shows and what colour it gets. Pure Java.
 *
 * <p>Minecraft's own rules: block light comes from torches and other light sources; sky light from the open sky, darkened
 * at night and in storms by the world's "sky darkening". Since Minecraft 1.18 most hostile mobs spawn only where block
 * light is 0 (and the overall light is low), but other rules (the block below, space, the biome, the spawn cap) decide
 * too: a "dangerous" marker means "dark enough", never "a mob will spawn here".
 */
public final class LightRules {
	public static final int BLOCK = 0;
	public static final int SKY = 1;
	public static final int BOTH = 2;

	private LightRules() {
	}

	/** The light level to show: block light, sky light (minus the sky darkening when counted), or the brighter of the two. */
	public static int level(int blockLight, int skyLight, int skyDarken, int type, boolean countDarkening) {
		int sky = Math.max(0, skyLight - (countDarkening ? skyDarken : 0));
		return switch (type) {
			case SKY -> sky;
			case BOTH -> Math.max(blockLight, sky);
			default -> blockLight;
		};
	}

	/** Whether {@code level} is at or below the danger level. */
	public static boolean dark(int level, int dangerLevel) {
		return level <= dangerLevel;
	}

	/**
	 * The marker colour ({@code 0xRRGGBB}): with a gradient, the danger colour at or below the danger level fading to the
	 * safe colour at the safest level; static: danger or safe, nothing in between.
	 */
	public static int color(int level, int dangerLevel, int safestLevel, boolean gradient, int dangerRgb, int safeRgb) {
		if (level <= dangerLevel) {
			return dangerRgb;
		}
		if (!gradient || level >= safestLevel || safestLevel <= dangerLevel) {
			return safeRgb;
		}
		float t = (level - dangerLevel) / (float) (safestLevel - dangerLevel);
		return mix(dangerRgb, safeRgb, t);
	}

	static int mix(int a, int b, float t) {
		int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
		int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
		int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
		return r << 16 | g << 8 | bl;
	}
}
