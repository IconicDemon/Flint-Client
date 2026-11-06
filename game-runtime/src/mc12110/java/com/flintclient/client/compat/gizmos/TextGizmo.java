package com.flintclient.client.compat.gizmos;

/** Minecraft 1.21.11's {@code net.minecraft.gizmos.TextGizmo} for 1.21.10: only its style, which Flint names. */
public final class TextGizmo {
	private TextGizmo() {
	}

	/** A label's colour and size (in 1/16 blocks per text pixel, as 1.21.11), centred on its point. */
	public record Style(int color, float scale) {
		public static final float DEFAULT_SCALE = 0.32F;

		public static Style whiteAndCentered() {
			return new Style(0xFFFFFFFF, DEFAULT_SCALE);
		}

		public static Style forColorAndCentered(int color) {
			return new Style(color, DEFAULT_SCALE);
		}

		public Style withScale(float scale) {
			return new Style(color, scale);
		}
	}
}
