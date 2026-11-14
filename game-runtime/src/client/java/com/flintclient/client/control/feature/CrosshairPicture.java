/*
 * The shapes follow Custom Crosshair Mod by wjbaker (CC0 1.0; META-INF/licenses/CC0-1.0-CustomCrosshairMod.txt), as Flint's
 * in-game crosshair draws them (control/platform/Mc263Crosshair).
 */
package com.flintclient.client.control.feature;

import com.flintclient.client.control.model.IntSetting;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.RgbSetting;
import com.flintclient.client.control.model.mods.PlayerMods.Crosshair;
import java.util.Arrays;

/**
 * Crosshair's picture for the Right Shift menu's live preview, the same on every Minecraft line: the crosshair as the game
 * draws it (its style, size, gap, thickness, rotation, scale and offset; its outline, its dot, its colour or Minecraft's colours
 * inverted behind it), rasterised into window pixels over a picture of a Minecraft day ({@link #scene}), so the preview shows
 * exactly what the game will. Lines are one window pixel wide, as the game's are. What depends on the game (aiming at a mob,
 * a bow being drawn, cooldowns) isn't shown. Pure Java; render thread.
 */
public final class CrosshairPicture {
	private static final int VANILLA = 0;
	private static final int CROSS = 1;
	private static final int CIRCLE = 2;
	private static final int SQUARE = 3;
	private static final int TRIANGLE = 4;
	private static final int ARROW = 5;
	private static final int DEBUG = 6;
	/** A primitive's kind: an axis-aligned box in the crosshair's own (turned) space, or a line one window pixel wide. */
	private static final int BOX = 0;
	private static final int LINE = 1;
	/** Colour 0 with this flag: Minecraft's colours behind it inverted (adaptive colour). */
	private static final int INVERT = 1;

	private final int width;
	private final int height;
	/** The picture: ARGB per window pixel, 0 where the crosshair isn't. */
	private final int[] pixels;
	/** Pixels in Minecraft's colours inverted, drawn without a background to invert (the game inverts them as it draws). */
	private final boolean[] inverted;
	/** The rectangle drawn last (cleared before the next drawing), inclusive; empty when x0 > x1. */
	private int dirtyX0;
	private int dirtyY0;
	private int dirtyX1 = -1;
	private int dirtyY1 = -1;
	private float[] prims = new float[8 * 64];
	private int count;

	public CrosshairPicture(int width, int height) {
		this.width = width;
		this.height = height;
		this.pixels = new int[width * height];
		this.inverted = new boolean[width * height];
	}

