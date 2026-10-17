package com.flintclient.client.control.editor;

import com.flintclient.client.FlintClient;
import com.flintclient.client.render.Canvas;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The editor's repeating marks, the dot grid and the dashed outlines, each drawn as one textured quad over a
 * tiny tile that repeats, instead of one fill per dot or dash.
 *
 * <p>Why: Minecraft files every GUI element by checking it against the elements already drawn above the last
 * one it overlaps ({@code GuiRenderState.navigateToAboveHighestElementWithIntersectingBounds}), so hundreds of
 * separate dots and dashes that never overlap each other cost a check against all of the others each, every
 * frame (about two million checks for the grid alone at GUI scale 2 on a 1080p window). A repeated tile is one
 * element whatever the screen size. Minecraft's own tiled backgrounds work the same way: a blit whose texture
 * coordinates run past the texture, on a texture whose sampler repeats ({@code DynamicTexture} uses
 * {@code SamplerCache.getRepeat(NEAREST)}).
 *
 * <p>Each tile is a few hundred pixels at most and is made again only when its size changes (a new GUI scale);
 * a replaced tile is freed at the next frame, once nothing can still be drawing it, and every tile is freed when
 * the editor closes. Render thread.
 */
public final class EditorPatterns {
	private final Tile dots = new Tile("editor_dots");
	private final Tile dashesAcross = new Tile("editor_dash_h");
	private final Tile dashesDown = new Tile("editor_dash_v");

	/** Frees tiles replaced during the last frame; call at the start of each editor frame. */
	public void beginFrame() {
		dots.freeRetired();
		dashesAcross.freeRetired();
		dashesDown.freeRetired();
	}

	/** Frees every tile (the editor closed). */
	public void release() {
		dots.release();
		dashesAcross.release();
		dashesDown.release();
	}

	/**
	 * Square dots {@code dot} pixels wide every {@code spacing} pixels, the first at {@code (x0, y0)}, over
	 * {@code (x0, y0)..(x1, y1)} in canvas pixels.
	 */
	public void dots(Canvas c, int x0, int y0, int x1, int y1, int spacing, int dot, int argb) {
		if (x1 <= x0 || y1 <= y0 || spacing <= 0) {
			return;
		}
		int size = Math.max(1, spacing);
		Identifier id = dots.get(size, size, Math.min(dot, size), Math.min(dot, size));
		blit(c, id, x0, y0, x1 - x0, y1 - y0, size, size, argb);
	}

	/** One dashed line {@code thick} pixels across: along x from ({@code x0}, {@code y0}) when {@code across}, else down. */
	public void dashLine(Canvas c, int x0, int y0, int length, int thick, boolean across, int dash, int gap, int argb) {
		if (length <= 0 || thick <= 0 || dash <= 0) {
			return;
		}
		int period = dash + Math.max(0, gap);
		if (across) {
			blit(c, dashesAcross.get(period, 1, dash, 1), x0, y0, length, thick, period, 1, argb);
		} else {
			blit(c, dashesDown.get(1, period, 1, dash), x0, y0, thick, length, 1, period, argb);
		}
	}

	/**
	 * A dashed rectangle outline {@code line} pixels wide inside {@code (x0, y0)..(x1, y1)}: dashes of
	 * {@code dash} pixels with {@code gap} between, starting at each edge's start.
	 */
	public void dashed(Canvas c, int x0, int y0, int x1, int y1, int line, int dash, int gap, int argb) {
		if (x1 <= x0 || y1 <= y0 || line <= 0 || dash <= 0) {
			return;
		}
		int period = dash + Math.max(0, gap);
		Identifier across = dashesAcross.get(period, 1, dash, 1);
		Identifier down = dashesDown.get(1, period, 1, dash);
		int thick = Math.min(line, y1 - y0);
		blit(c, across, x0, y0, x1 - x0, thick, period, 1, argb);
		blit(c, across, x0, y1 - thick, x1 - x0, thick, period, 1, argb);
		int top = y0 + line;
		int bottom = y1 - line;
		if (bottom > top) {
			int wide = Math.min(line, x1 - x0);
			blit(c, down, x0, top, wide, bottom - top, 1, period, argb);
			blit(c, down, x1 - wide, top, wide, bottom - top, 1, period, argb);
		}
	}

	/** One quad of {@code w × h} pixels covered by the tile repeated from its corner (texture pixels map 1:1). */
	private static void blit(Canvas c, Identifier id, int x, int y, int w, int h, int tileW, int tileH, int argb) {
		int color = c.tint(argb);
		if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
			return;
		}
		// A one-pixel-thick tile is stretched across the line's thickness; otherwise one texel per pixel.
		int srcW = tileW == 1 ? 1 : w;
		int srcH = tileH == 1 ? 1 : h;
		c.graphics().blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0, 0, w, h, srcW, srcH, tileW, tileH, color);
	}

	/** One repeating tile: white where the mark is, clear elsewhere (the blit colour tints it). */
	private static final class Tile {
		private final String name;
		private @Nullable Identifier id;
		private @Nullable Identifier retired;
		private long key = -1;
		private int version;

		Tile(String name) {
			this.name = name;
		}

		/** The tile {@code w × h} with an opaque {@code markW × markH} block at its corner, made on first need. */
		Identifier get(int w, int h, int markW, int markH) {
			long wanted = (long) (w & 0xFFFF) << 48 | (long) (h & 0xFFFF) << 32 | (long) (markW & 0xFFFF) << 16 | markH & 0xFFFF;
			if (id != null && wanted == key) {
				return id;
			}
			NativeImage image = new NativeImage(w, h, true);
			for (int y = 0; y < markH; y++) {
				for (int x = 0; x < markW; x++) {
					image.setPixel(x, y, 0xFFFFFFFF);
				}
			}
			freeRetired();
			retired = id;
			Identifier next = Identifier.fromNamespaceAndPath(FlintClient.MOD_ID, "dynamic/" + name + "_" + (++version));
			Minecraft.getInstance().getTextureManager().register(next, new DynamicTexture(() -> "Flint " + name, image));
			id = next;
			key = wanted;
			return next;
		}

		void freeRetired() {
			if (retired != null) {
				Minecraft.getInstance().getTextureManager().release(retired);
				retired = null;
			}
		}

		void release() {
			freeRetired();
			if (id != null) {
				Minecraft.getInstance().getTextureManager().release(id);
				id = null;
				key = -1;
			}
		}
	}
}
