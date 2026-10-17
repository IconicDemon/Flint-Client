package com.flintclient.client.control.hud.render;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.DigitRun;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.TextLine;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;

/**
 * Everything a module view draws goes through here, in physical pixels on the current {@link Canvas}: text
 * in the style's faces (with Classic's pixel scale and the Classic and Minimal shadows applied), tabular
 * digits, the style's panel, plain shapes, icons, items and effect sprites. Measuring uses the same faces, so
 * a module measured and then drawn in one frame lines up exactly.
 *
 * <p>A pen can also {@linkplain #startRecording() record} instead of drawing: every operation then appends its
 * rectangle, kind and colour, which the layout miniature redraws scaled down as simple shapes (text becomes a
 * bar of its real width). One pen per surface is reused for the session; nothing here allocates once the
 * record arrays have grown to a frame's size. Render thread.
 */
final class HudPen {
	static final int TEXT = 0;
	static final int FILL = 1;
	static final int PANEL = 2;
	static final int ITEM = 3;
	static final int SPRITE = 4;
	static final int ICON = 5;
	static final int RING = 6;

	private final int surface;
	private Canvas c;
	HudStyle s = new HudStyle();
	private boolean recording;
	/** x, y, w, h, kind, colour, radius per record. */
	private int[] records = new int[7 * 64];
	private int recordCount;

	HudPen(int surface) {
		this.surface = surface;
	}

	/** Draws (or records) on {@code canvas} in {@code style} from now on. */
	HudPen on(Canvas canvas, HudStyle style) {
		this.c = canvas;
		this.s = style;
		return this;
	}

	Canvas canvas() {
		return c;
	}

	GuiGraphicsExtractor graphics() {
		return c.graphics();
	}

	// ---------- recording ----------

	void startRecording() {
		recording = true;
		recordCount = 0;
	}

	void stopRecording() {
		recording = false;
	}

	boolean recording() {
		return recording;
	}

	int recordCount() {
		return recordCount;
	}

	/** Field {@code field} (0 x, 1 y, 2 w, 3 h, 4 kind, 5 colour, 6 radius) of record {@code i}. */
	int record(int i, int field) {
		return records[i * 7 + field];
	}

	private void record(int kind, int x, int y, int w, int h, int argb, int radius) {
		if (recordCount * 7 + 7 > records.length) {
			records = Arrays.copyOf(records, records.length * 2);
		}
		int at = recordCount * 7;
		records[at] = x;
		records[at + 1] = y;
		records[at + 2] = w;
		records[at + 3] = h;
		records[at + 4] = kind;
		records[at + 5] = c.tint(argb);
		records[at + 6] = radius;
		recordCount++;
	}

	// ---------- text metrics ----------

	private int face(int role) {
		return role == HudFaces.LABEL ? s.labelFace : s.valueFace;
	}

	/** Cap height in pixels of {@code role}'s face. */
	int cap(int role) {
		return Math.round(HudFaces.face(face(role)).capHeightPx(s.textScale));
	}

	/** The baseline of a line of text whose box starts at {@code top} ({@link HudStyle#lineH} tall). */
	int baseline(int top) {
		if (s.classic()) {
			// Minecraft's font: 7 pixels of capitals from the top of the line, then the descender and shadow.
			return top + 7 * s.textScale;
		}
		return top + Math.round((s.lineH + cap(HudFaces.VALUE)) / 2.0F);
	}

	/** Width in pixels of {@code text} in {@code role}'s face. */
	int width(HudText text, int role) {
		if (text.blank(s.theme)) {
			return 0;
		}
		TextLine line = text.line(surface, face(role));
		boolean nested = enter();
		try {
			return c.width(line);
		} finally {
			exit(nested);
		}
	}

	/** Width in pixels of the first {@code length} characters of {@code digits} in {@code role}'s face. */
	int width(char[] digits, int length, int role) {
		DigitRun run = HudFaces.digits(face(role));
		boolean nested = enter();
		try {
			return run.width(c, digits, length);
		} finally {
			exit(nested);
		}
	}

