package com.flintclient.client.control.editor;

import com.flintclient.client.control.hud.HudRect;
import com.flintclient.client.control.hud.Snapper;
import com.flintclient.client.control.hud.VanillaAreas;
import com.flintclient.client.control.hud.render.HudRenderer;
import com.flintclient.client.control.platform.Mc263HudData;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.kit.Type;

/**
 * Where Minecraft draws its own HUD, for the editor: the hotbar and status bars always, the chat (from the chat
 * options), the boss bar's place, the effect icons while there are some and Minecraft shows them, and the
 * scoreboard while there is one.
 * Drawn as faint dashed outlines with a small capitals name, used as snapping guides, and checked for overlaps
 * with the hotbar and bars. The rectangles are worked out when the screen size changes and then about once a
 * second (effects and scoreboards come and go), never per frame. Render thread.
 */
final class VanillaGuides {
	static final int HOTBAR = 0;
	static final int STATUS = 1;
	static final int CHAT = 2;
	static final int BOSS = 3;
	static final int EFFECTS = 4;
	static final int SCOREBOARD = 5;
	static final int COUNT = 6;
	private static final String[] NAMES = {"HOTBAR", "HEALTH & FOOD", "CHAT", "BOSS BAR", "EFFECTS", "SCOREBOARD"};
	private static final String[] WARNINGS = {"Overlaps Minecraft's hotbar", "Overlaps Minecraft's health and food bars"};
	private static final long REFRESH_NANOS = 1_000_000_000L;
	/** Over the live game: faint white dashes, and the names in small tracked capitals at 40%. */
	private static final int OUTLINE = 0x20FFFFFF;
	private static final int LABEL = 0x66FFFFFF;

	private final float[] rects = new float[4 * COUNT];
	private final boolean[] present = new boolean[COUNT];
	private final TextLine[] labels = new TextLine[COUNT];
	private float screenW = -1;
	private float screenH = -1;
	private long refreshed;

	VanillaGuides() {
		for (int i = 0; i < COUNT; i++) {
			labels[i] = new TextLine(Type.OVERLINE, NAMES[i], Type.OVERLINE_TRACKING);
		}
	}

	/** Recomputes the areas when the screen size changed or a second has passed. */
	void update(float width, float height, long nowNanos) {
		if (width == screenW && height == screenH && nowNanos - refreshed < REFRESH_NANOS) {
			return;
		}
		screenW = width;
		screenH = height;
		refreshed = nowNanos;
		set(HOTBAR, VanillaAreas.hotbar(width, height));
		set(STATUS, VanillaAreas.statusBars(width, height));
		set(CHAT, VanillaAreas.chat(height, Mc263HudData.chatWidth(), Mc263HudData.chatHeight(), Mc263HudData.chatScale()));
		set(BOSS, VanillaAreas.bossBars(width, 1));
		// Minecraft's icons only take room while there are some and Flint's Effects module isn't hiding them.
		present[EFFECTS] = false;
		if (!HudRenderer.hideVanillaEffects()) {
			int[] rows = Mc263HudData.effectIconRows();
			if (rows[0] + rows[1] > 0) {
				set(EFFECTS, VanillaAreas.effects(width, rows[0], rows[1]));
			}
		}
		int[] sidebar = Mc263HudData.sidebar();
		if (sidebar == null) {
			present[SCOREBOARD] = false;
		} else {
			set(SCOREBOARD, VanillaAreas.sidebar(width, height, sidebar[0], sidebar[1]));
		}
	}

	private void set(int area, HudRect rect) {
		present[area] = true;
		rects[4 * area] = rect.x();
		rects[4 * area + 1] = rect.y();
		rects[4 * area + 2] = rect.width();
		rects[4 * area + 3] = rect.height();
	}

	/** Adds every present area to the snapper as guides. */
	void addTo(Snapper snapper) {
		for (int i = 0; i < COUNT; i++) {
			if (present[i]) {
				snapper.addArea(rects[4 * i], rects[4 * i + 1], rects[4 * i + 2], rects[4 * i + 3]);
			}
		}
	}

	/**
	 * Which of the hotbar ({@link #HOTBAR}) or status bars ({@link #STATUS}) a rectangle overlaps, or -1; the
	 * editor warns about these two, which a module would hide in play.
	 */
	int overlap(float x, float y, float w, float h) {
		for (int i = HOTBAR; i <= STATUS; i++) {
			if (present[i] && EditorMath.overlaps(x, y, w, h, rects[4 * i], rects[4 * i + 1], rects[4 * i + 2], rects[4 * i + 3])) {
				return i;
			}
		}
		return -1;
	}

	/** The tooltip for an {@link #overlap}. */
	static String warning(int area) {
		return WARNINGS[area];
	}

	/** Draws the outlines and names on a canvas begun in GUI units. */
	void paint(Canvas c, EditorPatterns patterns) {
		int g = c.guiScale();
		int line = lineFor(g);
		int dash = dashFor(g);
		int gap = gapFor(g);
		for (int i = 0; i < COUNT; i++) {
			if (!present[i]) {
				continue;
			}
			int x0 = Math.round(rects[4 * i] * g);
			int y0 = Math.round(rects[4 * i + 1] * g);
			int x1 = Math.round((rects[4 * i] + rects[4 * i + 2]) * g);
			int y1 = Math.round((rects[4 * i + 1] + rects[4 * i + 3]) * g);
			patterns.dashed(c, x0, y0, x1, y1, line, dash, gap, OUTLINE);
			TextLine label = labels[i];
			int pad = Math.round(2.0F * g);
			int baseline = c.baselineFor(label, y0 + pad + Math.round(label.capHeightPx(g) / 2.0F) + 1);
			c.text(label, x0 + pad, baseline, LABEL, Math.max(1, x1 - x0 - 2 * pad));
		}
	}

	/** The editor's dashed outlines (these areas and hidden modules) share one look, so one dash tile. */
	static int lineFor(int guiScale) {
		return Math.max(1, guiScale / 4);
	}

	static int dashFor(int guiScale) {
		return Math.max(2, 3 * guiScale);
	}

	static int gapFor(int guiScale) {
		return Math.max(2, 2 * guiScale);
	}
}
