package com.flintclient.client.platform;

import com.flintclient.client.render.MaskAtlas;
import com.flintclient.legacy.mixin.TextRendererAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.render.vertex.BufferBuilder;
import net.minecraft.client.render.vertex.DefaultVertexFormat;
import net.minecraft.client.render.vertex.Tesselator;
import net.minecraft.resource.Identifier;
import org.lwjgl.opengl.GL11;

/**
 * Where a Flint frame draws, on Minecraft 1.8.9: the drawing backend under {@code render.Canvas}, on OpenGL 1.1 immediate
 * mode. Everything is one batch of textured quads (position, texture, colour) per texture: plain and gradient fills sample a
 * fully covered pixel of the mask atlas, so fills and the atlas's shapes (rounded corners, rings, icons) share one batch, and
 * Minecraft's font is drawn from its own glyph sheet as quads too (a whole line per batch, no shadow). A batch is drawn when
 * the texture or the clip changes, and at the end. Transforms (slides, zooms) are applied to the vertices on the CPU, so they
 * never break a batch. Coordinates are window pixels from the top left, as Canvas's pixel space is on every line.
 * Platform class (see {@link Clock}). Render thread.
 */
public final class Frame {
	private static final Frame SHARED = new Frame();
	private static final float SOLID_RADIUS = 8;

	private final float[] saved = new float[6 * 32];
	private int depth;
	/** The transform: {@code x' = m00 × x + m01 × y + moveX}, {@code y' = m10 × x + m11 × y + moveY}. */
	private float m00 = 1;
	private float m01;
	private float m10;
	private float m11 = 1;
	private float moveX;
	private float moveY;
	/** The transform as an OpenGL matrix, for the game's own drawing ({@link #pushGame}). */
	private final java.nio.FloatBuffer glMatrix = org.lwjgl.BufferUtils.createFloatBuffer(16);
	private final int[] clips = new int[4 * 16];
	private int clipDepth;
	/**
	 * Pending batches, drawn in this order: one per texture, each with its own buffer. A quad joins its texture's batch unless it
	 * overlaps a quad of a batch begun after that one (it must be drawn over it): then everything pending is drawn first. So text
	 * (Minecraft's glyph sheet) and shapes (the mask atlas) drawn in turn, as a HUD module or a menu row draws them, cost one draw
	 * each instead of one per switch.
	 */
	private static final int SLOTS = 4;
	private final Tesselator[] slotBuffers = new Tesselator[SLOTS];
	private final int[] slotTextures = new int[SLOTS];
	private int slots;
	/** Each pending batch's runs: bounding boxes (window pixels) of quads queued one after another, with their batch. */
	private static final int RUNS = 512;
	private final float[] runs = new float[4 * RUNS];
	private final int[] runSlots = new int[RUNS];
	private int runCount;
	/** The transformed bounding box of the quad being queued. */
	private float bx0;
	private float by0;
	private float bx1;
	private float by1;
	private MaskAtlas.Entry solid;
	private float solidU;
	private float solidV;
	private int solidTexture;
	private int windowHeight;
	private TextEngine text;
	/** Quads, draw calls and frames (for the menu's performance check). */
	private static long batches;

	private Frame() {
	}

	/** The frame Flint's screens draw into (one shared object). */
	public static Frame get() {
		return SHARED;
	}

	/** What this frame draws into, for telling frames apart ({@code ui.FlintFrame}). */
	public Object target() {
		return this;
	}

	/** Draw calls made so far (batches drawn), for measuring. */
	public static long batches() {
		return batches;
	}

	// ---------- the window ----------

	private static int scaleFor;
	private static int scaleWidth = -1;
	private static int scaleHeight = -1;
	private static int scaleOption = -1;
	private static boolean scaleUnicode;