	/** The tabular digit cell of {@code role}'s face, in pixels. */
	int cell(int role) {
		DigitRun run = HudFaces.digits(face(role));
		boolean nested = enter();
		try {
			return run.cellPx(c);
		} finally {
			exit(nested);
		}
	}

	// ---------- text ----------

	/** Draws {@code text} from {@code x} on {@code baseline}; returns where it ends. */
	int text(HudText text, int role, int x, int baseline, int argb) {
		return text(text, role, x, baseline, argb, Integer.MAX_VALUE);
	}

	/** {@link #text(HudText, int, int, int, int)} cut short with an ellipsis beyond {@code maxPx}. */
	int text(HudText text, int role, int x, int baseline, int argb, int maxPx) {
		if (text.blank(s.theme)) {
			return x;
		}
		TextLine line = text.line(surface, face(role));
		boolean nested = enter();
		try {
			int w = maxPx == Integer.MAX_VALUE ? c.width(line) : c.width(line, maxPx);
			if (recording) {
				int cap = cap(role);
				record(TEXT, x, baseline - cap, w, cap, argb, 0);
			} else {
				int shadow = s.shadowFor(argb);
				if (shadow != 0) {
					drawLine(line, x + s.shadowPx, baseline + s.shadowPx, shadow, maxPx);
				}
				drawLine(line, x, baseline, argb, maxPx);
			}
			return x + w;
		} finally {
			exit(nested);
		}
	}

	private void drawLine(TextLine line, int x, int baseline, int argb, int maxPx) {
		if (maxPx == Integer.MAX_VALUE) {
			c.text(line, x, baseline, argb);
		} else {
			c.text(line, x, baseline, argb, maxPx);
		}
	}

	/** Draws {@code text} centred on {@code centerX}, at most {@code maxPx} wide. */
	void textCentered(HudText text, int role, int centerX, int baseline, int argb, int maxPx) {
		if (text.blank(s.theme)) {
			return;
		}
		TextLine line = text.line(surface, face(role));
		int w;
		boolean nested = enter();
		try {
			w = c.width(line, maxPx);
		} finally {
			exit(nested);
		}
		text(text, role, centerX - w / 2, baseline, argb, maxPx);
	}

	/**
	 * Draws {@code text} turned by {@code quarterTurns} quarter turns clockwise, centred on ({@code cx}, {@code cy}):
	 * the keystrokes module's arrows are one arrow glyph turned, so all four come from the same typeface. A shadow
	 * still falls down and to the right.
	 */
	void textTurned(HudText text, int role, int cx, int cy, int quarterTurns, int argb) {
		if (text.blank(s.theme)) {
			return;
		}
		TextLine line = text.line(surface, face(role));
		boolean nested = enter();
		try {
			int w = c.width(line);
			int cap = cap(role);
			if (recording) {
				record(TEXT, cx - w / 2, cy - cap / 2, w, cap, argb, 0);
				return;
			}
			int shadow = s.shadowFor(argb);
			if (shadow != 0) {
				turned(line, cx + s.shadowPx, cy + s.shadowPx, w, cap, quarterTurns, shadow);
			}
			turned(line, cx, cy, w, cap, quarterTurns, argb);
		} finally {
			exit(nested);
		}
	}

	private void turned(TextLine line, int cx, int cy, int w, int cap, int quarterTurns, int argb) {
		Matrix3x2fStack pose = c.graphics().pose();
		pose.pushMatrix();
		try {
			pose.translate(cx, cy);
			pose.rotate((float) (Math.PI / 2) * (quarterTurns & 3));
			c.text(line, -w / 2, cap / 2, argb);
		} finally {
			pose.popMatrix();
		}
	}

	/** Draws tabular digits from {@code x}; returns where they end. */
	int digits(char[] digits, int length, int role, int x, int baseline, int argb) {
		DigitRun run = HudFaces.digits(face(role));
		boolean nested = enter();
		try {
			if (recording) {
				int w = run.width(c, digits, length);
				int cap = cap(role);
				record(TEXT, x, baseline - cap, w, cap, argb, 0);
				return x + w;
			}
			int shadow = s.shadowFor(argb);
			if (shadow != 0) {
				run.draw(c, digits, length, x + s.shadowPx, baseline + s.shadowPx, shadow);
			}
			return run.draw(c, digits, length, x, baseline, argb);
		} finally {
			exit(nested);
		}
	}

