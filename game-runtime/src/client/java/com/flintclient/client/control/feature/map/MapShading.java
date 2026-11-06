package com.flintclient.client.control.feature.map;

/**
 * How the maps colour and shade, worked out from Xaero's World Map and Minimap and written Flint's own way.
 *
 * <p>When a texture is built ({@link #pixel}), from what a region stores (unshaded colour, height) and the map's look:
 * <ul>
 * <li>{@link #SLOPES}: Xaero's "2D" slopes: a column higher than the one north of it is 15% brighter, a lower one 15%
 * darker;</li>
 * <li>{@link #RELIEF}: Xaero's "3D" terrain: sunlight from the north-west on the slope (its rise to the north and to the
 * north-west), quantised to tenths, over an ambient light tinted by the dimension's sky (blue in the Overworld, red in the
 * Nether, white elsewhere). A glowing block (lava, glowstone, sea lanterns) keeps its full colour and only gains a little
 * on lit slopes;</li>
 * <li>terrain depth: the height over 63 as brightness (0.7 to 1.15; 0.9 to 1 with 3D shading); on a cave layer, how far up
 * the layer the floor is (0.7 at its bottom to 1 at its top, in the same bounds);</li>
 * <li>height colours: a colour per height instead of the block's (sea blue, lowland green, hills yellow and brown, peaks
 * white), still shaded.</li>
 * </ul>
 * Water and the void are flat (their look is the scan's, below). Unknown columns stay transparent.
 *
 * <p>When a column is read ({@link #overlay}, {@link #floorLight}, {@link #glow}): see-through blocks over the ground
 * (water, ice, stained glass) are layers, as Xaero's: each adds its own colour times its opacity times what still shows
 * through, and each block of water or ice takes a level of sunlight from what is under it, so the floor of deep water
 * darkens toward 9/24 of its colour. Underground, a floor is as bright as the light on it ((9 + light) / 24), so torch-lit
 * places stand out of dark caves. Version-neutral, allocation-free.
 */
public final class MapShading {
	public static final int FLAT = 0;
	public static final int SLOPES = 1;
	public static final int RELIEF = 2;

	/** The colour of nothing (the End's void, a column with no ground at all), Xaero's deep violet black. */
	public static final int VOID_RGB = 0x0A0017;
	/** A cave layer column that is solid rock all the way down (Xaero's black). */
	public static final int ROCK_RGB = 0x010101;

	/**
	 * The map's look, as the settings choose it. {@code caveTop} and {@code caveDepth} describe a cave layer (its start and
	 * how far down it reads; depth 0 for a surface map).
	 */
	public record Look(int shading, boolean heightTint, boolean heightColors, int seaLevel, int shadowRgb, int caveTop, int caveDepth) {
		public static final Look DEFAULT = new Look(RELIEF, true, false, 63, OVERWORLD_SHADOW);

		public Look(int shading, boolean heightTint, boolean heightColors, int seaLevel, int shadowRgb) {
			this(shading, heightTint, heightColors, seaLevel, shadowRgb, 0, 0);
		}

		public Look(int shading, boolean heightTint, boolean heightColors, int seaLevel) {
			this(shading, heightTint, heightColors, seaLevel, OVERWORLD_SHADOW);
		}

		/** This look for a cave layer read from {@code top} down {@code depth} blocks (or the surface's, for depth 0). */
		public Look cave(int top, int depth) {
			return top == caveTop && depth == caveDepth ? this : new Look(shading, heightTint, heightColors, seaLevel, shadowRgb, top, depth);
		}
	}

	/** The ambient light's tint in 3D shading, per dimension (Xaero's): the Overworld's sky blue, the Nether's red. */
	public static final int OVERWORLD_SHADOW = 0x84ADFF;
	public static final int NETHER_SHADOW = 0xFF0000;
	public static final int OTHER_SHADOW = 0xFFFFFF;

	private MapShading() {
	}

