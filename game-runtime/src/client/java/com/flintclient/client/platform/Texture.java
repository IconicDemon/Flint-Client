package com.flintclient.client.platform;

import com.flintclient.client.FlintClient;
import com.mojang.blaze3d.platform.NativeImage;
import java.nio.IntBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * A texture Flint made and {@link Frame#blit draws} from: the mask atlas, a card picture. Made from ARGB pixels; registered
 * with Minecraft's texture manager under {@code flintclient:<path>} (one registered under the same path is replaced), and
 * freed with {@link #release}. One made by {@link #upload} can have a rectangle at its top left rewritten
 * ({@link #writeTopLeft}: an animated cape's frames). Platform class (see {@link Clock}). Render thread.
 */
public final class Texture {
	private final Identifier id;
	private final @Nullable DynamicTexture pixels;
	/** {@link #writeTopLeft}'s staging image (made at its first write, kept until {@link #release}) and its pixels as ints. */
	private @Nullable NativeImage staging;
	private @Nullable IntBuffer stagingPixels;

	Texture(Identifier id) {
		this(id, null);
	}

	private Texture(Identifier id, @Nullable DynamicTexture pixels) {
		this.id = id;
		this.pixels = pixels;
	}

	/** The id of a texture of Flint's at {@code path} (not necessarily registered yet). */
	static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(FlintClient.MOD_ID, path);
	}

	/**
	 * A {@code width × height} texture of {@code argb} pixels (row by row), uploaded and registered as {@code flintclient:<path>};
	 * {@code smooth} samples it with linear filtering, clamped at its edges (a picture drawn a little larger or smaller than
	 * its size), otherwise nearest.
	 */
	public static Texture upload(String path, int width, int height, int[] argb, boolean smooth) {
		NativeImage image = new NativeImage(width, height, false);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				image.setPixel(x, y, argb[y * width + x]);
			}
		}
		Identifier id = id(path);
		Pixels texture = new Pixels(id, image, smooth);
		Minecraft.getInstance().getTextureManager().register(id, texture);
		return new Texture(id, texture);
	}

	/**
	 * Writes {@code width × height} pixels at the top left of this texture (made by {@link #upload}, at least that large) and
	 * uploads only that rectangle. {@code pixels} are row by row in the layout of {@code NativeImage}'s memory (R, G, B, A
	 * bytes read as this machine's ints: {@code CapeFrames.Layout.RGBA_BYTES}), so this is one copy into a staging image made
	 * at the first write and reused. Render thread, between frames (not inside a render pass).
	 */
	public void writeTopLeft(int[] pixels, int width, int height) {
		DynamicTexture texture = this.pixels;
		if (texture == null) {
			throw new IllegalStateException("only a texture made by upload can be written");
		}
		NativeImage image = staging;
		IntBuffer ints = stagingPixels;
		if (image == null || ints == null || image.getWidth() != width || image.getHeight() != height) {
			if (image != null) {
				image.close();
			}
			image = new NativeImage(width, height, false);
			ints = MemoryUtil.memIntBuffer(image.getPointer(), width * height);
			staging = image;
			stagingPixels = ints;
		}
		ints.put(0, pixels, 0, width * height);
		com.flintclient.client.compat.McCompat.writeImage(texture, image, 0, 0);
	}

	/** Minecraft's id for it, for code on this engine's side of the platform classes. */
	public Identifier id() {
		return id;
	}

	/** Frees the texture (drawing it afterwards draws Minecraft's missing texture). */
	public void release() {
		Minecraft.getInstance().getTextureManager().release(id);
		NativeImage image = staging;
		staging = null;
		stagingPixels = null;
		if (image != null) {
			image.close();
		}
	}

	/** A texture of pixels, sampled smoothly (linear, clamped at its edges) or as they are. */
	private static final class Pixels extends DynamicTexture {
		Pixels(Identifier id, NativeImage image, boolean smooth) {
			super(id::toString, image);
			if (smooth) {
				this.sampler = com.flintclient.client.compat.McCompat.linearClampSampler();
			}
		}
	}
}
