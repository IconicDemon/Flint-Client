package com.flintclient.client.render;

import com.flintclient.client.FlintClient;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;
import org.joml.Matrix3x2fc;

/**
 * Draws in physical pixels instead of GUI units, so Flint's shapes and text land on whole screen pixels
 * at every GUI scale. Coordinates passed to it are pixels; {@link #px(float)} converts units. One
 * shared instance is reused every frame, so drawing allocates nothing.
 *
 * <p>A unit is normally a GUI unit ({@link #begin(GuiGraphicsExtractor)}). {@link #begin(GuiGraphicsExtractor, int)}
 * picks another whole number of pixels per unit (the Control Center's own interface size): the pose
 * is the same, only {@link #px} and the text size change, and text is rasterised for that scale so it
 * stays crisp.
 *
 * <p>An opacity below 1 ({@link #setOpacity}) fades everything drawn through the canvas (fills, shapes,
 * icons and text) for fade animations; at 1 colours pass through untouched. {@link #pushOffset} moves
 * drawing by whole pixels (slide animations) without breaking {@link #pushClip}.
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

	private GuiGraphicsExtractor graphics;
	private Font font;
	/** Pixels per unit for {@link #px} and text. */
	private int scale = 1;
	/** Pixels per GUI unit (the pose undoes it). */
	private int guiScale = 1;
	/** While above 0, what {@link #guiScale()} reports (a frame drawing a HUD at another size); clips still use the real one. */
	private int guiOverride;
	private int[] outerOverrides = new int[4];
	private int depth;
	/** The unit scale of each enclosing {@link #begin}, restored by {@link #end}. */
	private int[] outerScales = new int[8];
	private float opacity = 1.0F;
	private int clips;
	private final int[] clipBox = new int[4];
	/** The pose the outermost {@link #begin} set up (pixel space); scissor rectangles are given relative to it. */
	private final Matrix3x2f pixelPose = new Matrix3x2f();
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
	public static Canvas begin(GuiGraphicsExtractor graphics) {
		return begin(graphics, 0);
	}

	/**
	 * Starts pixel-space drawing with {@code unitScale} pixels per unit ({@code 0}: the GUI scale); pair
	 * with {@link #end()}. The outermost call sets up the transform (as {@link #begin(GuiGraphicsExtractor)}
	 * always has); nested calls keep it and only switch the unit scale until their {@code end}.
	 */
	public static Canvas begin(GuiGraphicsExtractor graphics, int unitScale) {
		Canvas canvas = SHARED;
		if (canvas.depth == 0) {
			Minecraft minecraft = Minecraft.getInstance();
			canvas.graphics = graphics;
			canvas.font = minecraft.font;
			canvas.guiScale = Math.max(1, minecraft.getWindow().getGuiScale());
			graphics.pose().pushMatrix();
			graphics.pose().scale(1.0F / canvas.guiScale, 1.0F / canvas.guiScale);
			canvas.pixelPose.set(graphics.pose());
		}
		canvas.enter(unitScale);
		return canvas;
	}

	/**
	 * {@link #begin}'s bookkeeping: the outermost call starts at full opacity with no clips or offsets;
	 * every call stacks the unit scale it replaces.
	 */
	void enter(int unitScale) {
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
		scale = unitScale > 0 ? unitScale : guiScale();
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
			Minecraft minecraft = Minecraft.getInstance();
			canvas.font = minecraft.font;
			canvas.guiScale = Math.max(1, minecraft.getWindow().getGuiScale());
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
		Canvas canvas = SHARED;
		if (canvas.depth == 0) {
			measuring();
			canvas.scale = Math.max(1, unitScale);
		} else if (canvas.scale != unitScale && !warnedScale) {
			warnedScale = true;
			FlintClient.LOGGER.warn("Flint measured text at scale {} inside a frame drawn at {}", unitScale, canvas.scale, new IllegalStateException());
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
			graphics.pose().popMatrix();
			graphics = null;
			opacity = 1.0F;
		}
	}

	/** Pixels per unit. */
	public int scale() {
		return scale;
	}

	/** Pixels per GUI unit (the window's GUI scale, or a nested {@link #beginAtGuiScale} frame's), whatever the unit scale. */
	public int guiScale() {
		return guiOverride > 0 ? guiOverride : guiScale;
	}

	/**
	 * A nested frame inside an open one in which a GUI unit is {@code pixelsPerGuiUnit} pixels: {@link #guiScale()}
	 * and the unit scale both report it, so something drawn in GUI units (the HUD) comes out at that size, as crisp
	 * as at its own. Clips keep landing on real pixels. Pair with {@link #end()}.
	 */
	public static Canvas beginAtGuiScale(GuiGraphicsExtractor graphics, int pixelsPerGuiUnit) {
		Canvas canvas = begin(graphics, Math.max(1, pixelsPerGuiUnit));
		canvas.guiOverride = Math.max(1, pixelsPerGuiUnit);
		return canvas;
	}

	/** Units to whole pixels. */
	public int px(float units) {
		return Math.round(units * scale);
	}

	public GuiGraphicsExtractor graphics() {
		return graphics;
	}

	Font font() {
		return font;
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
		graphics.pose().pushMatrix();
		graphics.pose().translate(dxPx, dyPx);
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
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(cx, cy);
		pose.scale(scale, scale);
		pose.translate(-cx, -cy);
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
			graphics.pose().popMatrix();
		}
	}

	// ---------- clipping ----------

	/**
	 * Clips everything drawn until {@link #popClip()} to a pixel rectangle (nested clips intersect), moved
	 * by any open {@link #pushOffset}. Minecraft keeps scissor rectangles in whole GUI units
	 * ({@code GuiGraphicsExtractor.enableScissor} maps the corners through the pose and floors them;
	 * {@code GuiRenderer} multiplies by the GUI scale), so a rectangle is rounded inwards to GUI units:
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
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		scissorPose(pose, pixelPose, guiScale);
		graphics.enableScissor(gui[0], gui[1], gui[2], gui[3]);
		pose.popMatrix();
		clips++;
	}

	/**
	 * The pose a scissor rectangle in GUI units is given under: the outermost begin's pixel pose with its
	 * 1/guiScale undone ({@code (1/g)·g} is exactly 1 in float for every GUI scale), so only the transform
	 * enclosing the canvas remains.
	 */
	static void scissorPose(Matrix3x2f out, Matrix3x2fc pixelPose, int guiScale) {
		out.set(pixelPose);
		out.scale(guiScale, guiScale);
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
			graphics.disableScissor();
		}
	}

	// ---------- shapes ----------

	public void fill(int x0, int y0, int x1, int y1, int argb) {
		fillRaw(x0, y0, x1, y1, tint(argb));
	}

	private void fillRaw(int x0, int y0, int x1, int y1, int argb) {
		if (x1 > x0 && y1 > y0 && (argb >>> 24) != 0) {
			graphics.fill(x0, y0, x1, y1, argb);
		}
	}

	public void gradient(int x0, int y0, int x1, int y1, int top, int bottom) {
		graphics.fillGradient(x0, y0, x1, y1, tint(top), tint(bottom));
	}

	/** A filled rectangle with antialiased rounded corners of radius {@code r} px. */
	public void roundRect(int x, int y, int w, int h, int r, int argb) {
		argb = tint(argb);
		if (w <= 0 || h <= 0 || (argb >>> 24) == 0) {
			return;
		}
		r = Math.min(r, Math.min(w, h) / 2);
		if (r <= 0) {
			graphics.fill(x, y, x + w, y + h, argb);
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
			graphics.fill(x, y, x + w, y + h, argb);
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
			graphics.fill(x, y, x + w, y + h, argb);
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

	/**
	 * Draws a picture filling the pixel box without stretching it: scaled to cover the box, centred, the overflowing side
	 * cropped evenly. Tinted by the canvas opacity (so it fades with its screen); corners of {@code radius} px are
	 * covered in {@code surroundArgb} (the colour around the box).
	 */
	public void picture(Picture.Level picture, int x, int y, int w, int h, int radius, int surroundArgb) {
		if (w <= 0 || h <= 0) {
			return;
		}
		float scale = Math.max(w / (float) picture.width(), h / (float) picture.height());
		float visibleW = Math.min(picture.width(), w / scale);
		float visibleH = Math.min(picture.height(), h / scale);
		float u = (picture.width() - visibleW) / 2;
		float v = (picture.height() - visibleH) / 2;
		graphics.blit(RenderPipelines.GUI_TEXTURED, picture.id(), x, y, u, v, w, h, Math.round(visibleW), Math.round(visibleH), picture.width(),
			picture.height(), tint(0xFFFFFFFF));
		roundCorners(x, y, w, h, radius, tint(surroundArgb));
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

	public void icon(Icon icon, int x, int y, int sizePx, int argb) {
		argb = tint(argb);
		if ((argb >>> 24) != 0) {
			blit(MaskAtlas.get().icon(icon, sizePx), x, y, sizePx, sizePx, false, false, argb);
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
		graphics.blit(RenderPipelines.GUI_TEXTURED, e.texture(), x, y, u, v, w, h, sw, sh, e.textureWidth(), e.textureHeight(), argb);
	}

	// ---------- text ----------

	/** Measures (and caches) a line at the current scale. */
	public int width(TextLine line) {
		line.prepare(scale, font);
		return line.widthPx();
	}

	/** The width a line takes when drawn at most {@code maxPx} wide (shortened with an ellipsis if needed). */
	public int width(TextLine line, int maxPx) {
		return line.fitted(scale, font, maxPx).widthPx();
	}

	/** Draws a line with its baseline at {@code baselinePx}. */
	public void text(TextLine line, int x, int baselinePx, int argb) {
		argb = tint(argb);
		if ((argb >>> 24) == 0) {
			return;
		}
		line.prepare(scale, font);
		Matrix3x2fStack pose = graphics.pose();
		float top = (float) baselinePx / scale - FlintFonts.BASELINE;
		pose.pushMatrix();
		pose.scale(scale, scale);
		if (line.whole() != null) {
			pose.translate((float) x / scale, top);
			graphics.text(font, line.whole(), 0, 0, argb, false);
		} else {
			int[] offsets = line.offsets();
			var glyphs = line.glyphs();
			for (int i = 0; i < glyphs.length; i++) {
				pose.pushMatrix();
				pose.translate((float) (x + offsets[i]) / scale, top);
				graphics.text(font, glyphs[i], 0, 0, argb, false);
				pose.popMatrix();
			}
		}
		pose.popMatrix();
	}

	/** Draws a line at most {@code maxPx} wide: cut short with an ellipsis when it is wider. */
	public void text(TextLine line, int x, int baselinePx, int argb, int maxPx) {
		text(line.fitted(scale, font, maxPx), x, baselinePx, argb);
	}

	/** Draws a line ending at {@code rightPx}. */
	public void textRight(TextLine line, int rightPx, int baselinePx, int argb) {
		text(line, rightPx - width(line), baselinePx, argb);
	}

	/** Draws a line at most {@code maxPx} wide, ending at {@code rightPx}. */
	public void textRight(TextLine line, int rightPx, int baselinePx, int argb, int maxPx) {
		TextLine shown = line.fitted(scale, font, maxPx);
		text(shown, rightPx - shown.widthPx(), baselinePx, argb);
	}

	/** Draws a line centred on {@code centerPx}. */
	public void textCentered(TextLine line, int centerPx, int baselinePx, int argb) {
		text(line, centerPx - width(line) / 2, baselinePx, argb);
	}

	/** Draws a line at most {@code maxPx} wide, centred on {@code centerPx}. */
	public void textCentered(TextLine line, int centerPx, int baselinePx, int argb, int maxPx) {
		TextLine shown = line.fitted(scale, font, maxPx);
		text(shown, centerPx - shown.widthPx() / 2, baselinePx, argb);
	}

	/** Baseline that vertically centres a line's capitals on {@code centerPx}. */
	public int baselineFor(TextLine line, int centerPx) {
		line.prepare(scale, font);
		return Math.round(centerPx + line.capHeightPx(scale) / 2.0F);
	}

	/** Baseline that vertically centres capitals of {@code face} on {@code centerPx} (for {@link DigitRun}). */
	public int baselineFor(FontFace face, int centerPx) {
		return Math.round(centerPx + face.capHeightPx(scale) / 2.0F);
	}
}