	/** Whether window pixel ({@code x}, {@code y}) shows Minecraft's colours inverted (drawn with no background given). */
	public boolean inverted(int x, int y) {
		return inverted[y * width + x];
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	/** ARGB of window pixel ({@code x}, {@code y}); 0 where the crosshair isn't. */
	public int at(int x, int y) {
		return pixels[y * width + x];
	}

	public int[] pixels() {
		return pixels;
	}

	private static int argb(ModuleState s, RgbSetting color, IntSetting alpha) {
		return s.integer(alpha) << 24 | s.rgb(color);
	}

	/**
	 * Draws Crosshair's settings {@code s} centred in the picture, {@code guiScale} window pixels to a GUI unit (the game's GUI
	 * scale), over {@code background} (ARGB per window pixel, the picture's size; for inverted colours). {@code rainbowTicks}:
	 * where the rainbow is, when it is on.
	 */
	public void draw(ModuleState s, int guiScale, int[] background, CrosshairDrawing drawing, int rainbowTicks) {
		int middleX = width / 2 / guiScale * guiScale;
		int middleY = height / 2 / guiScale * guiScale;
		drawAt(s, guiScale, background, drawing, rainbowTicks, middleX + s.integer(Crosshair.OFFSET_X) * guiScale,
			middleY + s.integer(Crosshair.OFFSET_Y) * guiScale, middleX, middleY);
	}

	/**
	 * Draws the crosshair centred on window pixel ({@code centerX}, {@code centerY}) of the picture and its dot on
	 * ({@code dotX}, {@code dotY}) (the screen's centre: the dot takes no offset). With {@code background} null, inverted pixels
	 * are white and marked ({@link #inverted}), for a renderer that inverts as it draws.
	 */
	public void drawAt(ModuleState s, int guiScale, int @org.jspecify.annotations.Nullable [] background, CrosshairDrawing drawing,
		int rainbowTicks, float centerX, float centerY, float dotX, float dotY) {
		if (dirtyX0 <= dirtyX1) {
			for (int y = dirtyY0; y <= dirtyY1; y++) {
				Arrays.fill(pixels, y * width + dirtyX0, y * width + dirtyX1 + 1, 0);
				Arrays.fill(inverted, y * width + dirtyX0, y * width + dirtyX1 + 1, false);
			}
		}
		dirtyX0 = width;
		dirtyY0 = height;
		dirtyX1 = -1;
		dirtyY1 = -1;
		count = 0;
		int style = s.option(Crosshair.TYPE);
		float scale = s.integer(Crosshair.SCALE) / 100.0F;
		float ppu = Math.max(0.01F, guiScale * scale);
		double angle = Math.toRadians(s.integer(Crosshair.ROTATION));
		int color = argb(s, Crosshair.COLOR, Crosshair.ALPHA);
		if (s.bool(Crosshair.RAINBOW)) {
			int speed = s.integer(Crosshair.RAINBOW_SPEED);
			color = s.integer(Crosshair.ALPHA) << 24 | rainbow(rainbowTicks, speed, 0) << 16 | rainbow(rainbowTicks, speed, 2) << 8
				| rainbow(rainbowTicks, speed, 4);
		}
		// Adaptive colour gives way to a colour of its own (the rainbow), as the game's crosshair does.
		boolean invert = s.bool(Crosshair.INVERT) && !s.bool(Crosshair.RAINBOW);
		shapes(s, style, ppu, color, invert, drawing);
		raster(centerX, centerY, ppu, (float) Math.cos(angle), (float) Math.sin(angle), background);
		if (s.bool(Crosshair.DOT) && style != VANILLA) {
			// The dot sits on the screen's centre, at the GUI's own size: no offset, scale or turn.
			count = 0;
			arc(0.5F, 0, 360, argb(s, Crosshair.DOT_COLOR, Crosshair.DOT_ALPHA), guiScale);
			raster(dotX, dotY, guiScale, 1, 0, background);
		}
	}

	/**
	 * How far from its centre the crosshair can reach, in GUI units before its scale (turned any way): the room a picture needs
	 * around it.
	 */
	public static float extent(ModuleState s, CrosshairDrawing drawing) {
		int style = s.option(Crosshair.TYPE);
		float reach;
		if (style == VANILLA) {
			reach = 6;
		} else if (style == DEBUG) {
			reach = 12;
		} else if (style > DEBUG) {
			reach = Math.max(drawing.width(), drawing.height()) / 2.0F + 1;
		} else {
			reach = Math.max(s.integer(Crosshair.WIDTH), s.integer(Crosshair.HEIGHT)) + s.integer(Crosshair.GAP) + s.integer(Crosshair.THICKNESS) + 2;
		}
		return reach * 1.4143F + 1;
	}

	/** The rectangle the last drawing touched (x0, y0, x1, y1, inclusive), or null when it touched nothing. */
	public int @org.jspecify.annotations.Nullable [] touched() {
		return dirtyX0 <= dirtyX1 ? new int[] {dirtyX0, dirtyY0, dirtyX1, dirtyY1} : null;
	}

	private static int rainbow(int ticks, int speed, float offset) {
		return (int) (Math.sin(ticks * speed / 20000.0F + offset) * 127.0 + 128.0) & 0xFF;
	}

	// ---------- the shapes, in GUI units about the crosshair's centre (Mc263Crosshair.drawStyle) ----------

	private void shapes(ModuleState s, int style, float ppu, int color, boolean invert, CrosshairDrawing drawing) {
		float width = s.integer(Crosshair.WIDTH);
		float height = s.integer(Crosshair.HEIGHT);
		float thickness = s.integer(Crosshair.THICKNESS);
		int gap = s.integer(Crosshair.GAP);
		int outline = argb(s, Crosshair.OUTLINE_COLOR, Crosshair.OUTLINE_ALPHA);
		boolean outlined = s.bool(Crosshair.OUTLINE);
		switch (style) {
			case VANILLA -> {
				// Minecraft's own: a 9-pixel plus, always in inverted colours.
				box(0, -4, 1, 5, 0, true);
				box(-4, 0, 0, 1, 0, true);
				box(1, 0, 5, 1, 0, true);
			}
			case CROSS -> {
				float t = thickness - 0.1F;
				if (outlined) {
					float w = width + 1.0F;
					float h = height + 1.0F;
					float gp = gap - 0.5F;
					bordered(-t, -gp - h, t, -gp, outline, color, invert, ppu);
					bordered(-t, gp, t, gp + h, outline, color, invert, ppu);
					bordered(-gp - w, -t, -gp, t, outline, color, invert, ppu);
					bordered(gp, -t, gp + w, t, outline, color, invert, ppu);
				} else {
					float at = t - 0.5F;
					box(-at, -gap - height, at, -gap, color, invert);
					box(-at, gap, at, gap + height, color, invert);
					box(-gap - width, -at, -gap, at, color, invert);
					box(gap, -at, gap + width, at, color, invert);
				}
			}
			case CIRCLE -> {
				float lw = strokeWidth(thickness);
				if (outlined) {
					arc(gap, 0, 360, outline, ppu, lw, 1);
				}
				arc(gap, 0, 360, color, ppu, lw, 0);
			}
			case SQUARE -> {
				if (outlined) {
					frame(-width - gap + 0.5F, -height - gap + 0.5F, width + gap - 0.5F, height + gap - 0.5F, outline);
					frame(-width - thickness - gap - 0.5F, -height - thickness - gap - 0.5F, width + thickness + gap + 0.5F,
						height + thickness + gap + 0.5F, outline);
				}
				box(-width - thickness - gap, -height - thickness - gap, width + thickness + gap, -height - gap, color, invert);
				box(-width - thickness - gap, height + gap, width + thickness + gap, height + thickness + gap, color, invert);
				box(-width - thickness - gap, -gap - height, -width - gap, gap + height, color, invert);
				box(width + gap, -gap - height, width + thickness + gap, gap + height, color, invert);
			}
			case TRIANGLE -> {
				float top = -height / 2.0F - gap;
				float bottom = height / 2.0F + gap;
				float side = width / 2.0F + gap;
				float lw = strokeWidth(thickness);
				for (int pass = outlined ? 0 : 1; pass < 2; pass++) {
					int ink = pass == 0 ? outline : color;
					line(0, top, side, bottom, ink, lw, 1 - pass);
					line(side, bottom, -side, bottom, ink, lw, 1 - pass);
					line(-side, bottom, 0, top, ink, lw, 1 - pass);
				}
			}
			case ARROW -> {
				// A chevron pointing at the centre from below, its tip the gap away.
				float lw = strokeWidth(thickness);
				for (int pass = outlined ? 0 : 1; pass < 2; pass++) {
					int ink = pass == 0 ? outline : color;
					line(-width, height + gap, 0, gap, ink, lw, 1 - pass);
					line(0, gap, width, height + gap, ink, lw, 1 - pass);
				}
			}
			case DEBUG -> {
				// Minecraft's debug crosshair is drawn in 3D; here its three arms, flat.
				line(0, 0, 10, 0, 0xFFFF4040);
				line(0, 0, 0, -10, 0xFF40FF40);
				box(-1, -1, 1, 1, 0xFF4060FF, false);
			}
			default -> {
				float ox = drawing.width() / 2.0F;
				float oy = drawing.height() / 2.0F;
				int w = drawing.width();
				// The outline first (every run a pixel wider all round), then the drawing over it.
				for (int pass = outlined ? 0 : 1; pass < 2; pass++) {
					for (int py = 0; py < drawing.height(); py++) {
						int run = -1;
						for (int px = 0; px <= w; px++) {
							boolean on = px < w && drawing.at(px, py);
							if (on && run < 0) {
								run = px;
							} else if (!on && run >= 0) {
								if (pass == 0) {
									push(BOX, run - ox, py - oy, px - ox, py - oy + 1, outline, 1 << 1, 0);
								} else {
									box(run - ox, py - oy, px - ox, py - oy + 1, color, invert);
								}
								run = -1;
							}
						}
					}
				}
			}
		}
	}

	/**
	 * A line style's stroke in GUI units for {@code thickness}: one window pixel at thickness 1 (0 here, as the game draws it),
	 * then as wide as the Cross's arms are.
	 */
	public static float strokeWidth(float thickness) {
		return thickness <= 1 ? 0 : 2 * thickness - 1.2F;
	}

	private void bordered(float x0, float y0, float x1, float y1, int border, int fill, boolean invert, float ppu) {
		box(x0, y0, x1, y1, fill, invert);
		frame(x0, y0, x1, y1, border);
	}

	private void frame(float x0, float y0, float x1, float y1, int color) {
		line(x0, y0, x1, y0, color);
		line(x1, y0, x1, y1, color);
		line(x1, y1, x0, y1, color);
		line(x0, y1, x0, y0, color);
	}

	/** An arc of radius {@code r}, clockwise from the top, as the game joins it: short lines, as many as look round. */
	private void arc(float r, int from, int to, int color, float ppu) {
		arc(r, from, to, color, ppu, 0, 0);
	}

	/** The same in strokes {@code width} wide (0: a pixel), grown {@code grow} pixels each side (an outline under it). */
	private void arc(float r, int from, int to, int color, float ppu, float width, int grow) {
		int start = Math.max(0, Math.min(from, to));
		int end = Math.min(360, Math.max(from, to));
		if (end <= start) {
			return;
		}
		float pixels = r * ppu;
		int step = Math.max(1, Math.min(30, (int) (360 / Math.max(12, 2 * Math.PI * pixels / 2))));
		float px = 0;
		float py = 0;
		for (int i = start; i <= end; i += step) {
			int deg = Math.min(i, end);
			double rad = Math.toRadians(deg - 90);
			float nx = (float) Math.cos(rad) * r;
			float ny = (float) Math.sin(rad) * r;
			if (i > start) {
				line(px, py, nx, ny, color, width, grow);
			}
			px = nx;
			py = ny;
			if (deg == end) {
				break;
			}
			if (i + step > end && i != end) {
				i = end - step;
			}
		}
	}

	private void box(float x0, float y0, float x1, float y1, int color, boolean invert) {
		push(BOX, Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1), invert ? 0 : color, invert ? INVERT : 0);
	}