	/** Pixels per GUI unit of the game window now (1.8.9's GUI scale, as its {@code Window} works it out). */
	public static int windowGuiScale() {
		Minecraft mc = Minecraft.getInstance();
		boolean unicode = mc.isUnicode();
		if (mc.width != scaleWidth || mc.height != scaleHeight || mc.options.guiScale != scaleOption || unicode != scaleUnicode) {
			scaleWidth = mc.width;
			scaleHeight = mc.height;
			scaleOption = mc.options.guiScale;
			scaleUnicode = unicode;
			scaleFor = Math.max(1, new net.minecraft.client.render.Window(mc).getScale());
		}
		return scaleFor;
	}

	/** The text engine for measuring outside a frame (layout). */
	public static TextEngine measuringText() {
		return TextEngine.of(Minecraft.getInstance().textRenderer);
	}

	// ---------- the frame ----------

	/** Sets up drawing in window pixels over whatever is on screen; pair with {@link #endPixels}. Returns the text engine. */
	public TextEngine beginPixels(int guiScale) {
		Minecraft mc = Minecraft.getInstance();
		windowHeight = mc.height;
		GlStateManager.matrixMode(GL11.GL_PROJECTION);
		GlStateManager.pushMatrix();
		GlStateManager.loadIdentity();
		GlStateManager.ortho(0, mc.width, mc.height, 0, 1000, 3000);
		GlStateManager.matrixMode(GL11.GL_MODELVIEW);
		GlStateManager.pushMatrix();
		GlStateManager.loadIdentity();
		GlStateManager.translatef(0, 0, -2000);
		GlStateManager.disableLighting();
		GlStateManager.disableDepthTest();
		GlStateManager.disableAlphaTest();
		GlStateManager.disableCull();
		GlStateManager.enableBlend();
		GlStateManager.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GlStateManager.shadeModel(GL11.GL_SMOOTH);
		GlStateManager.enableTexture();
		GlStateManager.color4f(1, 1, 1, 1);
		depth = 0;
		m00 = 1;
		m01 = 0;
		m10 = 0;
		m11 = 1;
		moveX = 0;
		moveY = 0;
		clipDepth = 0;
		slots = 0;
		runCount = 0;
		solid = null;
		text = TextEngine.of(mc.textRenderer);
		return text;
	}

	/** Draws what is queued and gives Minecraft its projection and state back. */
	public void endPixels() {
		flush();
		if (clipDepth > 0) {
			clipDepth = 0;
			GL11.glDisable(GL11.GL_SCISSOR_TEST);
		}
		GlStateManager.shadeModel(GL11.GL_FLAT);
		GlStateManager.enableAlphaTest();
		GlStateManager.enableDepthTest();
		GlStateManager.color4f(1, 1, 1, 1);
		GlStateManager.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
		GlStateManager.matrixMode(GL11.GL_PROJECTION);
		GlStateManager.popMatrix();
		GlStateManager.matrixMode(GL11.GL_MODELVIEW);
		GlStateManager.popMatrix();
	}

	// ---------- transforms ----------

	private void save() {
		if (depth < saved.length / 6) {
			int at = 6 * depth;
			saved[at] = m00;
			saved[at + 1] = m01;
			saved[at + 2] = m10;
			saved[at + 3] = m11;
			saved[at + 4] = moveX;
			saved[at + 5] = moveY;
		}
		depth++;
	}

	private void translate(float dx, float dy) {
		moveX += m00 * dx + m01 * dy;
		moveY += m10 * dx + m11 * dy;
	}

	private void scale(float k) {
		m00 *= k;
		m01 *= k;
		m10 *= k;
		m11 *= k;
	}

	/** Moves what follows by ({@code dx}, {@code dy}) pixels until {@link #pop}. */
	public void pushTranslate(float dx, float dy) {
		save();
		translate(dx, dy);
	}

	/** Scales what follows by {@code k} about the pixel point ({@code cx}, {@code cy}) until {@link #pop}. */
	public void pushZoom(float k, float cx, float cy) {
		save();
		translate(cx, cy);
		scale(k);
		translate(-cx, -cy);
	}

	/** Moves what follows to ({@code x}, {@code y}) and scales it by {@code k} from there, until {@link #pop}. */
	public void pushPlace(float x, float y, float k) {
		save();
		translate(x, y);
		scale(k);
	}

