package com.flintclient.client.render;

/** An image's pixels: {@code width × height} ARGB values, row by row. */
public record Pixels(int width, int height, int[] argb) {
	/** The pixel at ({@code x}, {@code y}). */
	public int get(int x, int y) {
		return argb[y * width + x];
	}
}
