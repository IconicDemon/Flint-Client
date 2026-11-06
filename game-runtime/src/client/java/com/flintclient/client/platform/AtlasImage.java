package com.flintclient.client.platform;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.system.MemoryUtil;

/**
 * The mask atlas's texture and the CPU copy of its pixels ({@code render.MaskAtlas}): shapes are written into the copy,
 * changed rows uploaded in one write, and a grown atlas carries the old one's pixels over. Created blank and not uploaded;
 * registered under a new path each time the atlas is replaced (draws collected earlier in a frame keep sampling the old
 * one). Platform class (see {@link Clock}). Render thread.
 */
public final class AtlasImage {
	private final DynamicTexture texture;

	/** A blank (transparent) {@code width × height} atlas, not uploaded yet. */
	public AtlasImage(int width, int height) {
		texture = new DynamicTexture(() -> "Flint mask atlas", width, height, true);
	}

	public int width() {
		return texture.getPixels().getWidth();
	}

	public int height() {
		return texture.getPixels().getHeight();
	}

	/** Sets one pixel of the CPU copy (ARGB). */
	public void setPixel(int x, int y, int argb) {
		texture.getPixels().setPixel(x, y, argb);
	}

	/**
	 * Copies the top {@code rows} rows of {@code from} to the same place here (as wide or wider), one memory copy per row.
	 * (NativeImage.copyFrom copies only part of each row when the widths differ.)
	 */
	public void copyRows(AtlasImage from, int rows) {
		NativeImage source = from.texture.getPixels();
		NativeImage target = texture.getPixels();
		long fromRow = (long) source.getWidth() * source.format().components();
		long toRow = (long) target.getWidth() * target.format().components();
		for (int y = 0; y < Math.min(rows, source.getHeight()); y++) {
			MemoryUtil.memCopy(source.getPointer() + y * fromRow, target.getPointer() + y * toRow, fromRow);
		}
	}

	/** Copies the {@code w × h} block at ({@code fromU}, {@code fromV}) of {@code from} to ({@code u}, {@code v}) here. */
	public void copyRect(AtlasImage from, int fromU, int fromV, int u, int v, int w, int h) {
		from.texture.getPixels().copyRect(texture.getPixels(), fromU, fromV, u, v, w, h, false, false);
	}

	/** Uploads rows [top, bottom) of the CPU copy: whole rows sit together in memory, so it's one write. */
	public void upload(int top, int bottom) {
		com.flintclient.client.compat.McCompat.writeRows(texture.getTexture(), texture.getPixels(), top, bottom);
	}

	/** Registers it as {@code flintclient:<path>} (uploading nothing more), to draw from. */
	public Texture register(String path) {
		Texture handle = new Texture(Texture.id(path));
		Minecraft.getInstance().getTextureManager().register(handle.id(), texture);
		return handle;
	}
}
