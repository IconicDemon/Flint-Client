package com.flintclient.client.ui.kit;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FontFace;
import com.flintclient.client.render.TextLine;

/** One line of text, vertically centred in its bounds, aligned left, centre or right, cut short with an ellipsis when it doesn't fit. */
public class Label extends Node {
	public enum Align {
		LEFT,
		CENTER,
		RIGHT
	}

	private final TextLine line;
	private int color;
	private Align align = Align.LEFT;

	public Label(FontFace face, String text, int color) {
		this(face, text, color, 0.0F);
	}

	public Label(FontFace face, String text, int color, float trackingEm) {
		this.line = new TextLine(face, text, trackingEm);
		this.color = color;
	}

	public Label align(Align align) {
		this.align = align;
		return this;
	}

	public void setText(String text) {
		line.set(text);
	}

	public String text() {
		return line.text();
	}

	public void setColor(int color) {
		this.color = color;
	}

	public TextLine line() {
		return line;
	}

	/** The text's width in units at the screen's scale. */
	@Override
	public float prefWidth() {
		Canvas c = measure();
		return (float) c.width(line) / c.scale();
	}

	@Override
	public float prefHeight(float width) {
		return line.face().size() * 1.5F;
	}

	@Override
	protected void paint(Canvas c) {
		int left = px0(c);
		int width = pxW(c);
		int baseline = c.baselineFor(line, c.px(y + h / 2));
		switch (align) {
			case LEFT -> c.text(line, left, baseline, color, width);
			case CENTER -> c.textCentered(line, left + width / 2, baseline, color, width);
			case RIGHT -> c.textRight(line, left + width, baseline, color, width);
		}
	}
}
