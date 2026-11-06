package com.flintclient.client.render;

import com.flintclient.client.FlintLog;
import com.flintclient.client.platform.Frame;
import com.flintclient.client.platform.TextEngine;
import com.flintclient.client.platform.TextRun;
import com.flintclient.client.platform.Texture;
import java.util.Arrays;

/**
 * Draws in physical pixels instead of GUI units, so Flint's shapes and text land on whole screen pixels
 * at every GUI scale. Coordinates passed to it are pixels; {@link #px(float)} converts units. One
 * shared instance is reused every frame, so drawing allocates nothing.
 *
 * <p>A unit is normally a GUI unit ({@link #begin(Frame)}). {@link #begin(Frame, int)}
 * picks another whole number of pixels per unit (the Control Center's own interface size): the pose
 * is the same, only {@link #px} and the text size change, and text is rasterised for that scale so it
 * stays crisp.
 *
 * <p>An opacity below 1 ({@link #setOpacity}) fades everything drawn through the canvas (fills, shapes,
 * icons and text) for fade animations; at 1 colours pass through untouched. {@link #pushOffset} moves
 * drawing by whole pixels (slide animations) without breaking {@link #pushClip}.
 *
 * <p>Everything the engine does (transforms, fills, textured quads, text runs, scissor rectangles) goes through the
 * {@link Frame} it was begun on, so this class is the same on every Minecraft line.
 */
public final class Canvas {
	private static final Canvas SHARED = new Canvas(1);
	private static boolean warnedScale;
	/** Rounded rectangles up to this many pixels are drawn as one atlas quad (buttons, up to GUI scale 9). */
	private static final int SINGLE_QUAD_MAX_AREA = 120_000;
	/** Corners for {@link #roundRectCorners(int, int, int, int, int, int, int)}. */
	public static final int TOP_LEFT = 1;
	public static final int TOP_RIGHT = 2;
	public static final int BOTTOM_LEFT = 4;
	public static final int BOTTOM_RIGHT = 8;
	public static final int ALL_CORNERS = 15;

	private Frame frame;
	private TextEngine text;
	/**
	 * Pixels per unit for {@link #px} and text: a whole number but for a frame begun at a fractional scale
	 * ({@link #beginAtGuiScale(Frame, float)}: the main menu's own size), where text is rasterised for that exact size.
	 */
	private float scale = 1;
	/** Pixels per GUI unit (the pose undoes it). */
	private int guiScale = 1;
	/** While above 0, what {@link #unitsPerGui()} reports (a frame drawing a HUD at another size); clips still use the real one. */
	private float guiOverride;
	private float[] outerOverrides = new float[4];
	private int depth;
	/** The unit scale of each enclosing {@link #begin}, restored by {@link #end}. */
	private float[] outerScales = new float[8];
	private float opacity = 1.0F;
	private int clips;
	private final int[] clipBox = new int[4];
	/** The total {@link #pushOffset} move in pixels, and each open one's move (x, y pairs) for {@link #popOffset}. */
	/**
	 * The transform {@link #pushOffset} and {@link #pushZoom} have applied since the outermost begin, as
	 * {@code x' = zoom × x + moveX} (likewise y); and each open one's previous values (3 per level) for popping.
	 * With offsets alone it is a whole-pixel move, exactly as before zooms existed.
	 */
	private float zoom = 1.0F;
	private float moveX;
	private float moveY;
	private int offsetDepth;
	private float[] saved = new float[24];

	/** {@code guiScale}: pixels per GUI unit until the first {@link #begin} reads the window's (tests set it). */
	Canvas(int guiScale) {
		this.guiScale = guiScale;
	}

	/** Starts pixel-space drawing in GUI units; pair with {@link #end()}. Nested calls share the same transform. */
	public static Canvas begin(Frame frame) {
		return begin(frame, 0);
	}

	/**
	 * Starts pixel-space drawing with {@code unitScale} pixels per unit ({@code 0}: the GUI scale); pair
	 * with {@link #end()}. The outermost call sets up the transform (as {@link #begin(Frame)}
	 * always has); nested calls keep it and only switch the unit scale until their {@code end}.
	 */
	public static Canvas begin(Frame frame, int unitScale) {
		Canvas canvas = SHARED;
		if (canvas.depth == 0) {
			canvas.frame = frame;
			canvas.guiScale = Frame.windowGuiScale();
			canvas.text = frame.beginPixels(canvas.guiScale);
		}
		canvas.enter(unitScale);
		return canvas;
	}

	/**
	 * {@link #begin}'s bookkeeping: the outermost call starts at full opacity with no clips or offsets;
	 * every call stacks the unit scale it replaces.
	 */
	void enter(int unitScale) {
		enter((float) unitScale);
	}

