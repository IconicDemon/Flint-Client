package com.flintclient.client.cosmetics;

/**
 * What a 3D cosmetic may be (the cosmetics contract's upload limits, which the Store, Admin and Creator enforce): the game
 * refuses anything past them, so a file that got through some other way can't make the game slow or crash. Pure Java.
 */
public final class CosmeticLimits {
	/** Cubes in one model (the Store's limit). */
	public static final int MAX_CUBES = 160;
	/** Bones in one model: far more than any reference item (60). */
	public static final int MAX_BONES = 256;
	public static final int MAX_GEOMETRY_BYTES = 256 * 1024;
	public static final int MAX_ANIMATION_BYTES = 256 * 1024;
	public static final int MAX_TEXTURE_BYTES = 2 * 1024 * 1024;
	/** A texture's side in pixels, every frame of a strip included. */
	public static final int MAX_TEXTURE_SIDE = 1024;
	/** The UV grid the geometry names ({@code texture_width}/{@code texture_height}). */
	public static final int MAX_UV_GRID = 1024;
	public static final int MAX_FRAMES = 12;
	public static final int MIN_FRAME_MS = 50;
	/** An emote's {@code animation_length}. */
	public static final double MAX_EMOTE_SECONDS = 10;
	/** A model animation's length (the reference items' longest is 6 s). */
	public static final double MAX_ANIMATION_SECONDS = 120;
	/** Animations in one file, bones in one animation, and keyframes in one channel. */
	public static final int MAX_ANIMATIONS = 32;
	public static final int MAX_ANIMATED_BONES = 256;
	public static final int MAX_KEYFRAMES = 512;
	/** JSON nested deeper than this is refused before it is parsed (geometry nests 8 deep, animations 7). */
	public static final int MAX_JSON_DEPTH = 24;
	/** Coordinates (pixels, 16 to a block) further out than this: no cosmetic is 256 blocks wide. */
	public static final float MAX_COORDINATE = 4096;
	/** A cube's side in pixels. */
	public static final float MAX_CUBE_SIDE = 1024;
	public static final float MAX_INFLATE = 64;
	public static final int MAX_NAME = 64;

	private CosmeticLimits() {
	}

	/** Whether {@code json}'s brackets nest at most {@link #MAX_JSON_DEPTH} deep (strings skipped): checked before parsing. */
	public static boolean shallow(String json) {
		int depth = 0;
		boolean inString = false;
		for (int i = 0, n = json.length(); i < n; i++) {
			char c = json.charAt(i);
			if (inString) {
				if (c == '\\') {
					i++;
				} else if (c == '"') {
					inString = false;
				}
			} else if (c == '"') {
				inString = true;
			} else if (c == '{' || c == '[') {
				if (++depth > MAX_JSON_DEPTH) {
					return false;
				}
			} else if (c == '}' || c == ']') {
				depth--;
			}
		}
		return true;
	}

	/** Why a file was refused. */
	public static final class Refused extends Exception {
		public Refused(String reason) {
			super(reason, null, false, false);
		}
	}
}
