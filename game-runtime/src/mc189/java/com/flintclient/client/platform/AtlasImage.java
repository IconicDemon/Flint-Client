package com.flintclient.client.platform;

/**
 * The mask atlas's texture and the CPU copy of its pixels ({@code render.MaskAtlas}), on Minecraft 1.8.9: an int array of
 * ARGB pixels and an OpenGL texture (nearest filtering, as on 26.x) that changed rows are uploaded to in one write. Created
 * blank; the texture is made when it is first uploaded or registered. Platform class (see {@link Clock}). Render thread.
 */
public final class AtlasImage {
	private final int width;
	private final int height;
	private final int[] pixels;
	private int glId = -1;

	/** A blank (transparent) {@code width × height} atlas, not uploaded yet. */
	public AtlasImage(int width, int height) {
		this.width = width;
		this.height = height;
		this.pixels = new int[width * height];
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	/** Sets one pixel of the CPU copy (ARGB). */
	public void setPixel(int x, int y, int argb) {
		pixels[y * width + x] = argb;
	}

	/** Copies the top {@code rows} rows of {@code from} to the same place here (as wide or wider). */
	public void copyRows(AtlasImage from, int rows) {
		int n = Math.min(Math.min(rows, from.height), height);
		for (int y = 0; y < n; y++) {
			System.arraycopy(from.pixels, y * from.width, pixels, y * width, Math.min(from.width, width));
		}
	}

	/** Copies the {@code w × h} block at ({@code fromU}, {@code fromV}) of {@code from} to ({@code u}, {@code v}) here. */
	public void copyRect(AtlasImage from, int fromU, int fromV, int u, int v, int w, int h) {
		for (int y = 0; y < h; y++) {
			System.arraycopy(from.pixels, (fromV + y) * from.width + fromU, pixels, (v + y) * width + u, w);
		}
	}

	/** Uploads rows [top, bottom) of the CPU copy in one write. */
	public void upload(int top, int bottom) {
		int from = Math.max(0, top);
		int to = Math.min(height, bottom);
		if (to > from) {
			Texture.write(texture(), 0, from, width, to - from, pixels, from * width, width);
		}
	}

	/** Its texture, to draw from ({@code path} names it on engines that register textures by name). */
	public Texture register(String path) {
		return new Texture(texture());
	}

	private int texture() {
		if (glId < 0) {
			glId = Texture.create(width, height, false);
		}
		return glId;
	}
}