	/** {@link #enter(int)} at a scale that may be fractional (0: the enclosing frame's GUI scale). */
	void enter(float unitScale) {
		if (depth == 0) {
			guiOverride = 0;
			opacity = 1.0F;
			clips = 0;
			offsetDepth = 0;
			zoom = 1.0F;
			moveX = 0;
			moveY = 0;
		}
		if (depth == outerScales.length) {
			outerScales = Arrays.copyOf(outerScales, depth * 2);
		}
		if (depth == outerOverrides.length) {
			outerOverrides = Arrays.copyOf(outerOverrides, depth * 2);
		}
		outerOverrides[depth] = guiOverride;
		outerScales[depth++] = scale;
		// Without a unit scale of its own, a nested frame draws GUI units at the enclosing frame's GUI scale (so a
		// widget drawn inside a menu frame at a larger scale comes out at that size).
		scale = unitScale > 0 ? unitScale : unitsPerGui();
	}

	/** {@link #end}'s bookkeeping: restores the enclosing unit scale; true when the outermost begin ended (an unbalanced end does nothing). */
	boolean exit() {
		if (depth == 0) {
			return false;
		}
		scale = outerScales[--depth];
		guiOverride = outerOverrides[depth];
		return depth == 0;
	}

	/** Nested {@link #begin}s open now (0 outside a frame). */
	int depth() {
		return depth;
	}

	/** Clips pushed and not yet popped. */
	int openClips() {
		return clips;
	}

	/** The shared canvas for measuring text outside a frame (for layout); it must not draw. */
	public static Canvas measuring() {
		Canvas canvas = SHARED;
		if (canvas.depth == 0) {
			canvas.text = Frame.measuringText();
			canvas.guiScale = Frame.windowGuiScale();
			canvas.scale = canvas.guiScale;
		}
		return canvas;
	}

	/**
	 * {@link #measuring()} at {@code unitScale} pixels per unit. Inside a frame the canvas keeps the
	 * frame's unit scale (it can't change without a {@link #begin}), so measuring there must use the same
	 * one; a mismatch is a layout bug, logged once rather than allowed to stop the game.
	 */
	public static Canvas measuring(int unitScale) {
		return measuring((float) unitScale);
	}

	/** {@link #measuring(int)} at a scale that may be fractional (the main menu's own size, {@link #beginAtGuiScale(Frame, float)}). */
	public static Canvas measuring(float unitScale) {
		Canvas canvas = SHARED;
		if (canvas.depth == 0) {
			measuring();
			canvas.scale = Math.max(1.0F, unitScale);
		} else if (canvas.scale != unitScale && !warnedScale) {
			warnedScale = true;
			FlintLog.LOGGER.warn("Flint measured text at scale {} inside a frame drawn at {}", unitScale, canvas.scale, new IllegalStateException());
		}
		return canvas;
	}

	public void end() {
		if (exit()) {
			// A clip or offset left open (an exception mid-draw) mustn't leak into whatever draws next.
			while (clips > 0) {
				popClip();
			}
			while (offsetDepth > 0) {
				popOffset();
			}
			frame.endPixels();
			frame = null;
			opacity = 1.0F;
		}
	}

	/** Pixels per unit, as a whole number (a fractional {@link #unitScale()} rounded; at least 1): line widths and the like. */
	public int scale() {
		return Math.max(1, Math.round(scale));
	}

	/** Pixels per unit exactly (fractional in a frame begun at a fractional scale): converting pixel sizes back to units. */
	public float unitScale() {
		return scale;
	}

	/** Pixels per GUI unit (the window's GUI scale, or a nested {@link #beginAtGuiScale} frame's), whatever the unit scale. */
	public int guiScale() {
		return guiOverride > 0 ? Math.max(1, Math.round(guiOverride)) : guiScale;
	}

	/** {@link #guiScale()} exactly: a nested frame without its own unit scale draws at this one. */
	private float unitsPerGui() {
		return guiOverride > 0 ? guiOverride : guiScale;
	}

	/**
	 * A nested frame inside an open one in which a GUI unit is {@code pixelsPerGuiUnit} pixels: {@link #guiScale()}
	 * and the unit scale both report it, so something drawn in GUI units (the HUD) comes out at that size, as crisp
	 * as at its own. Clips keep landing on real pixels. Pair with {@link #end()}.
	 */
	public static Canvas beginAtGuiScale(Frame frame, int pixelsPerGuiUnit) {
		return beginAtGuiScale(frame, (float) Math.max(1, pixelsPerGuiUnit));
	}

