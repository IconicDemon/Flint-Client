package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.render.TextLine;

/**
 * A piece of HUD text that isn't a number: a label ("FPS", "FPS:", "fps" depending on the style), a unit, a
 * compass point, a key name or an effect's name. A {@link TextLine} caches its glyphs for one face at one pixel
 * scale, so each face (style × role × size) and each surface (the HUD, previews) gets its own line, made the
 * first time it is drawn and kept: drawing the same text again allocates nothing, whichever surface draws.
 * Render thread.
 */
class HudText {
	/** The in-world HUD and the HUD editor: drawn at the GUI scale. */
	static final int HUD = 0;
	/** Previews in the Control Center and first-time setup: drawn at the interface scale. */
	static final int PREVIEW = 1;
	static final int SURFACES = 2;

	/** The text per style ({@link HudThemeId#ordinal()}). */
	private final String[] text;
	private final TextLine[] lines = new TextLine[SURFACES * HudFaces.COUNT];

	/** The same text in every style. */
	HudText(String text) {
		this(text, text, text);
	}

	/** Per style, in {@link HudThemeId} order: Flint, Classic, Minimal. */
	HudText(String flint, String classic, String minimal) {
		this.text = new String[] {flint, classic, minimal};
	}

	/** The line to draw in face {@code faceIndex} on {@code surface}. */
	final TextLine line(int surface, int faceIndex) {
		int slot = surface * HudFaces.COUNT + faceIndex;
		TextLine line = lines[slot];
		if (line == null) {
			String value = text[HudFaces.theme(faceIndex).ordinal()];
			line = new TextLine(HudFaces.face(faceIndex), value, HudFaces.tracking(faceIndex));
			lines[slot] = line;
		}
		return line;
	}

	/** The text shown in {@code theme}. */
	final String text(HudThemeId theme) {
		return text[theme.ordinal()];
	}

	/** Whether this shows nothing in {@code theme} (a label left out of a style). */
	final boolean blank(HudThemeId theme) {
		return text[theme.ordinal()].isEmpty();
	}

	/**
	 * Changes the text in every style (a key was rebound, an effect's level went up). Lines already made keep
	 * their objects and only rebuild their glyphs the next time they are drawn; nothing happens when the text
	 * is unchanged.
	 */
	final void set(String value) {
		if (value.equals(text[0]) && value.equals(text[1]) && value.equals(text[2])) {
			return;
		}
		text[0] = value;
		text[1] = value;
		text[2] = value;
		for (TextLine line : lines) {
			if (line != null) {
				line.set(value);
			}
		}
	}
}