	private void line(float x0, float y0, float x1, float y1, int color) {
		push(LINE, x0, y0, x1, y1, color, 0, 0);
	}

	/** A line {@code width} GUI units wide (0: one window pixel), grown {@code grow} window pixels each side, square-ended. */
	private void line(float x0, float y0, float x1, float y1, int color, float width, int grow) {
		push(LINE, x0, y0, x1, y1, color, grow << 1, width);
	}

	private void push(int kind, float a, float b, float c, float d, int color, int flags) {
		push(kind, a, b, c, d, color, flags, 0);
	}

	/** {@code flags}: {@link #INVERT}, and the pixels to grow it by each side shifted left one; {@code width}: a line's, in units. */
	private void push(int kind, float a, float b, float c, float d, int color, int flags, float width) {
		if (count * 8 + 8 > prims.length) {
			prims = Arrays.copyOf(prims, prims.length * 2);
		}
		int i = count * 8;
		prims[i] = kind;
		prims[i + 1] = a;
		prims[i + 2] = b;
		prims[i + 3] = c;
		prims[i + 4] = d;
		prims[i + 5] = Float.intBitsToFloat(color);
		prims[i + 6] = flags;
		prims[i + 7] = width;
		count++;
	}

	// ---------- rasterising ----------

	/**
	 * Fills each window pixel whose centre falls in a primitive (as the GPU fills the game's quads), the primitives in order,
	 * turned by ({@code cos}, {@code sin}) and scaled {@code ppu} pixels a unit about ({@code cx}, {@code cy}). Only the pixels
	 * around each primitive are tested.
	 */
	private void raster(float cx, float cy, float ppu, float cos, float sin, int @org.jspecify.annotations.Nullable [] background) {
		float halfLine = 0.5F / ppu;
		for (int p = 0; p < count; p++) {
			int i = p * 8;
			int kind = (int) prims[i];
			float a = prims[i + 1];
			float b = prims[i + 2];
			float c = prims[i + 3];
			float d = prims[i + 4];
			int color = Float.floatToRawIntBits(prims[i + 5]);
			int flags = (int) prims[i + 6];
			boolean invert = (flags & INVERT) != 0;
			float grow = (flags >> 1) / ppu;
			float stroke = prims[i + 7];
			// A line's half width across (a pixel's half at its least), and how far its ends reach past its points: square ends
			// for a wide or grown stroke, so corners close; none for a hairline, as the game draws it.
			float half = (stroke > 0 ? stroke / 2 : halfLine) + grow;
			float cap = stroke > 0 || grow > 0 ? half : 0;
			// The primitive's corners in pixels, for the pixels to test.
			float minX = Float.MAX_VALUE;
			float minY = Float.MAX_VALUE;
			float maxX = -Float.MAX_VALUE;
			float maxY = -Float.MAX_VALUE;
			float pad = kind == LINE ? half + cap : grow;
			for (int k = 0; k < 4; k++) {
				float ux = (k & 1) == 0 ? Math.min(a, c) - pad : Math.max(a, c) + pad;
				float uy = (k & 2) == 0 ? Math.min(b, d) - pad : Math.max(b, d) + pad;
				float sx = cx + (ux * cos - uy * sin) * ppu;
				float sy = cy + (ux * sin + uy * cos) * ppu;
				minX = Math.min(minX, sx);
				minY = Math.min(minY, sy);
				maxX = Math.max(maxX, sx);
				maxY = Math.max(maxY, sy);
			}
			int x0 = Math.max(0, (int) Math.floor(minX) - 1);
			int y0 = Math.max(0, (int) Math.floor(minY) - 1);
			int x1 = Math.min(width - 1, (int) Math.ceil(maxX) + 1);
			int y1 = Math.min(height - 1, (int) Math.ceil(maxY) + 1);
			float dx = c - a;
			float dy = d - b;
			float length = (float) Math.sqrt(dx * dx + dy * dy);
			float ex = length > 0 ? dx / length : 1;
			float ey = length > 0 ? dy / length : 0;
			for (int py = y0; py <= y1; py++) {
				for (int px = x0; px <= x1; px++) {
					// The pixel's centre back in the crosshair's own units.
					float rx = (px + 0.5F - cx) / ppu;
					float ry = (py + 0.5F - cy) / ppu;
					float ux = rx * cos + ry * sin;
					float uy = -rx * sin + ry * cos;
					boolean inside;
					if (kind == BOX) {
						inside = ux >= a - grow && ux < c + grow && uy >= b - grow && uy < d + grow;
					} else {
						float along = (ux - a) * ex + (uy - b) * ey;
						float across = -(ux - a) * ey + (uy - b) * ex;
						inside = length > 0 && along >= -cap && along < length + cap && across >= -half && across < half;
					}
					if (inside) {
						int at = py * width + px;
						if (background == null) {
							// Drawn by a renderer that inverts what is behind as it draws: marked, in white.
							if (invert) {
								inverted[at] = !inverted[at];
								pixels[at] = inverted[at] ? 0xFFFFFFFF : 0;
							} else {
								inverted[at] = false;
								pixels[at] = pixels[at] == 0 || (color >>> 24) == 255 ? color : over(color, pixels[at]);
							}
						} else {
							int under = pixels[at] != 0 ? pixels[at] : background[at];
							pixels[at] = invert ? 0xFF000000 | ~under & 0xFFFFFF : over(color, under);
						}
						dirtyX0 = Math.min(dirtyX0, px);
						dirtyY0 = Math.min(dirtyY0, py);
						dirtyX1 = Math.max(dirtyX1, px);
						dirtyY1 = Math.max(dirtyY1, py);
					}
				}
			}
		}
	}

