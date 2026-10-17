package com.flintclient.client.graphics;

/**
 * Flint's own GPU textures, by kind: how many are alive and how many bytes they hold. The code that creates
 * and frees them reports here, so a texture that outlives its screen shows up (Flint Graphics Core warns
 * when menu textures are still alive in a world). Bytes are the texture's own size (one RGBA8 level);
 * Minecraft's DynamicTexture also keeps a CPU copy of the same size. Reporting is a couple of array writes
 * and never allocates. Render thread only.
 */
public final class GpuResourceTracker {
	public enum Kind {
		/** The menu's shape atlas (render/MaskAtlas), including textures replaced this frame. */
		ATLAS("shape atlas"),
		/** The menu artwork (ui/Backdrop). */
		BACKDROP("backdrop"),
		/** Screenshot thumbnails and the viewer's picture (ScreenshotsScreen). */
		SCREENSHOT("screenshots"),
		/** The scene behind module settings previews (render/Picture), while the Right Shift menu is open. */
		PREVIEW("module preview");

		private final String label;

		Kind(String label) {
			this.label = label;
		}
	}

	private static final Kind[] KINDS = Kind.values();
	private static final GpuResourceTracker SHARED = new GpuResourceTracker();

	private final int[] counts = new int[KINDS.length];
	private final long[] bytes = new long[KINDS.length];
	private int unbalanced;

	GpuResourceTracker() {
	}

	public static GpuResourceTracker get() {
		return SHARED;
	}

	/** Bytes of a {@code width}×{@code height} RGBA8 texture. */
	public static long rgba8(int width, int height) {
		return (long) width * height * 4;
	}

	public void register(Kind kind, long size) {
		counts[kind.ordinal()]++;
		bytes[kind.ordinal()] += size;
	}

	/**
	 * A release with nothing alive of that kind, or sizes that don't add up (bytes left once the last one
	 * is released), is a bookkeeping error: counted as unbalanced, never taken below zero.
	 */
	public void release(Kind kind, long size) {
		int i = kind.ordinal();
		if (counts[i] == 0) {
			unbalanced++;
			return;
		}
		counts[i]--;
		bytes[i] -= size;
		if (bytes[i] < 0 || (counts[i] == 0 && bytes[i] != 0)) {
			unbalanced++;
			bytes[i] = counts[i] == 0 ? 0 : Math.max(0, bytes[i]);
		}
	}

	public int count(Kind kind) {
		return counts[kind.ordinal()];
	}

	public long bytes(Kind kind) {
		return bytes[kind.ordinal()];
	}

	/** Releases that found nothing to release (should stay 0). */
	public int unbalanced() {
		return unbalanced;
	}

	/** Every kind, for example "shape atlas 1 (262144 bytes), backdrop 0, screenshots 0". */
	public String describe() {
		StringBuilder line = new StringBuilder();
		for (Kind kind : KINDS) {
			if (!line.isEmpty()) {
				line.append(", ");
			}
			append(line, kind);
		}
		if (unbalanced > 0) {
			line.append(", unbalanced releases ").append(unbalanced);
		}
		return line.toString();
	}

	/** Only the kinds with live textures, or "" when there are none. */
	public String describeLive() {
		StringBuilder line = new StringBuilder();
		for (Kind kind : KINDS) {
			if (counts[kind.ordinal()] > 0) {
				if (!line.isEmpty()) {
					line.append(", ");
				}
				append(line, kind);
			}
		}
		return line.toString();
	}

	private void append(StringBuilder line, Kind kind) {
		int i = kind.ordinal();
		line.append(kind.label).append(' ').append(counts[i]);
		if (counts[i] > 0) {
			line.append(" (").append(bytes[i]).append(" bytes)");
		}
	}
}