	/**
	 * {@link #beginAtGuiScale(Frame, int)} at a fractional number of pixels per unit (Flint's main menu, sized from the window
	 * rather than Minecraft's GUI scale): {@link #px} rounds each position to a whole pixel, and text is rasterised for its exact
	 * size there ({@link TextLine}), so it stays as crisp as at a whole scale. Widgets drawn inside (their own
	 * {@link #begin(Frame)}) come out at the same scale.
	 */
	public static Canvas beginAtGuiScale(Frame frame, float pixelsPerGuiUnit) {
		Canvas canvas = SHARED;
		if (canvas.depth == 0) {
			canvas.frame = frame;
			canvas.guiScale = Frame.windowGuiScale();
			canvas.text = frame.beginPixels(canvas.guiScale);
		}
		float units = Math.max(1.0F, pixelsPerGuiUnit);
		canvas.enter(units);
		canvas.guiOverride = units;
		return canvas;
	}

	/** Units to whole pixels. */
	public int px(float units) {
		return Math.round(units * scale);
	}

	/** The frame this canvas draws into (the engine's drawing, for code on its side of the platform classes). */
	public Frame frame() {
		return frame;
	}

	/** The text engine text is measured and prepared with. */
	public TextEngine text() {
		return text;
	}

	// ---------- opacity ----------

	/** The opacity everything is drawn with (1 unless a fade set it). */
	public float opacity() {
		return opacity;
	}

	/**
	 * Multiplies the alpha of everything drawn from now on (0..1); callers restore the previous value
	 * when their faded part ends. Reset to 1 by the outermost {@link #begin}.
	 */
	public void setOpacity(float opacity) {
		this.opacity = Math.clamp(opacity, 0.0F, 1.0F);
	}

	/** {@code argb} with the current opacity applied (unchanged at full opacity). */
	public int tint(int argb) {
		return opacity == 1.0F ? argb : Palette.withAlpha(argb, opacity);
	}

	// ---------- offsets ----------

	/**
	 * Moves everything drawn until the matching {@link #popOffset()} by whole pixels (open and close
	 * animations). Nested offsets add up. Clips pushed meanwhile land exactly on their moved rectangles.
	 */
	public void pushOffset(int dxPx, int dyPx) {
		save();
		moveX += zoom * dxPx;
		moveY += zoom * dyPx;
		frame.pushTranslate(dxPx, dyPx);
	}

	/**
	 * Scales everything drawn until the matching {@link #popOffset()} by {@code scale} about the pixel point
	 * ({@code cx}, {@code cy}) (a window growing into place). Clips pushed meanwhile are scaled with it. Text and
	 * masks are drawn at their usual resolution and scaled, so this is for brief animations.
	 */
	public void pushZoom(float scale, float cx, float cy) {
		save();
		moveX += zoom * cx;
		moveY += zoom * cy;
		zoom *= scale;
		moveX -= zoom * cx;
		moveY -= zoom * cy;
		frame.pushZoom(scale, cx, cy);
	}

	private void save() {
		if (3 * offsetDepth + 3 > saved.length) {
			saved = Arrays.copyOf(saved, saved.length * 2);
		}
		saved[3 * offsetDepth] = zoom;
		saved[3 * offsetDepth + 1] = moveX;
		saved[3 * offsetDepth + 2] = moveY;
		offsetDepth++;
	}

	/** Ends the innermost {@link #pushOffset} or {@link #pushZoom}. */
	public void popOffset() {
		if (offsetDepth > 0) {
			offsetDepth--;
			zoom = saved[3 * offsetDepth];
			moveX = saved[3 * offsetDepth + 1];
			moveY = saved[3 * offsetDepth + 2];
			frame.pop();
		}
	}

	// ---------- clipping ----------

	/**
	 * Clips everything drawn until {@link #popClip()} to a pixel rectangle (nested clips intersect), moved
	 * by any open {@link #pushOffset}. Minecraft keeps scissor rectangles in whole GUI units (it maps the corners
	 * through the pose and floors them, then multiplies by the GUI scale), so a rectangle is rounded inwards to GUI units:
	 * nothing ever shows outside it, and up to {@code guiScale - 1} pixels at its edges may be cut early.
	 * The rectangle is in the canvas's own pixel space: transforms enclosing the outermost {@link #begin}
	 * apply to it, pose changes made since (other than offsets) don't.
	 */
	public void pushClip(int x0, int y0, int x1, int y1) {
		int[] gui = clipBox;
		// Rounded where it really lands: an offset that isn't a whole number of GUI units would otherwise
		// move the floored rectangle outwards.
		toGuiInside(Math.round(zoom * x0 + moveX), Math.round(zoom * y0 + moveY), Math.round(zoom * x1 + moveX),
			Math.round(zoom * y1 + moveY), guiScale, gui);
		frame.scissor(gui[0], gui[1], gui[2], gui[3], guiScale);
		clips++;
	}

