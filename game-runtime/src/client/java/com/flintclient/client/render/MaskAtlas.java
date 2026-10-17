package com.flintclient.client.render;

import com.flintclient.client.FlintClient;
import com.flintclient.client.graphics.GpuResourceTracker;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * One small texture holding every antialiased shape the Flint screens draw at the current GUI scale:
 * quarter discs for rounded corners, icons and the Flint mark. Shapes are white with coverage in alpha
 * (tinted when drawn), except the mark, which keeps its own colours.
 *
 * <p>Each shape is rasterised once and cached. With batching (the {@code atlasBatching} module), a shape
 * a frame asks for that isn't in the texture yet is written into free space in the atlas image, and the
 * changed rows are uploaded once, by {@link #endFrame()} at the end of the Flint screen's drawing. Screens
 * only collect the GUI's draws; Minecraft draws them afterwards ({@code GameRenderer.render} runs
 * {@code GuiRenderer} after {@code Gui.extractRenderState}, on this thread), so the upload lands before
 * anything samples the texture and a new shape shows in the frame that first asks for it. Shapes already
 * in the texture never move or change, so draws collected earlier in the frame are unaffected. A new
 * texture is only made when the atlas grows or is repacked (see {@link AtlasLayout}). A lookup outside a
 * Flint frame uploads straight away.
 *
 * <p>Without batching, a new texture (with a new id) is built from all cached shapes straight away
 * whenever a frame asks for a shape that isn't in it yet, so nothing is skipped. Either way, textures
 * replaced during a frame are released at the start of the next one, after the frame that used them has
 * been drawn.
 */
public final class MaskAtlas {
	/** A region of a texture, in pixels. */
	public record Entry(Identifier texture, int u, int v, int width, int height, int textureWidth, int textureHeight) {
	}

	private record Key(String kind, int size, Object detail) {
	}

	private record Size(int height, int radius) {
	}

	private record Ring(int height, int radius, int border) {
	}

	private record Shape(int width, int height, int[] argb) {
	}

	/** A replaced texture waiting to be freed, with its size for the GPU resource count. */
	private record Retired(Identifier id, long bytes) {
	}

	private static final MaskAtlas INSTANCE = new MaskAtlas();
	private static final int MAX_WIDTH = 2048;

	private final Map<Key, Shape> shapes = new LinkedHashMap<>();
	private final Map<Key, Entry> entries = new HashMap<>();
	private final List<Retired> retired = new ArrayList<>();
	private final AtlasLayout layout = new AtlasLayout();
	private Identifier current;
	private long currentBytes;
	/** The batched atlas's texture (its image is the CPU copy new shapes are written into). */
	private @Nullable DynamicTexture texture;
	private boolean batching;
	/** Between {@link #beginFrame} and {@link #endFrame}: new shapes wait for the end-of-frame upload. */
	private boolean framing;
	private int version;
	private int scale = -1;

	private MaskAtlas() {
	}

	public static MaskAtlas get() {
		return INSTANCE;
	}

	/**
	 * Batches new shapes into one upload per frame (the {@code atlasBatching} module). Set once at start-up,
	 * before anything is drawn.
	 */
	public static void useBatching(boolean on) {
		INSTANCE.batching = on;
	}

	/** Called at the start of every Flint frame: frees replaced textures and resets on a GUI scale change. */
	public void beginFrame(int guiScale) {
		freeRetired(Minecraft.getInstance().getTextureManager());
		if (guiScale != scale) {
			scale = guiScale;
			shapes.clear();
			entries.clear();
			layout.clear();
			texture = null;
			retireCurrent();
		}
		if (batching) {
			// A frame that drew without reaching endFrame still gets its new shapes uploaded.
			flush();
			layout.nextFrame();
			framing = true;
		}
	}

	/** Called when a Flint screen has finished drawing: uploads the shapes this frame added, in one write. */
	public void endFrame() {
		if (framing) {
			flush();
			framing = false;
		}
	}

