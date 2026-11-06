package com.flintclient.client.render;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Text lines kept for the next frames, by face and text: labels that come and go with what is shown (waypoint and mob names,
 * badges, map labels), so each is measured once and drawing allocates nothing. Bounded: a face's lines are dropped when it
 * passes 512. Render thread.
 */
public final class Labels {
	private static final Map<FontFace, Map<String, TextLine>> LABELS = new IdentityHashMap<>();

	private Labels() {
	}

	/** A text line for {@code text} in {@code face}, kept for the next frames. */
	public static TextLine get(String text, FontFace face) {
		Map<String, TextLine> lines = LABELS.computeIfAbsent(face, k -> new HashMap<>());
		TextLine line = lines.get(text);
		if (line == null) {
			if (lines.size() > 512) {
				lines.clear();
			}
			line = new TextLine(face, text);
			lines.put(text, line);
		}
		return line;
	}
}
