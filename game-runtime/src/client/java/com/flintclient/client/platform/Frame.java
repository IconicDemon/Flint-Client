package com.flintclient.client.platform;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;

/**
 * Where a Flint frame draws: the drawing backend under {@code render.Canvas}. Canvas keeps all of Flint's drawing logic
 * (pixel space, clips, offsets, fades, shapes, text layout); this class only does what the engine must: transforms, plain
 * and gradient rectangles, textured quads, text runs, scissor rectangles and draw layers. On 26.x and 1.21.x it records into
 * Minecraft's {@link GuiGraphicsExtractor} exactly as Canvas did before it existed. Platform class (see {@link Clock}).
 *
 * <p>One frame object is reused for every screen and the HUD ({@link #of}); drawing allocates nothing. Render thread.
 */
public final class Frame {
	private static final Frame SHARED = new Frame();
	/** While a Flint screen is drawn under another screen (see {@link #holdBlur}), its own blur stays off. */
	private static boolean blurHeld;

	private GuiGraphicsExtractor graphics;
	private Font font;
	/** The pose {@link #beginPixels} set up; scissor rectangles are given relative to it. */
	private final Matrix3x2f pixelPose = new Matrix3x2f();

	private Frame() {
	}

	/** The frame that draws into {@code graphics} (one shared object, pointed at it). */
	public static Frame of(GuiGraphicsExtractor graphics) {
		SHARED.graphics = graphics;
		return SHARED;
	}

	/** Minecraft's own drawing object, for code on this engine's side of the platform classes. */
	public GuiGraphicsExtractor graphics() {
		return graphics;
	}

	/** What this frame draws into, for telling frames apart ({@code ui.FlintFrame}): the same object for one frame's drawing. */
	public Object target() {
		return graphics;
	}

	/** Pixels per GUI unit of the game window now (at least 1). */
	public static int windowGuiScale() {
		return Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
	}

	/** The text engine for measuring outside a frame (layout). */
	public static TextEngine measuringText() {
		return TextEngine.of(Minecraft.getInstance().font);
	}

	// ---------- transforms ----------

	/** Switches the pose from GUI units to window pixels ({@code guiScale} pixels per unit); pair with {@link #endPixels}. Returns the text engine. */
	public TextEngine beginPixels(int guiScale) {
		font = Minecraft.getInstance().font;
		graphics.pose().pushMatrix();
		graphics.pose().scale(1.0F / guiScale, 1.0F / guiScale);
		pixelPose.set(graphics.pose());
		return TextEngine.of(font);
	}

	public void endPixels() {
		graphics.pose().popMatrix();
	}

	/** Moves what follows by ({@code dx}, {@code dy}) pixels until {@link #pop}. */
	public void pushTranslate(float dx, float dy) {
		graphics.pose().pushMatrix();
		graphics.pose().translate(dx, dy);
	}

	/** Scales what follows by {@code scale} about the pixel point ({@code cx}, {@code cy}) until {@link #pop}. */
	public void pushZoom(float scale, float cx, float cy) {
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(cx, cy);
		pose.scale(scale, scale);
		pose.translate(-cx, -cy);
	}

	/** Moves what follows to ({@code x}, {@code y}) and scales it by {@code scale} from there, until {@link #pop}. */
	public void pushPlace(float x, float y, float scale) {
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(scale, scale);
	}

	/** Turns what follows by {@code quarterTurns} quarter turns clockwise about ({@code cx}, {@code cy}), which becomes the origin, until {@link #pop}. */
	public void pushTurn(float cx, float cy, int quarterTurns) {
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(cx, cy);
		pose.rotate((float) (Math.PI / 2) * (quarterTurns & 3));
	}

	public void pop() {
		graphics.pose().popMatrix();
	}

	/** The screen's size in GUI units. */
	public int guiWidth() {
		return graphics.guiWidth();
	}

	public int guiHeight() {
		return graphics.guiHeight();
	}

	// ---------- clipping ----------

	/**
	 * Clips what follows to a rectangle in whole GUI units of the pixel space {@link #beginPixels} set up, until
	 * {@link #endScissor} (nested clips intersect). Minecraft maps the corners through the pose and floors them.
	 */
	public void scissor(int x0, int y0, int x1, int y1, int guiScale) {
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		scissorPose(pose, pixelPose, guiScale);
		graphics.enableScissor(x0, y0, x1, y1);
		pose.popMatrix();
	}

	public void endScissor() {
		graphics.disableScissor();
	}