	/**
	 * Batches new shapes again after an {@link #endFrame} earlier in the same frame (the HUD ends its part
	 * before a Flint screen draws). Unlike {@link #beginFrame} it frees nothing: draws collected earlier in
	 * this frame may still use every texture. The next {@link #endFrame} uploads what was added. Without
	 * batching it does nothing (new shapes are uploaded straight away).
	 */
	public void resumeFrame() {
		if (batching) {
			framing = true;
		}
	}

	/** Between a begin (or resume) and the end of a frame part: new shapes wait for {@link #endFrame}. */
	boolean framing() {
		return framing;
	}

	/**
	 * Frees the texture, its image and every cached shape (the {@code menuResources} module, when a world is
	 * joined). The next Flint frame builds them again. Render thread.
	 */
	public void release() {
		// The current texture goes last, as it always has.
		retireCurrent();
		freeRetired(Minecraft.getInstance().getTextureManager());
		texture = null;
		shapes.clear();
		entries.clear();
		layout.clear();
		framing = false;
		scale = -1;
	}

	/** A quarter disc of radius {@code radius} px (the top-left corner of a rounded rectangle). */
	public Entry corner(int radius) {
		return batching ? find(MaskKey.of(MaskKey.CORNER, radius, 0, 0, 0)) : lookup(new Key("corner", radius, null));
	}

	/** The outside of a quarter disc: drawn over a square corner in the surrounding colour, it rounds it. */
	public Entry notch(int radius) {
		return batching ? find(MaskKey.of(MaskKey.NOTCH, radius, 0, 0, 0)) : lookup(new Key("notch", radius, null));
	}

	/** A whole rounded rectangle at its exact pixel size (one quad instead of corners and fills). */
	public Entry roundRect(int width, int height, int radius) {
		return batching ? find(MaskKey.of(MaskKey.ROUND_RECT, width, height, radius, 0)) : lookup(new Key("rrect", width, new Size(height, radius)));
	}

	/** The border of a rounded rectangle ({@code border} px wide) at its exact pixel size. */
	public Entry ring(int width, int height, int radius, int border) {
		return batching ? find(MaskKey.of(MaskKey.RING, width, height, radius, border)) : lookup(new Key("ring", width, new Ring(height, radius, border)));
	}

	/** A quarter of a {@code border}-px ring of outer radius {@code radius} (for large outlines). */
	public Entry ringCorner(int radius, int border) {
		return batching ? find(MaskKey.of(MaskKey.RING_CORNER, radius, 0, 0, border)) : lookup(new Key("ringcorner", radius, border));
	}

	public Entry icon(Icon icon, int sizePx) {
		return batching ? find(MaskKey.of(MaskKey.ICON, sizePx, icon.ordinal(), 0, 0)) : lookup(new Key("icon", sizePx, icon));
	}

	/**
	 * A solid icon. It shares the ICON kind with the line icons ({@code c} = 1 marks the solid set, which
	 * line icons never use), so the packed key layout and its free kind stay as they are.
	 */
	public Entry filledIcon(FilledIcon icon, int sizePx) {
		return batching ? find(MaskKey.of(MaskKey.ICON, sizePx, icon.ordinal(), 1, 0)) : lookup(new Key("filled", sizePx, icon));
	}

	/** The Flint mark, {@code heightPx} tall, in its own colours. */
	public Entry mark(int heightPx) {
		return batching ? find(MaskKey.of(MaskKey.MARK, heightPx, 0, 0, 0)) : lookup(new Key("mark", heightPx, null));
	}

	public static int markWidth(int heightPx) {
		return Math.round(heightPx * Logo.ASPECT);
	}

	private Entry lookup(Key key) {
		Entry entry = entries.get(key);
		if (entry != null) {
			return entry;
		}
		shapes.computeIfAbsent(key, MaskAtlas::rasterise);
		rebuild();
		return entries.get(key);
	}