	/** Turns what follows by {@code quarterTurns} quarter turns clockwise about ({@code cx}, {@code cy}), which becomes the origin, until {@link #pop}. */
	public void pushTurn(float cx, float cy, int quarterTurns) {
		save();
		translate(cx, cy);
		for (int i = 0; i < (quarterTurns & 3); i++) {
			// A quarter turn clockwise on a screen whose y grows downwards: (x, y) becomes (-y, x).
			float a = m00;
			float c = m10;
			m00 = m01;
			m10 = m11;
			m01 = -a;
			m11 = -c;
		}
	}

	public void pop() {
		if (depth > 0) {
			depth--;
			if (depth < saved.length / 6) {
				int at = 6 * depth;
				m00 = saved[at];
				m01 = saved[at + 1];
				m10 = saved[at + 2];
				m11 = saved[at + 3];
				moveX = saved[at + 4];
				moveY = saved[at + 5];
			}
		}
	}

	/** The screen's size in GUI units. */
	public int guiWidth() {
		return (int) Math.ceil(Minecraft.getInstance().width / (double) windowGuiScale());
	}

	public int guiHeight() {
		return (int) Math.ceil(Minecraft.getInstance().height / (double) windowGuiScale());
	}

	/**
	 * For the game's own drawing (items, Minecraft's font with its shadow): draws what is queued, then sets OpenGL's model view to
	 * this frame's transform, so the game draws in the frame's coordinates. Pair with {@link #popGame}.
	 */
	public void pushGame() {
		flush();
		glMatrix.clear();
		glMatrix.put(m00).put(m10).put(0).put(0);
		glMatrix.put(m01).put(m11).put(0).put(0);
		glMatrix.put(0).put(0).put(1).put(0);
		glMatrix.put(moveX).put(moveY).put(0).put(1);
		glMatrix.flip();
		GlStateManager.pushMatrix();
		GL11.glMultMatrix(glMatrix);
	}

	/** Back to the frame's own drawing after {@link #pushGame}, with its state as the batch expects it. */
	public void popGame() {
		GlStateManager.popMatrix();
		GlStateManager.disableLighting();
		GlStateManager.disableDepthTest();
		GlStateManager.disableAlphaTest();
		GlStateManager.enableBlend();
		GlStateManager.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GlStateManager.shadeModel(GL11.GL_SMOOTH);
		GlStateManager.enableTexture();
		GlStateManager.color4f(1, 1, 1, 1);
	}

	// ---------- clipping ----------

	/** Clips what follows to a rectangle in whole GUI units of the pixel space, until {@link #endScissor} (nested clips intersect). */
	public void scissor(int x0, int y0, int x1, int y1, int guiScale) {
		flush();
		int px0 = x0 * guiScale;
		int py0 = y0 * guiScale;
		int px1 = x1 * guiScale;
		int py1 = y1 * guiScale;
		if (clipDepth > 0) {
			int p = (clipDepth - 1) * 4;
			px0 = Math.max(px0, clips[p]);
			py0 = Math.max(py0, clips[p + 1]);
			px1 = Math.min(px1, clips[p + 2]);
			py1 = Math.min(py1, clips[p + 3]);
		}
		if (clipDepth < clips.length / 4) {
			int p = clipDepth * 4;
			clips[p] = px0;
			clips[p + 1] = py0;
			clips[p + 2] = Math.max(px0, px1);
			clips[p + 3] = Math.max(py0, py1);
		}
		clipDepth++;
		applyClip();
	}

	public void endScissor() {
		flush();
		clipDepth = Math.max(0, clipDepth - 1);
		applyClip();
	}

	private void applyClip() {
		if (clipDepth == 0) {
			GL11.glDisable(GL11.GL_SCISSOR_TEST);
			return;
		}
		int p = (Math.min(clipDepth, clips.length / 4) - 1) * 4;
		GL11.glEnable(GL11.GL_SCISSOR_TEST);
		GL11.glScissor(clips[p], windowHeight - clips[p + 3], clips[p + 2] - clips[p], clips[p + 3] - clips[p + 1]);
	}