	/** {@link #pixel(int, int, int, int, int, Look)} with the north-west neighbour taken from the plane through the others. */
	public static int pixel(int packed, int h, int hw, int hn, Look look) {
		return pixel(packed, h, hw, hn, hn + hw - h, look);
	}

	/**
	 * The ARGB pixel for a column: {@code packed} as a region stores it, its height {@code h}, and the heights of its west,
	 * north and north-west neighbours ({@code hw}, {@code hn}, {@code hnw}; the column's own height where a neighbour is
	 * unknown).
	 */
	public static int pixel(int packed, int h, int hw, int hn, int hnw, Look look) {
		int kind = packed >>> 24;
		if (kind == MapRegion.UNKNOWN) {
			return 0;
		}
		if (kind == MapRegion.VOID) {
			return 0xFF000000 | packed & 0xFFFFFF;
		}
		int rgb = look.heightColors() && kind != MapRegion.WATER ? heightColor(h, look.seaLevel()) : packed & 0xFFFFFF;
		if (kind == MapRegion.WATER) {
			return 0xFF000000 | rgb;
		}
		boolean glow = kind == MapRegion.GLOW;
		boolean relief = look.shading() == RELIEF;
		float depth = 1;
		if (look.heightTint() && !glow) {
			float raw = look.caveDepth() > 0
				? 0.7F + 0.3F * (h - (look.caveTop() - look.caveDepth())) / look.caveDepth()
				: (h - look.seaLevel() + 63) / 63.0F;
			depth = Math.clamp(raw, relief ? 0.9F : 0.7F, relief ? 1.0F : 1.15F);
		}
		if (look.shading() == FLAT) {
			return 0xFF000000 | scale(rgb, depth);
		}
		int vertical = h - hn;
		if (!relief) {
			return 0xFF000000 | scale(rgb, depth * (vertical > 0 ? 1.15F : vertical < 0 ? 0.85F : 1));
		}
		int diagonal = h - hnw;
		float cos = 0;
		if (vertical >= 0) {
			if (vertical == 1 && diagonal == 1) {
				cos = 1;
			} else {
				float crossX = vertical - diagonal;
				float crossZ = -vertical;
				float cast = 1 - crossZ;
				cos = (float) (cast / Math.sqrt(crossX * crossX + 1 + crossZ * crossZ) / Math.sqrt(2));
			}
		}
		float ambientColored = glow ? 0 : 0.2F;
		float maxDirect = glow ? 2 / 9.0F : 2 / 3.0F;
		float direct = cos >= 1 ? maxDirect : cos > 0 ? (float) Math.ceil(cos * 10) / 10 * maxDirect * 0.88388F : 0;
		float white = (glow ? 1 : 0.5F) + direct;
		int s = look.shadowRgb();
		float r = ((s >> 16 & 255) / 255.0F * ambientColored + white) * depth;
		float g = ((s >> 8 & 255) / 255.0F * ambientColored + white) * depth;
		float b = ((s & 255) / 255.0F * ambientColored + white) * depth;
		return 0xFF000000 | Math.clamp(Math.round((rgb >> 16 & 255) * r), 0, 255) << 16 | Math.clamp(Math.round((rgb >> 8 & 255) * g), 0, 255) << 8
			| Math.clamp(Math.round((rgb & 255) * b), 0, 255);
	}

	/** {@code rgb} with each channel times {@code f}, clamped. */
	public static int scale(int rgb, float f) {
		if (f == 1) {
			return rgb & 0xFFFFFF;
		}
		int r = Math.clamp(Math.round((rgb >> 16 & 255) * f), 0, 255);
		int g = Math.clamp(Math.round((rgb >> 8 & 255) * f), 0, 255);
		int b = Math.clamp(Math.round((rgb & 255) * f), 0, 255);
		return r << 16 | g << 8 | b;
	}

