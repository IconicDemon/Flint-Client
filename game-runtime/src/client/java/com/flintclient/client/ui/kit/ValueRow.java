package com.flintclient.client.ui.kit;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.TextLine;

/**
 * A fact to read: a label at the left and its value at the right ({@link Type#META}, dim), as tall as a setting
 * row. A keyboard stop of its own, so the narrator can read it ("Render distance, 12 chunks").
 */
public class ValueRow extends Node {
	private static final float GAP = 10;

	private final TextLine label;
	private final TextLine value = new TextLine(Type.META, "");

	public ValueRow(String label, String value) {
		this.label = new TextLine(Type.LABEL, label);
		this.value.set(value);
		this.h = SettingRow.HEIGHT;
		name(label);
	}

	public void setValue(String text) {
		value.set(text);
	}

	public String value() {
		return value.text();
	}

	@Override
	public boolean interactive() {
		return true;
	}

	@Override
	public boolean focusable() {
		return true;
	}

	@Override
	public float prefHeight(float width) {
		return SettingRow.HEIGHT;
	}

	@Override
	public String narration() {
		return label.text() + ", " + value.text();
	}

	@Override
	protected void paint(Canvas c) {
		int center = c.px(y + h / 2);
		int right = px0(c) + pxW(c);
		int valueMax = Math.max(0, pxW(c) * 3 / 5);
		int valueW = c.width(value, valueMax);
		c.textRight(value, right, c.baselineFor(value, center), Tokens2.TEXT_DIM, valueMax);
		c.text(label, px0(c), c.baselineFor(label, center), Tokens2.muted(), Math.max(0, pxW(c) - valueW - c.px(GAP)));
		Kit.focusRing(c, x - 3, y + 2, w + 6, h - 4, 4, focus);
	}
}