	/** Classic text is drawn at its own whole pixel scale; returns whether that nested a canvas scale. */
	private boolean enter() {
		if (c.scale() == s.textScale) {
			return false;
		}
		Canvas.begin(c.graphics(), s.textScale);
		return true;
	}

	private void exit(boolean nested) {
		if (nested) {
			c.end();
		}
	}

	// ---------- shapes ----------

	/** The style's panel behind a module (nothing when the module has its panel off). */
	void panel(int x, int y, int w, int h) {
		if (!s.background || w <= 0 || h <= 0) {
			return;
		}
		box(x, y, w, h, s.radius, s.panel);
		if (s.outlinePx > 0 && !recording) {
			c.ringCorners(x, y, w, h, s.radius, s.outlinePx, s.outline);
		}
	}

	/** A filled rectangle with corners of {@code radius} px (square at 0), drawn with corner masks only. */
	void box(int x, int y, int w, int h, int radius, int argb) {
		if (w <= 0 || h <= 0) {
			return;
		}
		if (recording) {
			record(PANEL, x, y, w, h, argb, radius);
		} else if (radius <= 0) {
			c.fill(x, y, x + w, y + h, argb);
		} else {
			c.roundRectCorners(x, y, w, h, radius, argb);
		}
	}

	/** An outline {@code px} wide around a rectangle with corners of {@code radius}. */
	void ring(int x, int y, int w, int h, int radius, int px, int argb) {
		if (w <= 0 || h <= 0 || px <= 0) {
			return;
		}
		if (recording) {
			record(RING, x, y, w, h, argb, radius);
		} else if (radius <= 0) {
			c.fill(x, y, x + w, y + px, argb);
			c.fill(x, y + h - px, x + w, y + h, argb);
			c.fill(x, y + px, x + px, y + h - px, argb);
			c.fill(x + w - px, y + px, x + w, y + h - px, argb);
		} else {
			c.ringCorners(x, y, w, h, radius, px, argb);
		}
	}

	void fill(int x0, int y0, int x1, int y1, int argb) {
		if (x1 <= x0 || y1 <= y0) {
			return;
		}
		if (recording) {
			record(FILL, x0, y0, x1 - x0, y1 - y0, argb, 0);
		} else {
			c.fill(x0, y0, x1, y1, argb);
		}
	}

	void icon(Icon icon, int x, int y, int size, int argb) {
		if (recording) {
			record(ICON, x, y, size, size, argb, 0);
		} else {
			c.icon(icon, x, y, size, argb);
		}
	}

	/**
	 * An item drawn {@code size} px square with its decorations (Minecraft's durability bar and stack count),
	 * as the hotbar draws it. {@code count} is the stack count text (null: Minecraft's own, shown above 1).
	 * Minecraft builds render state objects for every item it draws; that is its cost, as for the hotbar.
	 */
	void item(ItemStack stack, int x, int y, int size, String count) {
		if (recording) {
			record(ITEM, x, y, size, size, 0xFFFFFFFF, 0);
			return;
		}
		GuiGraphicsExtractor graphics = c.graphics();
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(size / 16.0F, size / 16.0F);
		graphics.item(stack, 0, 0);
		graphics.itemDecorations(Minecraft.getInstance().font, stack, 0, 0, count);
		pose.popMatrix();
	}

	/** A GUI sprite (an effect icon), {@code size} px square, tinted (alpha fades it). */
	void sprite(Identifier sprite, int x, int y, int size, int argb) {
		if (recording) {
			record(SPRITE, x, y, size, size, argb, 0);
		} else {
			int color = c.tint(argb);
			if ((color >>> 24) != 0) {
				c.graphics().blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, size, size, color);
			}
		}
	}
}