	/** The largest whole-GUI-unit rectangle inside a pixel rectangle, into {@code out} (x0, y0, x1, y1; never inverted). */
	static void toGuiInside(int x0, int y0, int x1, int y1, int guiScale, int[] out) {
		out[0] = Math.ceilDiv(x0, guiScale);
		out[1] = Math.ceilDiv(y0, guiScale);
		out[2] = Math.max(out[0], Math.floorDiv(x1, guiScale));
		out[3] = Math.max(out[1], Math.floorDiv(y1, guiScale));
	}

	public void popClip() {
		if (clips > 0) {
			clips--;
			frame.endScissor();
		}
	}

	// ---------- shapes ----------

	public void fill(int x0, int y0, int x1, int y1, int argb) {
		fillRaw(x0, y0, x1, y1, tint(argb));
	}

	private void fillRaw(int x0, int y0, int x1, int y1, int argb) {
		if (x1 > x0 && y1 > y0 && (argb >>> 24) != 0) {
			frame.fill(x0, y0, x1, y1, argb);
		}
	}

	public void gradient(int x0, int y0, int x1, int y1, int top, int bottom) {
		frame.gradient(x0, y0, x1, y1, tint(top), tint(bottom));
	}

	/**
	 * A rounded box whose colour runs from {@code top} to {@code bottom}: the middle and the sides as gradients, each
	 * corner in the colour at its height (the gradients these boxes use are gentle enough that it can't be seen).
	 */
	public void roundGradient(int x, int y, int w, int h, int r, int top, int bottom) {
		if (w <= 0 || h <= 0) {
			return;
		}
		if (top == bottom) {
			roundRectCorners(x, y, w, h, r, top);
			return;
		}
		r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
		gradient(x + r, y, x + w - r, y + h, top, bottom);
		if (r == 0) {
			return;
		}
		int sideTop = Palette.lerp(top, bottom, r / (float) h);
		int sideBottom = Palette.lerp(top, bottom, (h - r) / (float) h);
		gradient(x, y + r, x + r, y + h - r, sideTop, sideBottom);
		gradient(x + w - r, y + r, x + w, y + h - r, sideTop, sideBottom);
		int cornerTop = Palette.lerp(top, bottom, r / 2.0F / h);
		int cornerBottom = Palette.lerp(top, bottom, (h - r / 2.0F) / h);
		quarter(x, y, r, TOP_LEFT, cornerTop);
		quarter(x + w - r, y, r, TOP_RIGHT, cornerTop);
		quarter(x, y + h - r, r, BOTTOM_LEFT, cornerBottom);
		quarter(x + w - r, y + h - r, r, BOTTOM_RIGHT, cornerBottom);
	}

	/**
	 * A rounded box filled as a CSS {@code linear-gradient(angle, stops...)}: {@code angleDeg} 0 runs to the top, 90 to the
	 * right; the stops are evenly spaced. Drawn as vertical strips {@code stripPx} wide, each an exact vertical gradient
	 * (split where it crosses a stop), so only the steps across the strips approximate the angle; each corner takes the
	 * colour at its middle.
	 */
	public void roundLinear(int x, int y, int w, int h, int r, float angleDeg, int[] stops, int stripPx) {
		if (w <= 0 || h <= 0) {
			return;
		}
		r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
		Linear g = new Linear(x, y, w, h, angleDeg, stops);
		int step = Math.abs(g.dx) < 0.02F ? w : Math.max(1, stripPx);
		// The sides stop short of the corners (drawn after); the middle runs the full height.
		g.strips(this, x, x + r, y + r, y + h - r, step);
		g.strips(this, x + r, x + w - r, y, y + h, step);
		g.strips(this, x + w - r, x + w, y + r, y + h - r, step);
		if (r > 0) {
			float half = r / 2.0F;
			quarter(x, y, r, TOP_LEFT, g.at(x + half, y + half));
			quarter(x + w - r, y, r, TOP_RIGHT, g.at(x + w - half, y + half));
			quarter(x, y + h - r, r, BOTTOM_LEFT, g.at(x + half, y + h - half));
			quarter(x + w - r, y + h - r, r, BOTTOM_RIGHT, g.at(x + w - half, y + h - half));
		}
	}