	/** Mixes {@code a} toward {@code b} by {@code t} (0..1), per channel. */
	public static int mix(int a, int b, float t) {
		int r = Math.round((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * t);
		int g = Math.round((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * t);
		int bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
		return r << 16 | g << 8 | bl;
	}

	/** Multiplies two colours channel by channel (a texture's grey times its biome tint). */
	public static int multiply(int a, int b) {
		int r = (a >> 16 & 255) * (b >> 16 & 255) / 255;
		int g = (a >> 8 & 255) * (b >> 8 & 255) / 255;
		int bl = (a & 255) * (b & 255) / 255;
		return r << 16 | g << 8 | bl;
	}

	private static final int[] RAMP_HEIGHTS = {-64, 0, 40, 62, 64, 80, 100, 130, 170, 220, 320};
	private static final int[] RAMP_COLORS = {0x101640, 0x1B2A6B, 0x2D5DA8, 0x4D8FD1, 0xD9C98F, 0x5E9E4A, 0x3E7B36, 0xB5A85A, 0x8C6B45,
		0xD8D8D8, 0xFFFFFF};

	/** A colour for a height: deep blue under the sea, sand at the shore, greens, then hill yellow, rock brown and snow. */
	public static int heightColor(int h, int seaLevel) {
		int y = h + (63 - seaLevel);
		if (y <= RAMP_HEIGHTS[0]) {
			return RAMP_COLORS[0];
		}
		for (int i = 1; i < RAMP_HEIGHTS.length; i++) {
			if (y <= RAMP_HEIGHTS[i]) {
				float t = (y - RAMP_HEIGHTS[i - 1]) / (float) (RAMP_HEIGHTS[i] - RAMP_HEIGHTS[i - 1]);
				return mix(RAMP_COLORS[i - 1], RAMP_COLORS[i], t);
			}
		}
		return RAMP_COLORS[RAMP_COLORS.length - 1];
	}

	// ---------- reading a column (the scanner) ----------

	/** Xaero's light curve: a block lit at {@code light} (0..15) shows (9 + light) / 24 of its colour. */
	public static float floorLight(int light) {
		return (9 + Math.clamp(light, 0, 15)) / 24.0F;
	}

	/**
	 * A layer of see-through blocks over what's below, added to a column being read: its colour {@code rgb} at opacity
	 * {@code alpha} (0..1) lit by the sunlight {@code sun} (0..15) left above it, into {@code acc} (r, g, b, what still shows
	 * through). Returns nothing; {@code acc} carries the sum.
	 */
	public static void overlay(float[] acc, int rgb, float alpha, int sun) {
		float k = alpha * acc[3] * floorLight(sun);
		acc[0] += (rgb >> 16 & 255) * k;
		acc[1] += (rgb >> 8 & 255) * k;
		acc[2] += (rgb & 255) * k;
		acc[3] *= 1 - alpha;
	}

	/** What a column being read shows: its layers ({@code acc}) over the ground {@code rgb} at brightness {@code light}. */
	public static int under(float[] acc, int rgb, float light) {
		float k = acc[3] * light;
		int r = Math.clamp(Math.round(acc[0] + (rgb >> 16 & 255) * k), 0, 255);
		int g = Math.clamp(Math.round(acc[1] + (rgb >> 8 & 255) * k), 0, 255);
		int b = Math.clamp(Math.round(acc[2] + (rgb & 255) * k), 0, 255);
		return r << 16 | g << 8 | b;
	}

	/** A glowing block's colour as Xaero shows it: brightened until its channels add up to at least 407. */
	public static int glow(int rgb) {
		int total = (rgb >> 16 & 255) + (rgb >> 8 & 255) + (rgb & 255);
		if (total >= 407) {
			return rgb & 0xFFFFFF;
		}
		return scale(rgb, total == 0 ? 1 : 407.0F / total);
	}

	/** {@code rgb} as a packed column of {@code kind}. */
	public static int pack(int kind, int rgb) {
		return kind << 24 | rgb & 0xFFFFFF;
	}
}