	private void rebuild() {
		int atlasWidth = 256;
		for (Shape shape : shapes.values()) {
			atlasWidth = Math.max(atlasWidth, Math.min(MAX_WIDTH, shape.width + 2));
		}
		// Shelf packing with a one-pixel gap so neighbouring shapes never bleed into each other.
		int x = 1;
		int y = 1;
		int row = 0;
		Map<Key, int[]> at = new LinkedHashMap<>();
		for (Map.Entry<Key, Shape> e : shapes.entrySet()) {
			Shape shape = e.getValue();
			if (x + shape.width + 1 > atlasWidth) {
				x = 1;
				y += row + 1;
				row = 0;
			}
			at.put(e.getKey(), new int[] {x, y});
			x += shape.width + 1;
			row = Math.max(row, shape.height);
		}
		int atlasHeight = y + row + 1;
		NativeImage image = new NativeImage(atlasWidth, atlasHeight, true);
		Identifier id = Identifier.fromNamespaceAndPath(FlintClient.MOD_ID, "dynamic/mask_atlas_" + (++version));
		entries.clear();
		for (Map.Entry<Key, int[]> e : at.entrySet()) {
			Shape shape = shapes.get(e.getKey());
			int u = e.getValue()[0];
			int v = e.getValue()[1];
			for (int py = 0; py < shape.height; py++) {
				for (int px = 0; px < shape.width; px++) {
					int argb = shape.argb[py * shape.width + px];
					if (argb != 0) {
						image.setPixel(u + px, v + py, argb);
					}
				}
			}
			entries.put(e.getKey(), new Entry(id, u, v, shape.width, shape.height, atlasWidth, atlasHeight));
		}
		Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "Flint mask atlas", image));
		makeCurrent(id, GpuResourceTracker.rgba8(atlasWidth, atlasHeight));
	}

	/** {@code id} was just registered ({@code bytes} big): it becomes current, and the one before is freed next frame. */
	private void makeCurrent(Identifier id, long bytes) {
		GpuResourceTracker.get().register(GpuResourceTracker.Kind.ATLAS, bytes);
		retireCurrent();
		current = id;
		currentBytes = bytes;
	}

	private void retireCurrent() {
		if (current != null) {
			retired.add(new Retired(current, currentBytes));
			current = null;
		}
	}

	/** Frees the textures replaced so far (an index loop: this runs every frame and allocates nothing). */
	private void freeRetired(TextureManager textures) {
		for (int i = 0; i < retired.size(); i++) {
			Retired old = retired.get(i);
			textures.release(old.id());
			GpuResourceTracker.get().release(GpuResourceTracker.Kind.ATLAS, old.bytes());
		}
		retired.clear();
	}

	// ---------- batched path ----------

	private Entry find(long key) {
		AtlasLayout.Slot slot = layout.get(key);
		return slot != null ? slot.entry : add(key);
	}

	private Entry add(long key) {
		Shape shape = rasterisePacked(key);
		// Rows written this frame but not uploaded yet: the current texture still needs them if it's replaced.
		int pendingTop = layout.dirtyTop();
		int pendingBottom = layout.dirtyBottom();
		int usedBefore = layout.usedHeight();
		AtlasLayout.Change change = layout.add(key, shape.width, shape.height);
		if (change != AtlasLayout.Change.NONE) {
			replaceTexture(change, pendingTop, pendingBottom, usedBefore);
		}
		AtlasLayout.Slot slot = layout.get(key);
		NativeImage pixels = texture.getPixels();
		for (int py = 0; py < shape.height; py++) {
			for (int px = 0; px < shape.width; px++) {
				int argb = shape.argb[py * shape.width + px];
				if (argb != 0) {
					pixels.setPixel(slot.u + px, slot.v + py, argb);
				}
			}
		}
		slot.entry = new Entry(current, slot.u, slot.v, shape.width, shape.height, layout.width(), layout.height());
		if (!framing) {
			flush();
		}
		return slot.entry;
	}

	/** Moves to a new texture of the layout's size, carrying the kept shapes' pixels over from the old one. */
	private void replaceTexture(AtlasLayout.Change change, int pendingTop, int pendingBottom, int usedBefore) {
		DynamicTexture old = texture;
		// Draws already collected this frame sample the old texture, so it gets its pending rows first.
		if (old != null && pendingBottom > pendingTop) {
			upload(old, pendingTop, pendingBottom);
		}
		// Created without an upload: the end-of-frame flush uploads the used rows once.
		DynamicTexture next = new DynamicTexture(() -> "Flint mask atlas", layout.width(), layout.height(), true);
		if (old != null) {
			NativeImage from = old.getPixels();
			NativeImage to = next.getPixels();
			if (change == AtlasLayout.Change.GROWN) {
				copyRows(from, to, Math.min(usedBefore, from.getHeight()));
			} else {
				for (AtlasLayout.Slot slot : layout.slots()) {
					if (slot.fromU >= 0) {
						from.copyRect(to, slot.fromU, slot.fromV, slot.u, slot.v, slot.width, slot.height, false, false);
					}
				}
			}
		}
		Identifier id = Identifier.fromNamespaceAndPath(FlintClient.MOD_ID, "dynamic/mask_atlas_" + (++version));
		Minecraft.getInstance().getTextureManager().register(id, next);
		makeCurrent(id, GpuResourceTracker.rgba8(layout.width(), layout.height()));
		texture = next;
		for (AtlasLayout.Slot slot : layout.slots()) {
			slot.entry = new Entry(id, slot.u, slot.v, slot.width, slot.height, layout.width(), layout.height());
		}
	}

	/**
	 * Copies the top {@code rows} rows of {@code from} to the same place in {@code to} (as wide or wider),
	 * one memory copy per row. (NativeImage.copyFrom copies only part of each row when the widths differ.)
	 */
	private static void copyRows(NativeImage from, NativeImage to, int rows) {
		long fromRow = (long) from.getWidth() * from.format().components();
		long toRow = (long) to.getWidth() * to.format().components();
		for (int y = 0; y < rows; y++) {
			MemoryUtil.memCopy(from.getPointer() + y * fromRow, to.getPointer() + y * toRow, fromRow);
		}
	}

	private void flush() {
		if (texture != null && layout.dirty()) {
			upload(texture, layout.dirtyTop(), layout.dirtyBottom());
		}
		layout.clean();
	}

	/** Uploads rows [top, bottom) of a texture's image: whole rows sit together in memory, so it's one write. */
	private static void upload(DynamicTexture texture, int top, int bottom) {
		NativeImage pixels = texture.getPixels();
		int rowBytes = pixels.getWidth() * pixels.format().components();
		ByteBuffer rows = pixels.getPixelBytes().slice(top * rowBytes, (bottom - top) * rowBytes);
		RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture.getTexture(), rows, 0, 0, 0, top, pixels.getWidth(), bottom - top);
	}

	/** Without batching: the shape for a record key (the original path). */
	private static Shape rasterise(Key key) {
		return switch (key.kind) {
			case "corner" -> cornerPixels(key.size);
			case "rrect" -> {
				Size size = (Size) key.detail;
				yield roundRectPixels(key.size, size.height(), size.radius());
			}
			case "ring" -> {
				Ring ring = (Ring) key.detail;
				yield ringPixels(key.size, ring.height(), ring.radius(), ring.border());
			}
			case "ringcorner" -> ringCornerPixels(key.size, (Integer) key.detail);
			case "notch" -> notchPixels(key.size);
			case "icon" -> iconPixels((Icon) key.detail, key.size);
			case "filled" -> filledPixels((FilledIcon) key.detail, key.size);
			case "mark" -> markPixels(key.size);
			default -> throw new IllegalArgumentException("Unknown mask " + key.kind);
		};
	}

	/** With batching: the shape for a packed key. */
	private static Shape rasterisePacked(long key) {
		int a = MaskKey.a(key);
		return switch (MaskKey.kind(key)) {
			case MaskKey.CORNER -> cornerPixels(a);
			case MaskKey.NOTCH -> notchPixels(a);
			case MaskKey.ROUND_RECT -> roundRectPixels(a, MaskKey.b(key), MaskKey.c(key));
			case MaskKey.RING -> ringPixels(a, MaskKey.b(key), MaskKey.c(key), MaskKey.d(key));
			case MaskKey.RING_CORNER -> ringCornerPixels(a, MaskKey.d(key));
			case MaskKey.ICON -> MaskKey.c(key) == 0 ? iconPixels(Icon.values()[MaskKey.b(key)], a) : filledPixels(FilledIcon.values()[MaskKey.b(key)], a);
			case MaskKey.MARK -> markPixels(a);
			default -> throw new IllegalArgumentException("Unknown mask kind " + MaskKey.kind(key));
		};
	}

	private static Shape cornerPixels(int r) {
		int[] argb = new int[r * r];
		for (int py = 0; py < r; py++) {
			for (int px = 0; px < r; px++) {
				// Distance from the pixel centre to the circle centred at the quarter's inner corner.
				float dx = r - (px + 0.5F);
				float dy = r - (py + 0.5F);
				argb[py * r + px] = white(Sdf.coverage((float) Math.sqrt(dx * dx + dy * dy) - r));
			}
		}
		return new Shape(r, r, argb);
	}

	private static Shape roundRectPixels(int w, int h, int radius) {
		float r = Math.min(radius, Math.min(w, h) / 2.0F);
		int[] argb = new int[w * h];
		for (int py = 0; py < h; py++) {
			for (int px = 0; px < w; px++) {
				argb[py * w + px] = white(Sdf.coverage(roundRectDistance(px + 0.5F, py + 0.5F, w, h, r)));
			}
		}
		return new Shape(w, h, argb);
	}

	private static Shape ringPixels(int w, int h, int radius, int b) {
		float r = Math.min(radius, Math.min(w, h) / 2.0F);
		int[] argb = new int[w * h];
		for (int py = 0; py < h; py++) {
			for (int px = 0; px < w; px++) {
				float outer = Sdf.coverage(roundRectDistance(px + 0.5F, py + 0.5F, w, h, r));
				float inner = Sdf.coverage(roundRectDistance(px + 0.5F - b, py + 0.5F - b, w - 2 * b, h - 2 * b, Math.max(0, r - b)));
				argb[py * w + px] = white(Math.max(0, outer - inner));
			}
		}
		return new Shape(w, h, argb);
	}

	private static Shape ringCornerPixels(int r, int b) {
		int[] argb = new int[r * r];
		for (int py = 0; py < r; py++) {
			for (int px = 0; px < r; px++) {
				float dx = r - (px + 0.5F);
				float dy = r - (py + 0.5F);
				float dist = (float) Math.sqrt(dx * dx + dy * dy);
				argb[py * r + px] = white(Math.max(0, Sdf.coverage(dist - r) - Sdf.coverage(dist - (r - b))));
			}
		}
		return new Shape(r, r, argb);
	}

	private static Shape notchPixels(int r) {
		int[] argb = new int[r * r];
		for (int py = 0; py < r; py++) {
			for (int px = 0; px < r; px++) {
				float dx = r - (px + 0.5F);
				float dy = r - (py + 0.5F);
				argb[py * r + px] = white(1.0F - Sdf.coverage((float) Math.sqrt(dx * dx + dy * dy) - r));
			}
		}
		return new Shape(r, r, argb);
	}

	private static Shape iconPixels(Icon icon, int size) {
		Sdf.Path path = icon.path();
		float unit = size / Icon.UNITS;
		float half = Icon.STROKE / 2.0F;
		int[] argb = new int[size * size];
		for (int py = 0; py < size; py++) {
			for (int px = 0; px < size; px++) {
				float d = path.distance((px + 0.5F) / unit, (py + 0.5F) / unit) - half;
				argb[py * size + px] = white(Sdf.coverage(d * unit));
			}
		}
		return new Shape(size, size, argb);
	}

	/** The polygon grown by half a stroke, so it covers the same area as the outline of the same shape. */
	private static Shape filledPixels(FilledIcon icon, int size) {
		Sdf.Polygon polygon = icon.polygon();
		float unit = size / Icon.UNITS;
		float half = Icon.STROKE / 2.0F;
		int[] argb = new int[size * size];
		for (int py = 0; py < size; py++) {
			for (int px = 0; px < size; px++) {
				float d = polygon.signedDistance((px + 0.5F) / unit, (py + 0.5F) / unit) - half;
				argb[py * size + px] = white(Sdf.coverage(d * unit));
			}
		}
		return new Shape(size, size, argb);
	}

	/** A line icon's pixels (white, coverage in alpha), as the atlas stores them; for tests and previews. */
	static int[] iconArgb(Icon icon, int size) {
		return iconPixels(icon, size).argb;
	}

	/** A solid icon's pixels, as {@link #iconArgb}. */
	static int[] filledArgb(FilledIcon icon, int size) {
		return filledPixels(icon, size).argb;
	}

	private static Shape markPixels(int height) {
		int width = markWidth(height);
		float unit = height / Logo.HEIGHT;
		int[] argb = new int[width * height];
		for (int py = 0; py < height; py++) {
			for (int px = 0; px < width; px++) {
				float lx = Logo.LEFT + (px + 0.5F) / unit;
				float ly = Logo.TOP + (py + 0.5F) / unit;
				float r = 0;
				float g = 0;
				float b = 0;
				float a = 0;
				// The facets are painted in order, compositing "over" with premultiplied colour.
				for (Sdf.Polygon facet : Logo.FACETS) {
					float c = Sdf.coverage(facet.signedDistance(lx, ly) * unit);
					if (c <= 0) {
						continue;
					}
					r = ((facet.argb() >> 16) & 255) / 255.0F * c + r * (1 - c);
					g = ((facet.argb() >> 8) & 255) / 255.0F * c + g * (1 - c);
					b = (facet.argb() & 255) / 255.0F * c + b * (1 - c);
					a = c + a * (1 - c);
				}
				if (a > 0) {
					int rgb = (Math.round(r / a * 255) << 16) | (Math.round(g / a * 255) << 8) | Math.round(b / a * 255);
					argb[py * width + px] = (Math.round(a * 255) << 24) | rgb;
				}
			}
		}
		return new Shape(width, height, argb);
	}

	/** Signed distance from (px, py) to a w×h rounded rectangle at the origin with corner radius r. */
	static float roundRectDistance(float px, float py, float w, float h, float r) {
		float qx = Math.abs(px - w / 2.0F) - (w / 2.0F - r);
		float qy = Math.abs(py - h / 2.0F) - (h / 2.0F - r);
		float ox = Math.max(qx, 0);
		float oy = Math.max(qy, 0);
		return (float) Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(qx, qy), 0) - r;
	}

	private static int white(float coverage) {
		int alpha = Math.round(coverage * 255);
		return alpha == 0 ? 0 : (alpha << 24) | 0xFFFFFF;
	}

	/** The Flint mark from the brand SVG (512-unit canvas), cropped to its bounds. */
	static final class Logo {
		static final float LEFT = 161;
		static final float TOP = 60;
		static final float HEIGHT = 350;
		static final float ASPECT = 190.0F / 350.0F;
		static final Sdf.Polygon[] FACETS = {
			new Sdf.Polygon(new float[] {256, 351, 256, 161}, new float[] {60, 228, 312, 228}, 0xB45309),
			new Sdf.Polygon(new float[] {256, 351, 256}, new float[] {60, 228, 186}, 0xD97706),
			new Sdf.Polygon(new float[] {239, 318, 239, 194}, new float[] {172, 284, 410, 284}, 0xFEF3E2),
		};

		private Logo() {
		}
	}
}