	/**
	 * The outline of a rounded box, {@code b} px wide, coloured as {@link #roundLinear}: the top and bottom edges in
	 * {@code segments} steps, the sides as exact vertical gradients, each corner in the colour at its middle.
	 */
	public void ringLinear(int x, int y, int w, int h, int r, int b, float angleDeg, int[] stops, int segments) {
		if (w <= 0 || h <= 0 || b <= 0) {
			return;
		}
		r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
		Linear g = new Linear(x, y, w, h, angleDeg, stops);
		int span = w - 2 * r;
		int n = Math.max(1, Math.min(segments, span / 2));
		for (int i = 0; i < n; i++) {
			int sx = x + r + span * i / n;
			int ex = x + r + span * (i + 1) / n;
			float mid = (sx + ex) / 2.0F;
			fill(sx, y, ex, y + b, g.at(mid, y + b / 2.0F));
			fill(sx, y + h - b, ex, y + h, g.at(mid, y + h - b / 2.0F));
		}
		g.column(this, x, x + b, y + r, y + h - r);
		g.column(this, x + w - b, x + w, y + r, y + h - r);
		if (r > 0) {
			int rb = Math.min(b, r);
			float half = r / 2.0F;
			ringQuarter(x, y, r, rb, TOP_LEFT, g.at(x + half, y + half));
			ringQuarter(x + w - r, y, r, rb, TOP_RIGHT, g.at(x + w - half, y + half));
			ringQuarter(x, y + h - r, r, rb, BOTTOM_LEFT, g.at(x + half, y + h - half));
			ringQuarter(x + w - r, y + h - r, r, rb, BOTTOM_RIGHT, g.at(x + w - half, y + h - half));
		}
	}

	/** A CSS linear gradient over one box: the colour at any point, and vertical runs of it drawn exactly. */
	private static final class Linear {
		final float dx;
		final float dy;
		final float cx;
		final float cy;
		final float length;
		final int[] stops;

		Linear(int x, int y, int w, int h, float angleDeg, int[] stops) {
			double a = Math.toRadians(angleDeg);
			this.dx = (float) Math.sin(a);
			this.dy = (float) -Math.cos(a);
			this.cx = x + w / 2.0F;
			this.cy = y + h / 2.0F;
			// As CSS: the gradient line is as long as the box's projection onto it.
			this.length = Math.max(1, Math.abs(w * dx) + Math.abs(h * dy));
			this.stops = stops;
		}

		/** How far along the gradient line ({@code px}, {@code py}) is: 0 at its start, 1 at its end. */
		float t(float px, float py) {
			return ((px - cx) * dx + (py - cy) * dy) / length + 0.5F;
		}

		int color(float t) {
			int last = stops.length - 1;
			if (last <= 0) {
				return stops[0];
			}
			float u = Math.clamp(t, 0, 1) * last;
			int i = Math.min(last - 1, (int) u);
			return Palette.lerp(stops[i], stops[i + 1], u - i);
		}

		int at(float px, float py) {
			return color(t(px, py));
		}

		/** {@code x0..x1} from {@code y0} to {@code y1} as columns at most {@code step} wide. */
		void strips(Canvas c, int x0, int x1, int y0, int y1, int step) {
			for (int sx = x0; sx < x1; sx += step) {
				column(c, sx, Math.min(x1, sx + step), y0, y1);
			}
		}

		/** The columns {@code x0..x1} from {@code y0} to {@code y1} as vertical gradients, split where they cross a stop. */
		void column(Canvas c, int x0, int x1, int y0, int y1) {
			if (x1 <= x0 || y1 <= y0) {
				return;
			}
			float mid = (x0 + x1) / 2.0F;
			float t0 = Math.clamp(t(mid, y0), 0, 1);
			float t1 = Math.clamp(t(mid, y1), 0, 1);
			int last = stops.length - 1;
			int top = y0;
			float tTop = t0;
			if (last > 1 && t1 != t0) {
				for (int k = 1; k < last; k++) {
					float tk = k / (float) last;
					if ((tk - t0) * (tk - t1) < 0) {
						int at = Math.round(y0 + (y1 - y0) * (tk - t0) / (t1 - t0));
						if (at > top && at < y1) {
							c.gradient(x0, top, x1, at, color(tTop), color(tk));
							top = at;
							tTop = tk;
						}
					}
				}
			}
			c.gradient(x0, top, x1, y1, color(tTop), color(t1));
		}
	}

	/** A filled rectangle with antialiased rounded corners of radius {@code r} px. */
	public void roundRect(int x, int y, int w, int h, int r, int argb) {
		argb = tint(argb);
		if (w <= 0 || h <= 0 || (argb >>> 24) == 0) {
			return;
		}
		r = Math.min(r, Math.min(w, h) / 2);
		if (r <= 0) {
			frame.fill(x, y, x + w, y + h, argb);
			return;
		}
		if (w * h <= SINGLE_QUAD_MAX_AREA) {
			// Shapes up to button size are one pre-rasterised quad (cheaper to draw every frame).
			MaskAtlas.Entry e = MaskAtlas.get().roundRect(w, h, r);
			blit(e, x, y, w, h, false, false, argb);
			return;
		}
		cornersAndFills(x, y, w, h, r, argb);
	}

