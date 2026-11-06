package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.MapMods.Minimap;
import com.flintclient.client.platform.HudData;
import com.flintclient.client.platform.HudMaps;
import com.flintclient.client.render.Palette;

/**
 * The Minimap on the HUD: the map around you (round or square, turned with you or north up), its frame and compass, the
 * entity radar, waypoints, your arrow, and lines of information under it (coordinates, biome, facing, the matching Nether
 * or Overworld place, the time of day). The map itself is drawn by {@link Mc263Minimap} from the map's cached textures;
 * this view lays it out and draws the text. Without a world (previews) it draws a still sample. Render thread.
 */
final class MinimapView extends ModuleView {
	private static final int MAX_LINES = 5;
	private final HudText[] lines = new HudText[MAX_LINES];
	private final int[] widths = new int[MAX_LINES];
	private int lineCount;
	private boolean live;
	private boolean sample;
	private boolean hidden;
	private int mapPx;
	private int gap;

	MinimapView(int surface) {
		super(Minimap.ID, surface);
		for (int i = 0; i < MAX_LINES; i++) {
			lines[i] = new HudText("");
		}
	}

	@Override
	void update(ModuleState state, int mode, long nowNanos) {
		hidden = mode == HUD && state.bool(Minimap.HIDE_WITH_F3) && HudData.debugOverlayOpen();
		live = (mode == HUD || mode == PREVIEW) && HudMaps.ready();
		sample = !live;
		String[] text = live ? HudMaps.info(state) : HudMaps.sampleInfo(state);
		lineCount = Math.min(MAX_LINES, text.length);
		for (int i = 0; i < lineCount; i++) {
			lines[i].set(text[i]);
		}
		if (mode == PLACEHOLDER) {
			lineCount = 0;
		}
	}

	@Override
	void measure(HudPen p, ModuleState state) {
		if (hidden) {
			width = 0;
			height = 0;
			return;
		}
		mapPx = p.s.px(state.integer(Minimap.SIZE));
		gap = p.s.px(3);
		int textH = lineCount == 0 ? 0 : gap + linesHeight(p, lineCount) + 2 * p.s.px(2);
		width = mapPx;
		for (int i = 0; i < lineCount; i++) {
			widths[i] = p.width(lines[i], HudFaces.VALUE);
		}
		height = mapPx + textH;
	}

	@Override
	void draw(HudPen p, ModuleState state, int x, int y) {
		if (p.recording()) {
			boolean round = state.option(Minimap.SHAPE) == 0;
			p.box(x, y, mapPx, mapPx, round ? mapPx / 2 : p.s.px(4), 0xC0202020);
			return;
		}
		float unit = p.s.px(100) / 100.0F;
		if (!sample && surface == HUD && HudMaps.enlarged()) {
			// Its enlarge key held: the map grows in the middle of the screen instead (as Xaero's), nothing at its spot.
			var g = p.canvas().frame();
			int gs = p.canvas().guiScale();
			HudMaps.drawEnlarged(p.canvas(), state, g.guiWidth() * gs, g.guiHeight() * gs, unit);
			return;
		}
		if (sample) {
			HudMaps.drawSample(p.canvas(), state, x, y, mapPx, unit);
		} else {
			HudMaps.draw(p.canvas(), state, x, y, mapPx, unit, surface == HUD);
		}
		if (lineCount == 0) {
			return;
		}
		int top = y + mapPx + gap;
		int textH = linesHeight(p, lineCount) + 2 * p.s.px(2);
		int widest = 0;
		for (int i = 0; i < lineCount; i++) {
			widest = Math.max(widest, widths[i]);
		}
		int opacity = state.integer(Minimap.INFO_BACKGROUND);
		if (opacity > 0) {
			int boxW = Math.min(mapPx, widest + 2 * p.s.px(4));
			p.box(x + (mapPx - boxW) / 2, top, boxW, textH, p.s.px(3), Palette.withAlpha(0xFF0B0B0C, opacity / 100.0F));
		}
		int lineY = top + p.s.px(2);
		for (int i = 0; i < lineCount; i++) {
			int w = Math.min(widths[i], mapPx);
			p.text(lines[i], HudFaces.VALUE, x + (mapPx - w) / 2, p.baseline(lineY), i == 0 ? p.s.text : p.s.muted, mapPx);
			lineY += p.s.lineH + p.s.lineGap;
		}
	}

	@Override
	boolean panelled() {
		return false;
	}

	@Override
	boolean ownLook() {
		return true;
	}

	@Override
	String emptyReason() {
		return "Shows in a world";
	}

	@Override
	int summary(ModuleState state, char[] out, long nowNanos) {
		String text = HudMaps.summary();
		if (text == null) {
			return -1;
		}
		int n = Math.min(out.length, text.length());
		text.getChars(0, n, out, 0);
		return n;
	}
}
