package com.flintclient.client.render;

import java.util.ArrayList;
import java.util.List;

/** Text wrapped to a pixel width in one {@link FlintFonts} role; lines are re-wrapped only when the width or scale changes. */
public final class Paragraph {
	private final FlintFonts role;
	private final String text;
	private final List<TextLine> lines = new ArrayList<>();
	private int wrappedFor = -1;

	public Paragraph(FlintFonts role, String text) {
		this.role = role;
		this.text = text;
	}

	/** Lines that fit {@code maxPx}; words longer than the width are left whole on their own line. */
	public List<TextLine> lines(Canvas c, int maxPx) {
		int key = maxPx * 31 + c.scale();
		if (key != wrappedFor) {
			wrappedFor = key;
			lines.clear();
			for (String block : text.split("\n")) {
				StringBuilder current = new StringBuilder();
				TextLine probe = new TextLine(role, "");
				for (String word : block.split(" ")) {
					String candidate = current.isEmpty() ? word : current + " " + word;
					probe.set(candidate);
					if (c.width(probe) <= maxPx || current.isEmpty()) {
						current.setLength(0);
						current.append(candidate);
					} else {
						lines.add(new TextLine(role, current.toString()));
						current.setLength(0);
						current.append(word);
					}
				}
				lines.add(new TextLine(role, current.toString()));
			}
		}
		return lines;
	}

	/** Line height in pixels (1.5× the em). */
	public int lineHeightPx(Canvas c) {
		return Math.round(role.size * 1.5F * c.scale());
	}
}