	/**
	 * {@link #roundRect} always drawn as four corners and three fills, so it adds one small mask per
	 * radius however many sizes it is drawn at (HUD panels that change size with their content).
	 */
	public void roundRectCorners(int x, int y, int w, int h, int r, int argb) {
		argb = tint(argb);
		if (w <= 0 || h <= 0 || (argb >>> 24) == 0) {
			return;
		}
		r = Math.min(r, Math.min(w, h) / 2);
		if (r <= 0) {
			frame.fill(x, y, x + w, y + h, argb);
			return;
		}
		cornersAndFills(x, y, w, h, r, argb);
	}

	/**
	 * {@link #roundRectCorners} with only some corners rounded ({@link #TOP_LEFT} | ...), the others
	 * square: a rail or side panel at one edge of a rounded window.
	 */
	public void roundRectCorners(int x, int y, int w, int h, int r, int argb, int corners) {
		argb = tint(argb);
		if (w <= 0 || h <= 0 || (argb >>> 24) == 0) {
			return;
		}
		r = Math.min(r, Math.min(w, h) / 2);
		if (r <= 0 || corners == 0) {
			frame.fill(x, y, x + w, y + h, argb);
			return;
		}
		corner(x, y, r, false, false, argb, (corners & TOP_LEFT) != 0);
		corner(x + w - r, y, r, true, false, argb, (corners & TOP_RIGHT) != 0);
		corner(x, y + h - r, r, false, true, argb, (corners & BOTTOM_LEFT) != 0);
		corner(x + w - r, y + h - r, r, true, true, argb, (corners & BOTTOM_RIGHT) != 0);
		fillRaw(x + r, y, x + w - r, y + r, argb);
		fillRaw(x, y + r, x + w, y + h - r, argb);
		fillRaw(x + r, y + h - r, x + w - r, y + h, argb);
	}

	/**
	 * One antialiased quarter circle of radius {@code r} px filling the square at ({@code x}, {@code y}), its
	 * curve on the side {@code which} names ({@link #TOP_LEFT} and so on): two make a half circle.
	 */
	public void quarter(int x, int y, int r, int which, int argb) {
		argb = tint(argb);
		if (r > 0 && (argb >>> 24) != 0) {
			boolean right = which == TOP_RIGHT || which == BOTTOM_RIGHT;
			boolean bottom = which == BOTTOM_LEFT || which == BOTTOM_RIGHT;
			blit(MaskAtlas.get().corner(r), x, y, r, r, right, bottom, argb);
		}
	}

	/**
	 * One quarter of a {@code b}-px ring of outer radius {@code r} px in the square at ({@code x}, {@code y}), its
	 * curve on the side {@code which} names: hairline edges that turn a rounded corner.
	 */
	public void ringQuarter(int x, int y, int r, int b, int which, int argb) {
		argb = tint(argb);
		if (r > 0 && b > 0 && (argb >>> 24) != 0) {
			boolean right = which == TOP_RIGHT || which == BOTTOM_RIGHT;
			boolean bottom = which == BOTTOM_LEFT || which == BOTTOM_RIGHT;
			blit(MaskAtlas.get().ringCorner(r, Math.min(b, r)), x, y, r, r, right, bottom, argb);
		}
	}

	private void corner(int x, int y, int r, boolean flipX, boolean flipY, int argb, boolean round) {
		if (round) {
			blit(MaskAtlas.get().corner(r), x, y, r, r, flipX, flipY, argb);
		} else {
			fillRaw(x, y, x + r, y + r, argb);
		}
	}

	private void cornersAndFills(int x, int y, int w, int h, int r, int argb) {
		// Four corners and three fills, so the atlas stays small.
		MaskAtlas.Entry c = MaskAtlas.get().corner(r);
		blit(c, x, y, r, r, false, false, argb);
		blit(c, x + w - r, y, r, r, true, false, argb);
		blit(c, x, y + h - r, r, r, false, true, argb);
		blit(c, x + w - r, y + h - r, r, r, true, true, argb);
		fillRaw(x + r, y, x + w - r, y + r, argb);
		fillRaw(x, y + r, x + w, y + h - r, argb);
		fillRaw(x + r, y + h - r, x + w - r, y + h, argb);
	}

