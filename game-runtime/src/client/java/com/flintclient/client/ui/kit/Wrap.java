package com.flintclient.client.ui.kit;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FontFace;
import com.flintclient.client.render.TextLine;
import java.util.ArrayList;
import java.util.List;

/**
 * Text in one face wrapped to a pixel width: explanations and notices. The lines are worked out again only when
 * the text, the width or the scale changes, so drawing it every frame allocates nothing.
 */
public final class Wrap {
	private final FontFace face;
	private final List<TextLine> lines = new ArrayList<>();
	private String text;
	private int wrappedFor = -1;
	private int wrappedScale = -1;

	public Wrap(FontFace face, String text) {
		this.face = face;
		this.text = text;
	}

	public String text() {
		return text;
	}

	public void set(String value) {
		if (!value.equals(text)) {
			text = value;
			wrappedFor = -1;
		}
	}

	public FontFace face() {
		return face;
	}

	/** The lines that fit {@code maxPx}; a word wider than that is left whole on its own line. */
	public List<TextLine> lines(Canvas c, int maxPx) {
		if (maxPx != wrappedFor || c.scale() != wrappedScale) {
			wrappedFor = maxPx;
			wrappedScale = c.scale();
			lines.clear();
			if (text.isEmpty()) {
				return lines;
			}
			TextLine probe = new TextLine(face, "");
			for (String block : text.split("\n")) {
				StringBuilder current = new StringBuilder();
				for (String word : block.split(" ")) {
					String candidate = current.isEmpty() ? word : current + " " + word;
					probe.set(candidate);
					if (current.isEmpty() || c.width(probe) <= maxPx) {
						current.setLength(0);
						current.append(candidate);
					} else {
						lines.add(new TextLine(face, current.toString()));
						current.setLength(0);
						current.append(word);
					}
				}
				lines.add(new TextLine(face, current.toString()));
			}
		}
		return lines;
	}

	/** The line pitch in units (about 1.45 × the size, for reading). */
	public float lineHeight() {
		return face.size() * 1.45F;
	}

	/** The height in units of the text wrapped to {@code widthUnits}. */
	public float height(Canvas c, float widthUnits) {
		int n = lines(c, Math.max(1, Math.round(widthUnits * c.scale()))).size();
		return n * lineHeight();
	}

	/** Draws the text from ({@code x}, {@code y}) units, wrapped to {@code widthUnits}. */
	public void paint(Canvas c, float x, float y, float widthUnits, int argb) {
		List<TextLine> shown = lines(c, Math.max(1, Math.round(widthUnits * c.scale())));
		float pitch = lineHeight();
		for (int i = 0; i < shown.size(); i++) {
			TextLine line = shown.get(i);
			int center = c.px(y + pitch * i + pitch / 2);
			c.text(line, c.px(x), c.baselineFor(line, center), argb);
		}
	}
}
