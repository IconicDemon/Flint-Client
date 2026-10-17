package com.flintclient.client.ui.kit;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.TextLine;

/**
 * A key's name on a small pill (12 units tall, a strong hairline outline, the name in {@link Type#META}): how the
 * deck shows a key binding ("Right Shift"). Right-aligned in its bounds. Not a control by itself.
 */
public class KeyChip extends Node {
	public static final float HEIGHT = 15;
	private static final float PAD_X = 6;

	private final TextLine key = new TextLine(Type.META, "");

	public KeyChip(String keyName) {
		key.set(keyName);
		this.h = HEIGHT;
	}

	public void setKey(String keyName) {
		if (!keyName.equals(key.text())) {
			key.set(keyName);
			invalidate();
		}
	}

	public String key() {
		return key.text();
	}

	@Override
	public float prefWidth() {
		return width(measure(), key);
	}

	@Override
	public float prefHeight(float width) {
		return HEIGHT;
	}

	@Override
	public String narration() {
		return key.text();
	}

	@Override
	protected void paint(Canvas c) {
		float chipW = Math.min(w, width(c, key));
		paintChip(c, key, x + w - chipW, y + (h - HEIGHT) / 2, chipW, Tokens2.TEXT_DIM, Tokens2.hairlineStrong(), 0);
	}

	/** A chip's width in units for {@code text}. */
	public static float width(Canvas c, TextLine text) {
		return (float) Math.ceil((float) c.width(text) / c.scale() + 2 * PAD_X);
	}

	/** Draws a chip {@code w} units wide at ({@code x}, {@code y}) with {@code text} centred in {@code ink}. */
	public static void paintChip(Canvas c, TextLine text, float x, float y, float w, int ink, int outline, int fill) {
		int x0 = c.px(x);
		int y0 = c.px(y);
		int pw = c.px(x + w) - x0;
		int ph = c.px(y + HEIGHT) - y0;
		if (fill != 0) {
			c.roundRect(x0, y0, pw, ph, ph / 2, fill);
		}
		c.ring(x0, y0, pw, ph, ph / 2, 1, outline);
		c.textCentered(text, x0 + pw / 2, c.baselineFor(text, y0 + ph / 2), ink, pw - c.px(2 * PAD_X - 2));
	}
}