	/**
	 * A rounded rectangle with a {@code borderPx} border: the fill, then the border ring on top. Works with
	 * translucent fills and borders (nothing is painted twice inside the ring).
	 */
	public void panel(int x, int y, int w, int h, int r, int borderPx, int border, int fill) {
		roundRect(x, y, w, h, r, fill);
		if (borderPx > 0) {
			ring(x, y, w, h, r, borderPx, border);
		}
	}

	/** {@link #panel} drawn only with corner masks ({@link #roundRectCorners}, {@link #ringCorners}). */
	public void panelCorners(int x, int y, int w, int h, int r, int borderPx, int border, int fill) {
		roundRectCorners(x, y, w, h, r, fill);
		if (borderPx > 0) {
			ringCorners(x, y, w, h, r, borderPx, border);
		}
	}

	/** The outline of a rounded rectangle, {@code b} px wide. */
	public void ring(int x, int y, int w, int h, int r, int b, int argb) {
		argb = tint(argb);
		if (w <= 0 || h <= 0 || b <= 0 || (argb >>> 24) == 0) {
			return;
		}
		r = Math.min(r, Math.min(w, h) / 2);
		if (w * h <= SINGLE_QUAD_MAX_AREA) {
			MaskAtlas.Entry e = MaskAtlas.get().ring(w, h, r, b);
			blit(e, x, y, w, h, false, false, argb);
			return;
		}
		ringEdges(x, y, w, h, r, b, argb);
	}

	/** {@link #ring} always drawn as ring corners and straight edges (bounded atlas use, as {@link #roundRectCorners}). */
	public void ringCorners(int x, int y, int w, int h, int r, int b, int argb) {
		argb = tint(argb);
		if (w <= 0 || h <= 0 || b <= 0 || (argb >>> 24) == 0) {
			return;
		}
		ringEdges(x, y, w, h, Math.min(r, Math.min(w, h) / 2), b, argb);
	}

	private void ringEdges(int x, int y, int w, int h, int r, int b, int argb) {
		// Large outlines: four ring corners and four straight edges.
		if (r > 0) {
			MaskAtlas.Entry c = MaskAtlas.get().ringCorner(r, Math.min(b, r));
			blit(c, x, y, r, r, false, false, argb);
			blit(c, x + w - r, y, r, r, true, false, argb);
			blit(c, x, y + h - r, r, r, false, true, argb);
			blit(c, x + w - r, y + h - r, r, r, true, true, argb);
		}
		fillRaw(x + r, y, x + w - r, y + b, argb);
		fillRaw(x + r, y + h - b, x + w - r, y + h, argb);
		fillRaw(x, y + r, x + b, y + h - r, argb);
		fillRaw(x + w - b, y + r, x + w, y + h - r, argb);
	}

	/** A whole {@code texW × texH} texture drawn into the pixel box, tinted by {@code argb} and the canvas opacity. */
	public void texture(Texture texture, int x, int y, int w, int h, int texW, int texH, int argb) {
		argb = tint(argb);
		if ((argb >>> 24) == 0 || w <= 0 || h <= 0) {
			return;
		}
		frame.blit(texture, x, y, 0, 0, w, h, texW, texH, texW, texH, argb);
	}

	/** Rounds the corners of a square image by covering them in {@code surroundArgb}. */
	public void roundCorners(int x, int y, int w, int h, int r, int surroundArgb) {
		r = Math.min(r, Math.min(w, h) / 2);
		if (r <= 0) {
			return;
		}
		surroundArgb = tint(surroundArgb);
		MaskAtlas.Entry n = MaskAtlas.get().notch(r);
		blit(n, x, y, r, r, false, false, surroundArgb);
		blit(n, x + w - r, y, r, r, true, false, surroundArgb);
		blit(n, x, y + h - r, r, r, false, true, surroundArgb);
		blit(n, x + w - r, y + h - r, r, r, true, true, surroundArgb);
	}

	/** A Font Awesome Solid glyph filling the {@code sizePx} square at ({@code x}, {@code y}) (one em, centred across). */
	public void glyph(int codepoint, int x, int y, int sizePx, int argb) {
		argb = tint(argb);
		if ((argb >>> 24) != 0 && sizePx > 0) {
			blit(MaskAtlas.get().glyph(codepoint, sizePx), x, y, sizePx, sizePx, false, false, argb);
		}
	}

	public void icon(Icon icon, int x, int y, int sizePx, int argb) {
		argb = tint(argb);
		if ((argb >>> 24) != 0) {
			blit(MaskAtlas.get().icon(icon, sizePx), x, y, sizePx, sizePx, false, false, argb);
		}
	}