	/**
	 * The pose a scissor rectangle in GUI units is given under: the pixel pose with its 1/guiScale undone ({@code (1/g)·g} is
	 * exactly 1 in float for every GUI scale), so only the transform enclosing the canvas remains.
	 */
	public static void scissorPose(Matrix3x2f out, Matrix3x2f pixelPose, int guiScale) {
		out.set(pixelPose);
		out.scale(guiScale, guiScale);
	}

	// ---------- drawing ----------

	public void fill(int x0, int y0, int x1, int y1, int argb) {
		graphics.fill(x0, y0, x1, y1, argb);
	}

	/** A rectangle running from {@code top} to {@code bottom}. */
	public void gradient(int x0, int y0, int x1, int y1, int top, int bottom) {
		graphics.fillGradient(x0, y0, x1, y1, top, bottom);
	}

	/**
	 * The {@code sw × sh} region at ({@code u}, {@code v}) of a {@code texW × texH} texture drawn into the pixel box, tinted by
	 * {@code argb}; a negative {@code sw} or {@code sh} mirrors it (u or v then names the region's far edge).
	 */
	public void blit(Texture texture, int x, int y, float u, float v, int w, int h, int sw, int sh, int texW, int texH, int argb) {
		graphics.blit(RenderPipelines.GUI_TEXTURED, texture.id(), x, y, u, v, w, h, sw, sh, texW, texH, argb);
	}

	/** A run of text with its top-left at ({@code x}, {@code y}) font units, {@code scale} pixels per font unit, without a shadow. */
	public void text(TextRun run, float x, float y, int scale, int argb) {
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.scale(scale, scale);
		pose.translate(x, y);
		graphics.text(font, run.component(), 0, 0, argb, false);
		pose.popMatrix();
	}

	/** What follows is drawn over everything drawn so far (Minecraft's GUI strata). */
	public void nextLayer() {
		graphics.nextStratum();
	}

	/**
	 * Holds Flint's blur back while a Flint screen is drawn under another one ({@code HostScreen} sets it). Minecraft's friends
	 * list (26.2 and later) draws the screen it was opened over, then blurs it, and Minecraft allows one blur a frame: a
	 * blur of the Flint screen's own first made the list's throw ("Can only blur once per frame") and crashed the game.
	 */
	public static void holdBlur(boolean held) {
		blurHeld = held;
	}

	/**
	 * Blurs everything drawn before this layer (Minecraft's menu blur), when the game draws one; false when something
	 * blurred this frame already, or this screen is drawn under another (whose blur it leaves to that one, {@link #holdBlur}).
	 */
	public boolean blurBehind() {
		if (blurHeld) {
			return false;
		}
		try {
			graphics.blurBeforeThisStratum();
			return true;
		} catch (IllegalStateException alreadyBlurred) {
			return false;
		}
	}

	// ---------- a blur kept to rectangles ----------

	/** At most this many rectangles keep a clipped blur. */
	public static final int BLUR_CLIPS = 8;
	/** The rectangles (window pixels from the top left: x0, y0, x1, y1), and how many; -1 while the blur isn't clipped. */
	private static final int[] CLIPS = new int[4 * BLUR_CLIPS];
	private static int clipCount = -1;

	/**
	 * Keeps the blur this frame's {@link #blurBehind} was granted to the rectangles {@link #clipBlurTo} adds (none yet: nothing
	 * stays blurred), so the rest of the screen stays sharp: the Right Shift menu's frosted panel over a live game. Only after
	 * {@code blurBehind} returned true in the same frame. The engine reads it where it runs the blur (26.3's {@code PanelBlur});
	 * elsewhere the blur covers the screen as before.
	 */
	public static void clipBlur() {
		clipCount = 0;
	}

	/** Adds a rectangle (window pixels from the top left) to this frame's clipped blur ({@link #clipBlur}); empty ones are left out. */
	public static void clipBlurTo(int x0, int y0, int x1, int y1) {
		if (clipCount < 0 || clipCount >= BLUR_CLIPS || x1 <= x0 || y1 <= y0) {
			return;
		}
		int at = 4 * clipCount++;
		CLIPS[at] = x0;
		CLIPS[at + 1] = y0;
		CLIPS[at + 2] = x1;
		CLIPS[at + 3] = y1;
	}

	/** Drops a clip that was asked for (the screen that asked it closed before the blur ran). */
	public static void clipBlurOff() {
		clipCount = -1;
	}

	/**
	 * Takes this frame's clip, once: its rectangles into {@code out} (four ints each, at least {@code 4 × BLUR_CLIPS} long) and how
	 * many; -1 when the blur isn't clipped (it covers the screen).
	 */
	public static int takeBlurClip(int[] out) {
		int n = clipCount;
		clipCount = -1;
		if (n > 0) {
			System.arraycopy(CLIPS, 0, out, 0, 4 * n);
		}
		return n;
	}
}