	/** {@code top} over the opaque {@code under}. */
	static int over(int top, int under) {
		int a = top >>> 24;
		if (a >= 255) {
			return top;
		}
		if (a == 0) {
			return under;
		}
		int r = ((top >> 16 & 0xFF) * a + (under >> 16 & 0xFF) * (255 - a)) / 255;
		int g = ((top >> 8 & 0xFF) * a + (under >> 8 & 0xFF) * (255 - a)) / 255;
		int b = ((top & 0xFF) * a + (under & 0xFF) * (255 - a)) / 255;
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	// ---------- the backdrops ----------

	/**
	 * A plain backdrop that keeps any crosshair colour readable, {@code width × height} texels: a mid-dark grey ({@code light}: a
	 * light grey) that darkens softly towards the edges, with a faint grid every 8 texels (a GUI unit each) and the middle lines a
	 * touch stronger, so the centre and the crosshair's size read at a glance. Opaque ARGB, row by row.
	 */
	public static int[] backdrop(int width, int height, boolean light) {
		int[] out = new int[width * height];
		int base = light ? 0xFFC9CDD3 : 0xFF3B3E44;
		int line = light ? 0xFFBBBFC6 : 0xFF44484F;
		int middle = light ? 0xFFADB2BA : 0xFF4D525A;
		float cx = width / 2.0F;
		float cy = height / 2.0F;
		float reach = (float) Math.sqrt(cx * cx + cy * cy);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				boolean mid = x == width / 2 || y == height / 2;
				boolean grid = (x - width / 2) % 8 == 0 || (y - height / 2) % 8 == 0;
				int c = mid ? middle : grid ? line : base;
				float dx = (x + 0.5F - cx) / reach;
				float dy = (y + 0.5F - cy) / reach;
				// The vignette: untouched in the middle, about a fifth darker in the corners.
				float k = 1 - 0.22F * Math.min(1, (dx * dx + dy * dy) * 1.6F);
				out[y * width + x] = 0xFF000000 | Math.round((c >> 16 & 0xFF) * k) << 16 | Math.round((c >> 8 & 0xFF) * k) << 8 | Math.round((c & 0xFF) * k);
			}
		}
		return out;
	}

	// ---------- the sample scene ----------

	/**
	 * A Minecraft day, {@code width × height} texels (one a GUI unit): sky from deep to pale blue with a blocky cloud, grass
	 * blocks with dirt under them stepping down to the right and an oak tree; every texel a shade off its block's colour, as
	 * Minecraft's textures are. The same picture each time for a size. Opaque ARGB, row by row.
	 */
	public static int[] scene(int width, int height) {
		int[] out = new int[width * height];
		int ground = Math.round(height * 0.58F);
		for (int y = 0; y < height; y++) {
			float k = y / (float) Math.max(1, ground);
			int sky = mix(0xFF6E9BEF, 0xFFB9D3FF, Math.min(1, k));
			for (int x = 0; x < width; x++) {
				out[y * width + x] = sky;
			}
		}
		// A cloud: two flat blocks of white, Minecraft's own shape.
		int cloudY = Math.max(1, height / 7);
		rect(out, width, height, width / 8, cloudY, width / 3, cloudY + 3, 0xFFF4F7FF);
		rect(out, width, height, width / 8 + 5, cloudY - 2, width / 3 - 6, cloudY, 0xFFF4F7FF);
		rect(out, width, height, width * 2 / 3, cloudY + 4, width * 2 / 3 + 14, cloudY + 6, 0xFFEFF3FF);
		// The ground: 16-texel blocks, a step up on the left.
		int block = 16;
		int originX = width / 2 - block * 2;
		for (int bx = -8; bx < width / block + 8; bx++) {
			int left = originX + bx * block;
			int top = ground + (bx < 0 ? -block / 2 : bx >= 3 ? block / 4 : 0);
			for (int y = Math.max(0, top); y < height; y++) {
				for (int x = Math.max(0, left); x < Math.min(width, left + block); x++) {
					int tx = x - left;
					int ty = y - top;
					boolean grass = ty < 3 || ty < 5 && hash(tx, bx) % 3 == 0;
					int base = grass ? 0xFF5FA03A : 0xFF8B6240;
					out[y * width + x] = shade(base, hash(x, y), grass ? 0.10F : 0.12F);
				}
			}
		}
		// An oak: a trunk and its leaves, right of the centre (so the crosshair crosses sky, leaves and bark).
		int trunkX = width / 2 + 5;
		int trunkTop = ground - 22;
		rect(out, width, height, trunkX, trunkTop, trunkX + 4, ground, 0);
		for (int y = Math.max(0, trunkTop); y < Math.min(height, ground); y++) {
			for (int x = trunkX; x < Math.min(width, trunkX + 4); x++) {
				out[y * width + x] = shade((x - trunkX) % 3 == 0 ? 0xFF5A4327 : 0xFF6B5030, hash(x, y), 0.08F);
			}
		}
		int leafX = trunkX - 10;
		int leafY = trunkTop - 12;
		for (int y = Math.max(0, leafY); y < Math.min(height, leafY + 16); y++) {
			for (int x = Math.max(0, leafX); x < Math.min(width, leafX + 24); x++) {
				boolean corner = (y - leafY < 3 || y - leafY > 12) && (x - leafX < 3 || x - leafX > 20);
				if (!corner && hash(x * 3, y * 7) % 11 != 0) {
					out[y * width + x] = shade(0xFF3F7A2A, hash(x, y), 0.16F);
				}
			}
		}
		return out;
	}

	private static void rect(int[] out, int width, int height, int x0, int y0, int x1, int y1, int argb) {
		if (argb == 0) {
			return;
		}
		for (int y = Math.max(0, y0); y < Math.min(height, y1); y++) {
			for (int x = Math.max(0, x0); x < Math.min(width, x1); x++) {
				out[y * width + x] = argb;
			}
		}
	}

	private static int hash(int x, int y) {
		int h = x * 374761393 + y * 668265263;
		h = (h ^ h >>> 13) * 1274126177;
		return (h ^ h >>> 16) & 0x7FFFFFFF;
	}

	/** {@code argb} a little lighter or darker (by up to {@code amount}), from {@code noise}. */
	private static int shade(int argb, int noise, float amount) {
		float k = 1 + ((noise % 1000) / 1000F * 2 - 1) * amount;
		int r = Math.min(255, Math.round((argb >> 16 & 0xFF) * k));
		int g = Math.min(255, Math.round((argb >> 8 & 0xFF) * k));
		int b = Math.min(255, Math.round((argb & 0xFF) * k));
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	private static int mix(int a, int b, float t) {
		int r = Math.round((a >> 16 & 0xFF) + ((b >> 16 & 0xFF) - (a >> 16 & 0xFF)) * t);
		int g = Math.round((a >> 8 & 0xFF) + ((b >> 8 & 0xFF) - (a >> 8 & 0xFF)) * t);
		int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
		return 0xFF000000 | r << 16 | g << 8 | bl;
	}
}