	/** A mod card's line icon ({@link CardIcons#index}), {@code sizePx} square. */
	public void cardIcon(int index, int x, int y, int sizePx, int argb) {
		argb = tint(argb);
		if ((argb >>> 24) != 0 && sizePx > 0) {
			blit(MaskAtlas.get().cardIcon(index, sizePx), x, y, sizePx, sizePx, false, false, argb);
		}
	}

	/** A solid icon, the same size and placement as the matching {@link Icon}. */
	public void filledIcon(FilledIcon icon, int x, int y, int sizePx, int argb) {
		argb = tint(argb);
		if ((argb >>> 24) != 0 && sizePx > 0) {
			blit(MaskAtlas.get().filledIcon(icon, sizePx), x, y, sizePx, sizePx, false, false, argb);
		}
	}

	/** The Flint mark, {@code heightPx} tall, at full colour (alpha scales it). */
	public void mark(int x, int y, int heightPx, float alpha) {
		MaskAtlas.Entry e = MaskAtlas.get().mark(heightPx);
		blit(e, x, y, e.width(), e.height(), false, false, tint(Palette.withAlpha(0xFFFFFFFF, alpha)));
	}

	private void blit(MaskAtlas.Entry e, int x, int y, int w, int h, boolean flipX, boolean flipY, int argb) {
		float u = flipX ? e.u() + e.width() : e.u();
		float v = flipY ? e.v() + e.height() : e.v();
		int sw = flipX ? -e.width() : e.width();
		int sh = flipY ? -e.height() : e.height();
		frame.blit(e.texture(), x, y, u, v, w, h, sw, sh, e.textureWidth(), e.textureHeight(), argb);
	}

	// ---------- text ----------

	/** Measures (and caches) a line at the current scale. */
	public int width(TextLine line) {
		line.prepare(scale, text);
		return line.widthPx();
	}

	/** The width a line takes when drawn at most {@code maxPx} wide (shortened with an ellipsis if needed). */
	public int width(TextLine line, int maxPx) {
		return line.fitted(scale, text, maxPx).widthPx();
	}

	/** Draws a line with its baseline at {@code baselinePx}. */
	public void text(TextLine line, int x, int baselinePx, int argb) {
		argb = tint(argb);
		if ((argb >>> 24) == 0) {
			return;
		}
		line.prepare(scale, text);
		int s = line.drawScale();
		float top = (float) baselinePx / s - FlintFonts.BASELINE;
		TextRun whole = line.whole();
		if (whole != null) {
			frame.text(whole, (float) x / s, top, s, argb);
		} else {
			int[] offsets = line.offsets();
			TextRun[] glyphs = line.glyphs();
			for (int i = 0; i < glyphs.length; i++) {
				frame.text(glyphs[i], (float) (x + offsets[i]) / s, top, s, argb);
			}
		}
	}

	/** Draws a line at most {@code maxPx} wide: cut short with an ellipsis when it is wider. */
	public void text(TextLine line, int x, int baselinePx, int argb, int maxPx) {
		text(line.fitted(scale, text, maxPx), x, baselinePx, argb);
	}

	/** Draws a line ending at {@code rightPx}. */
	public void textRight(TextLine line, int rightPx, int baselinePx, int argb) {
		text(line, rightPx - width(line), baselinePx, argb);
	}

	/** Draws a line at most {@code maxPx} wide, ending at {@code rightPx}. */
	public void textRight(TextLine line, int rightPx, int baselinePx, int argb, int maxPx) {
		TextLine shown = line.fitted(scale, text, maxPx);
		text(shown, rightPx - shown.widthPx(), baselinePx, argb);
	}

	/** Draws a line centred on {@code centerPx}. */
	public void textCentered(TextLine line, int centerPx, int baselinePx, int argb) {
		text(line, centerPx - width(line) / 2, baselinePx, argb);
	}

	/** Draws a line at most {@code maxPx} wide, centred on {@code centerPx}. */
	public void textCentered(TextLine line, int centerPx, int baselinePx, int argb, int maxPx) {
		TextLine shown = line.fitted(scale, text, maxPx);
		text(shown, centerPx - shown.widthPx() / 2, baselinePx, argb);
	}

	/** Baseline that vertically centres a line's capitals on {@code centerPx}. */
	public int baselineFor(TextLine line, int centerPx) {
		line.prepare(scale, text);
		return Math.round(centerPx + line.capHeightPx(scale) / 2.0F);
	}

	/** Baseline that vertically centres capitals of {@code face} on {@code centerPx} (for {@link DigitRun}). */
	public int baselineFor(FontFace face, int centerPx) {
		return Math.round(centerPx + TextLine.capHeightPx(face, scale) / 2.0F);
	}
}