	// ---------- the batch ----------

	/** The pending buffer for {@code glTexture}, for a quad over ({@code x0}, {@code y0})..({@code x1}, {@code y1}) before the transform. */
	private BufferBuilder use(int glTexture, float x0, float y0, float x1, float y1) {
		bounds(x0, y0, x1, y1);
		int slot = -1;
		for (int i = 0; i < slots; i++) {
			if (slotTextures[i] == glTexture) {
				slot = i;
				break;
			}
		}
		if (slot >= 0 && slot < slots - 1 && overlapsAfter(slot)) {
			flush();
			slot = -1;
		}
		if (slot < 0) {
			if (slots == SLOTS) {
				flush();
			}
			slot = slots++;
			slotTextures[slot] = glTexture;
			if (slotBuffers[slot] == null) {
				slotBuffers[slot] = new Tesselator(0x40000);
			}
			slotBuffers[slot].getBuffer().begin(GL11.GL_QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
		}
		addRun(slot);
		return slotBuffers[slot].getBuffer();
	}

	/** The box ({@code x0}, {@code y0})..({@code x1}, {@code y1}) through the transform, into {@link #bx0}..{@link #by1}. */
	private void bounds(float x0, float y0, float x1, float y1) {
		float ax = m00 * x0 + moveX;
		float bx = m00 * x1 + moveX;
		float ay = m10 * x0 + moveY;
		float by = m10 * x1 + moveY;
		float cx = m01 * y0;
		float dx = m01 * y1;
		float cy = m11 * y0;
		float dy = m11 * y1;
		bx0 = Math.min(Math.min(ax + cx, ax + dx), Math.min(bx + cx, bx + dx));
		bx1 = Math.max(Math.max(ax + cx, ax + dx), Math.max(bx + cx, bx + dx));
		by0 = Math.min(Math.min(ay + cy, ay + dy), Math.min(by + cy, by + dy));
		by1 = Math.max(Math.max(ay + cy, ay + dy), Math.max(by + cy, by + dy));
	}

	/** Whether the box being queued overlaps a run of a batch drawn after batch {@code slot}. */
	private boolean overlapsAfter(int slot) {
		for (int r = 0; r < runCount; r++) {
			if (runSlots[r] > slot) {
				int at = 4 * r;
				if (bx0 < runs[at + 2] && bx1 > runs[at] && by0 < runs[at + 3] && by1 > runs[at + 1]) {
					return true;
				}
			}
		}
		return false;
	}

	/** Notes the box being queued: it grows the last run when that run is the same batch's and close by, else starts a run. */
	private void addRun(int slot) {
		if (runCount > 0 && runSlots[runCount - 1] == slot) {
			int at = 4 * (runCount - 1);
			if (bx0 < runs[at + 2] + 16 && bx1 > runs[at] - 16 && by0 < runs[at + 3] + 16 && by1 > runs[at + 1] - 16) {
				runs[at] = Math.min(runs[at], bx0);
				runs[at + 1] = Math.min(runs[at + 1], by0);
				runs[at + 2] = Math.max(runs[at + 2], bx1);
				runs[at + 3] = Math.max(runs[at + 3], by1);
				return;
			}
		}
		if (runCount == RUNS) {
			// Too many runs to check: keep one box over them all (correct, only more cautious).
			int at = 4 * (RUNS - 1);
			runs[at] = Math.min(runs[at], bx0);
			runs[at + 1] = Math.min(runs[at + 1], by0);
			runs[at + 2] = Math.max(runs[at + 2], bx1);
			runs[at + 3] = Math.max(runs[at + 3], by1);
			runSlots[RUNS - 1] = Math.max(runSlots[RUNS - 1], slot);
			return;
		}
		int at = 4 * runCount;
		runs[at] = bx0;
		runs[at + 1] = by0;
		runs[at + 2] = bx1;
		runs[at + 3] = by1;
		runSlots[runCount++] = slot;
	}

	/** Draws every pending batch now, in order. */
	private void flush() {
		if (slots == 0) {
			return;
		}
		// Shapes new this frame reach the atlas texture before anything draws from it (on 26.x the GPU draws after the frame).
		MaskAtlas.get().uploadPending();
		GlStateManager.enableTexture();
		GlStateManager.color4f(1, 1, 1, 1);
		for (int i = 0; i < slots; i++) {
			GlStateManager.bindTexture(slotTextures[i]);
			slotBuffers[i].end();
			batches++;
		}
		slots = 0;
		runCount = 0;
	}

	private void vertex(BufferBuilder b, float x, float y, float u, float v, int argb) {
		b.vertex(m00 * x + m01 * y + moveX, m10 * x + m11 * y + moveY, 0).texture(u, v).color((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, argb >>> 24)
			.nextVertex();
	}

	private void quad(BufferBuilder b, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, int top, int bottom) {
		vertex(b, x0, y0, u0, v0, top);
		vertex(b, x0, y1, u0, v1, bottom);
		vertex(b, x1, y1, u1, v1, bottom);
		vertex(b, x1, y0, u1, v0, top);
	}

	/** A fully covered pixel of the mask atlas's current texture (the inside corner of a rounded corner's mask). */
	private void solid() {
		MaskAtlas.Entry e = MaskAtlas.get().corner((int) SOLID_RADIUS);
		if (e != solid) {
			solid = e;
			solidTexture = e.texture().glId;
			solidU = (e.u() + SOLID_RADIUS - 0.5F) / e.textureWidth();
			solidV = (e.v() + SOLID_RADIUS - 0.5F) / e.textureHeight();
		}
	}

	// ---------- drawing ----------

	public void fill(int x0, int y0, int x1, int y1, int argb) {
		gradient(x0, y0, x1, y1, argb, argb);
	}

	/** A rectangle running from {@code top} to {@code bottom}. */
	public void gradient(int x0, int y0, int x1, int y1, int top, int bottom) {
		if (x1 <= x0 || y1 <= y0) {
			return;
		}
		solid();
		quad(use(solidTexture, x0, y0, x1, y1), x0, y0, x1, y1, solidU, solidV, solidU, solidV, top, bottom);
	}

	/**
	 * The {@code sw × sh} region at ({@code u}, {@code v}) of a {@code texW × texH} texture drawn into the pixel box, tinted by
	 * {@code argb}; a negative {@code sw} or {@code sh} mirrors it.
	 */
	public void blit(Texture texture, int x, int y, float u, float v, int w, int h, int sw, int sh, int texW, int texH, int argb) {
		quad(use(texture.glId, x, y, x + w, y + h), x, y, x + w, y + h, u / texW, v / texH, (u + sw) / texW, (v + sh) / texH, argb, argb);
	}

	/** A region of one of the game's own textures ({@code id}, UVs 0..1), for engine code here (players' faces). */
	public void blitGame(Identifier id, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, int argb) {
		Minecraft mc = Minecraft.getInstance();
		net.minecraft.client.render.texture.Texture t = mc.getTextureManager().get(id);
		if (t == null) {
			flush();
			mc.getTextureManager().bind(id);
			t = mc.getTextureManager().get(id);
			if (t == null) {
				return;
			}
		}
		quad(use(t.getGlId(), x0, y0, x1, y1), x0, y0, x1, y1, u0, v0, u1, v1, argb, argb);
	}

	/** A run of text with its top-left at ({@code x}, {@code y}) font units, {@code k} pixels per font unit, without a shadow. */
	public void text(TextRun run, float x, float y, int k, int argb) {
		String s = run.text;
		if (s.isEmpty() || (argb >>> 24) == 0) {
			return;
		}
		TextRenderer font = text.font;
		if (!run.simple || font.getUnicode()) {
			drawWithMinecraft(font, s, x, y, k, argb);
			return;
		}
		TextRendererAccessor a = (TextRendererAccessor) font;
		Minecraft mc = Minecraft.getInstance();
		net.minecraft.client.render.texture.Texture sheet = mc.getTextureManager().get(a.flint$texture());
		if (sheet == null) {
			drawWithMinecraft(font, s, x, y, k, argb);
			return;
		}
		int[] widths = a.flint$widths();
		float end = x;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			end += c == ' ' ? 4 : widths[TextEngine.INDEX[c]];
		}
		BufferBuilder b = use(sheet.getGlId(), x * k, y * k, end * k, (y + 8) * k);
		float at = x;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == ' ') {
				at += 4;
				continue;
			}
			int index = TextEngine.INDEX[c];
			int w = widths[index];
			float f = w - 1.01F;
			float u = index % 16 * 8;
			float v = index / 16 * 8;
			quad(b, at * k, y * k, (at + f) * k, (y + 7.99F) * k, u / 128F, v / 128F, (u + f) / 128F, (v + 7.99F) / 128F, argb, argb);
			at += w;
		}
	}

	/**
	 * {@code s} in Minecraft's font as Minecraft draws it, top left at ({@code x}, {@code y}) under the transform (GUI units in a
	 * HUD module), with Minecraft's shadow (the colour at a quarter, one unit down and right) when {@code shadow}: as batched
	 * quads, or Minecraft's own renderer for what they can't draw (formatting codes, a character off the ASCII page, the
	 * Unicode font).
	 */
	public void gameText(String s, float x, float y, int argb, boolean shadow) {
		if (s.isEmpty() || (argb >>> 24) == 0) {
			return;
		}
		TextRenderer font = Minecraft.getInstance().textRenderer;
		boolean simple = !font.getUnicode();
		for (int i = 0; i < s.length() && simple; i++) {
			char c = s.charAt(i);
			simple = c == ' ' || c != '§' && TextEngine.INDEX[c] >= 0;
		}
		TextRendererAccessor a = (TextRendererAccessor) font;
		net.minecraft.client.render.texture.Texture sheet = simple ? Minecraft.getInstance().getTextureManager().get(a.flint$texture()) : null;
		if (sheet == null) {
			pushGame();
			GlStateManager.enableBlend();
			font.draw(s, x, y, argb, shadow);
			popGame();
			return;
		}
		if (shadow) {
			glyphs(a, sheet, s, x + 1, y + 1, (argb & 0xFCFCFC) >> 2 | argb & 0xFF000000);
		}
		glyphs(a, sheet, s, x, y, argb);
	}

	/** A simple run of Minecraft's glyphs, one unit per font unit, as quads in the batch. */
	private void glyphs(TextRendererAccessor a, net.minecraft.client.render.texture.Texture sheet, String s, float x, float y, int argb) {
		int[] widths = a.flint$widths();
		float end = x;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			end += c == ' ' ? 4 : widths[TextEngine.INDEX[c]];
		}
		BufferBuilder b = use(sheet.getGlId(), x, y, end, y + 8);
		float at = x;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == ' ') {
				at += 4;
				continue;
			}
			int index = TextEngine.INDEX[c];
			int w = widths[index];
			float f = w - 1.01F;
			float u = index % 16 * 8;
			float v = index / 16 * 8;
			quad(b, at, y, at + f, y + 7.99F, u / 128F, v / 128F, (u + f) / 128F, (v + 7.99F) / 128F, argb, argb);
			at += w;
		}
	}

	/** Text the batched quads can't draw (a character off the ASCII page, the Unicode font): Minecraft's own renderer. */
	private void drawWithMinecraft(TextRenderer font, String s, float x, float y, int k, int argb) {
		flush();
		pushGame();
		GlStateManager.translatef(x * k, y * k, 0);
		GlStateManager.scalef(k, k, 1);
		font.draw(s, 0, 0, argb, false);
		popGame();
	}

	/** What follows is drawn over everything drawn so far (already so here: the batch keeps the order it was drawn in). */
	public void nextLayer() {
	}

	/** 1.8.9 has no menu blur: false (nothing blurred). */
	public boolean blurBehind() {
		return false;
	}
}
