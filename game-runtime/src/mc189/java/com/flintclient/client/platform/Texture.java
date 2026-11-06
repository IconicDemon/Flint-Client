package com.flintclient.client.platform;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.render.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * A texture Flint made and {@link Frame#blit draws} from, on Minecraft 1.8.9: an OpenGL texture of its own (1.8.9's texture
 * manager only knows files). One uploaded under the same path as an earlier one replaces it; {@link #release} frees it.
 * Platform class (see {@link Clock}). Render thread.
 */
public final class Texture {
	private static final Map<String, Texture> BY_PATH = new HashMap<>();

	final int glId;
	private boolean released;

	Texture(int glId) {
		this.glId = glId;
	}

	/** OpenGL's name for it, for this engine's own drawing. */
	public int glId() {
		return glId;
	}

	/**
	 * A {@code width × height} texture of {@code argb} pixels (row by row) under {@code path}; {@code smooth} samples it with
	 * linear filtering, clamped at its edges, otherwise nearest.
	 */
	public static Texture upload(String path, int width, int height, int[] argb, boolean smooth) {
		Texture old = BY_PATH.remove(path);
		if (old != null) {
			old.release();
		}
		// Smooth pictures clamp at their edges; the rest repeat past them (the HUD editor's dot grid and dashes are tiles), as on 26.x.
		Texture texture = new Texture(create(width, height, smooth, !smooth));
		write(texture.glId, 0, 0, width, height, argb, 0, width);
		BY_PATH.put(path, texture);
		return texture;
	}

	/** A new texture with storage for {@code width × height} pixels, sampled as {@code smooth} says. */
	static int create(int width, int height, boolean smooth) {
		return create(width, height, smooth, false);
	}

	/** {@link #create(int, int, boolean)}, repeating past its edges when {@code repeat} (else clamped). */
	static int create(int width, int height, boolean smooth, boolean repeat) {
		int id = GL11.glGenTextures();
		GlStateManager.bindTexture(id);
		int filter = smooth ? GL11.GL_LINEAR : GL11.GL_NEAREST;
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
		int wrap = repeat ? GL11.GL_REPEAT : GL12.GL_CLAMP_TO_EDGE;
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, wrap);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, wrap);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, width, height, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, (IntBuffer) null);
		return id;
	}

	private static IntBuffer upload = ByteBuffer.allocateDirect(256 * 256 * 4).order(ByteOrder.nativeOrder()).asIntBuffer();

	/** Writes the {@code w × h} block of {@code argb} (rows {@code stride} long, from {@code offset}) to ({@code x}, {@code y}). */
	static void write(int glId, int x, int y, int w, int h, int[] argb, int offset, int stride) {
		if (w <= 0 || h <= 0) {
			return;
		}
		if (upload.capacity() < w * h) {
			upload = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
		}
		upload.clear();
		for (int row = 0; row < h; row++) {
			upload.put(argb, offset + row * stride, w);
		}
		upload.flip();
		GlStateManager.bindTexture(glId);
		GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
		GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
		GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
		GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
		GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, w, h, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, upload);
	}

	/** Frees the texture (drawing it afterwards draws nothing). */
	public void release() {
		if (!released) {
			released = true;
			GlStateManager.deleteTextures(glId);
		}
	}
}
