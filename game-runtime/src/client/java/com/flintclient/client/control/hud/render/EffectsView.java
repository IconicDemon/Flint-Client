package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.model.DurationFormat;
import com.flintclient.client.control.model.EffectSort;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.ModuleSettings;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.Orientation;
import com.flintclient.client.platform.HudData;
import com.flintclient.client.platform.HudEffects;
import com.flintclient.client.ui.kit.Kit;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Effects, in two layouts.
 *
 * <p><b>Tiles</b> (the default) are Minecraft's own effect HUD made a module: each effect in Minecraft's 24-unit effect tile
 * (beacon effects in theirs, or a tile in custom colours, or none) with its 18-unit icon in the middle, tiles 1 unit apart, the
 * beneficial ones on one line and the harmful ones on a second line 2 units lower, by default in Minecraft's own top-right
 * corner. On each tile (or under it) the time left in Minecraft's font, short enough to fit ("1:49", "12m", "2h", "∞"), and from
 * level II the level in the tile's top-right corner ("II", or "2"); the icon
 * blinks as Minecraft's does in the last seconds and the time fades with it, never below half. Status Effect Bars draws its bar
 * on the tile exactly where it draws it on Minecraft's. The lines hug the anchored edges, so the module grows away from its
 * corner wherever it is moved; as columns, names can sit beside the tiles. While the tiles are on, Minecraft's own icons are
 * left out ({@link HudRenderer#hideVanillaEffects}), so nothing is shown twice.
 *
 * <p><b>List</b>: one entry per active effect, stacked (the default) or in a row: its icon (Minecraft's own effect
 * sprite), its name, its level in Roman numerals ("Speed II") and the time left as a clock ("1:30", or the infinity
 * sign) or in seconds ("90s"); each part can be left out. Soonest to end first, or by name. Effects Minecraft hides
 * from the HUD ({@code showIcon()} off) are left out here too. In the last ten seconds an entry pulses gently unless
 * motion is reduced or the setting is off; the time itself says how long is left, so the pulse adds nothing only
 * colour or motion could carry.
 *
 * <p>Reading the effects means iterating a collection, which allocates an iterator, so the list is rebuilt at
 * most four times a second into reused arrays (and at once when the order setting changes); names and sprite ids
 * are made once per effect and level. The time left is read from the kept effect objects every frame. With no
 * effects the module shows nothing on the HUD; previews show a row of dashes. While a screen lists the effects
 * itself (the inventory, with room beside it) the HUD leaves them out, as Minecraft does with its own icons.
 *
 * <p>The player the list was read from is only weakly held: the view outlives worlds (it is static), and a
 * strong reference would keep the last world in memory on the title screen.
 */
final class EffectsView extends ModuleView {
	private static final long REFRESH_NANOS = 250_000_000L;
	/** Where the text sits by its icon. */
	private static final int LEFT = 0;
	private static final int RIGHT = 1;
	private static final int BELOW = 2;
	private static final int ABOVE = 3;
	private static final int EMBED = 4;
	private static final long PULSE_NANOS = 1_000_000_000L;
	private static final HudText INFINITE = new HudText("∞");
	private static final HudText SPACE = new HudText(" ");
	/** The seconds format's unit: tabular digits cover numbers and punctuation only, so the "s" is drawn as text. */
	private static final HudText SECONDS = new HudText("s");
	/** The tiles' units past ten minutes ("12m") and an hour ("2h"), so a time never runs wider than its tile. */
	private static final HudText MINUTES = new HudText("m");
	private static final HudText HOURS = new HudText("h");
	/** Tiles the HUD editor shows (empty, with dashes) while there are no effects: enough to read as a row. */
	private static final int PLACEHOLDER_TILES = 3;
	/** Minecraft's effect tile: 24 units, its 18-unit icon in the middle; tiles 1 unit apart, the harmful line 2 units below. */
	private static final int TILE = 24;
	private static final int TILE_ICON = 18;
	private static final int TILE_LINE_GAP = 2;
	private static final HudText PLACEHOLDER_NAME = HudField.DASH;
	private static @Nullable Object infiniteLanguage;
	/** For counting the shown effects without keeping them. */
	private static final Object[] NONE = new Object[0];

	private final Map<Object, Entry> entries = new IdentityHashMap<>();
	private Object[] effects = new Object[8];
	private Entry[] rows = new Entry[8];
	private int[] remaining = new int[8];
	private int[] tieBreak = new int[8];
	private String[] names = new String[8];
	private int[] order = new int[8];
	/** Per read effect (same index as {@link #effects}): beneficial, so on the first line of tiles as Minecraft draws it. */
	private boolean[] good = new boolean[8];
	private int count;
	// ---------- the Tiles layout ----------
	private boolean tiles = true;
	private boolean tileColumn;
	private boolean tileSplit = true;
	private boolean tileTimeInside;
	/** {@link ModuleSettings.Effects#TILE_LEVEL}: off, a numeral or a number in the top-right corner. */
	private int tileLevel;
	private boolean tileShadow = true;
	private int tileGapUnits = 1;
	/** 0 Minecraft's effect tile, 1 custom colours, 2 none. */
	private int tileBackground;
	private int tileFill;
	private int tileBorder;
	private int tileHarmfulBorder;
	/** The placement's anchor: lines of tiles hug the anchored edges, as Minecraft's hug the right one. */
	private float anchorFx = 1.0F;
	private float anchorFy;
	/** This frame's tile metrics in pixels (set by {@link #measureTiles}). */
	private int tilePx;
	private int tileIcon;
	private int tileGap;
	private int tileLineGap;
	private int tileEntryH;
	private int tileTextScale = 1;
	private int goodCount;
	private int badCount;
	private int goodNames;
	private int badNames;
	private long lastRefresh;
	/** Who the list was read from, to notice a new player (respawn, another world) at once; weak, see above. */
	private @Nullable WeakReference<Object> readFrom;
	private @Nullable EffectSort sortedBy;
	/** On the HUD while a screen lists the effects itself. */
	private boolean hidden;
	private boolean placeholder;
	private boolean horizontal;
	private boolean showIcons;
	/** This frame's settings, for Status Effect Bars on the icons. */
	private @Nullable ModuleState barState;
	private boolean showNames;
	private boolean showLevel;
	private boolean showDuration;
	private boolean seconds;
	private boolean blink;
	/** Entries start pulsing this many ticks before the end. */
	private int endingTicks = 200;
	private int spacing;
	private int side = RIGHT;
	/** Two lines wanted (name above time) when the text sits beside the icon; {@link #stacked} is whether this frame has both to stack. */
	private boolean twoLines;
	private boolean stacked;
	private boolean framed;
	private boolean shorthand;
	private boolean effectColors;
	private int timeColor;
	private boolean lossSound;
	private float lossVolume;
	/** The effect types shown at the last refresh, to notice one ending. */
	private Object[] lastTypes = new Object[8];
	private int lastCount;
	private final char[] duration = new char[HudFormat.CHARS];
	private int iconPx;
	private int nameColumn;
	private int timeColumn;
	/** The summary's last effect count, and the collection size it was counted at (recounted only when that changes). */
	private int summarySize = -1;
	private int summaryPlayer;
	private int summaryCount;

	EffectsView(int surface) {
		super(ModuleId.EFFECTS, surface);
	}

	@Override
	boolean panelled() {
		// Each tile is its own background; the List keeps the style's panel.
		return !tiles;
	}

	@Override
	@Nullable String noPaddingReason() {
		return tiles ? "Tiles have no panel around them" : null;
	}

	@Override
	void update(ModuleState state, int mode, long nowNanos) {
		barState = state;
		tiles = state.option(ModuleSettings.Effects.VIEW) == ModuleSettings.Effects.TILES;
		tileColumn = state.option(ModuleSettings.Effects.TILE_DIRECTION) == 1;
		tileSplit = state.bool(ModuleSettings.Effects.TILE_SPLIT);
		tileTimeInside = state.option(ModuleSettings.Effects.TILE_TIME) == 1;
		tileLevel = state.option(ModuleSettings.Effects.TILE_LEVEL);
		tileShadow = state.bool(ModuleSettings.Effects.TILE_SHADOW);
		tileGapUnits = state.integer(ModuleSettings.Effects.TILE_GAP);
		tileBackground = state.option(ModuleSettings.Effects.TILE_BACKGROUND);
		tileFill = com.flintclient.client.render.Palette.withAlpha(0xFF000000 | state.rgb(ModuleSettings.Effects.TILE_COLOR),
			state.integer(ModuleSettings.Effects.TILE_OPACITY) / 100.0F);
		tileBorder = 0xFF000000 | state.rgb(ModuleSettings.Effects.TILE_BORDER);
		tileHarmfulBorder = 0xFF000000 | state.rgb(ModuleSettings.Effects.TILE_HARMFUL_BORDER);
		anchorFx = state.placement().anchor().fx();
		anchorFy = state.placement().anchor().fy();
		horizontal = state.choice(ModuleSettings.Effects.LAYOUT) == Orientation.HORIZONTAL;
		showNames = state.bool(ModuleSettings.Effects.SHOW_NAMES);
		showLevel = state.bool(ModuleSettings.Effects.SHOW_LEVEL);
		showDuration = state.bool(ModuleSettings.Effects.SHOW_DURATION);
		// With every part off the icons stay, so the module never draws an empty panel.
		showIcons = state.bool(ModuleSettings.Effects.SHOW_ICONS) || !showNames && !showLevel && !showDuration;
		seconds = state.choice(ModuleSettings.Effects.DURATION_FORMAT) == DurationFormat.SECONDS;
		spacing = state.integer(ModuleSettings.Effects.SPACING);
		blink = state.bool(ModuleSettings.Effects.BLINK_ENDING);
		endingTicks = state.integer(ModuleSettings.Effects.BLINK_SECONDS) * 20;
		side = state.option(ModuleSettings.Effects.TEXT_SIDE);
		framed = state.option(ModuleSettings.Effects.STYLE) == 1;
		shorthand = state.bool(ModuleSettings.Effects.SHORTHAND);
		twoLines = state.bool(ModuleSettings.Effects.TWO_LINES);
		effectColors = state.bool(ModuleSettings.Effects.EFFECT_COLORS);
		timeColor = 0xFF000000 | state.rgb(ModuleSettings.Effects.TIME_COLOR);
		lossSound = state.bool(ModuleSettings.Effects.LOSS_SOUND);
		lossVolume = state.integer(ModuleSettings.Effects.LOSS_VOLUME) / 100.0F;
		EffectSort sort = state.choice(ModuleSettings.Effects.SORT);
		hidden = false;
		if (mode == PLACEHOLDER) {
			// The editor's outline size: a row of dashes. The live list is kept, so it isn't read again next frame.
			placeholder = true;
			return;
		}
		Object player = HudData.player();
		if (player == null) {
			forget();
			placeholder = mode == PREVIEW;
			return;
		}
		Object last = readFrom == null ? null : readFrom.get();
		if (player != last) {
			// A new player object (joined, respawned): one small allocation per player, never per frame.
			readFrom = new WeakReference<>(player);
			lastRefresh = nowNanos;
			refresh(sort);
		} else if (nowNanos - lastRefresh >= REFRESH_NANOS || sort != sortedBy) {
			lastRefresh = nowNanos;
			refresh(sort);
		}
		placeholder = count == 0 && mode == PREVIEW;
		hidden = mode == HUD && HudData.screenShowsEffects();
	}

	@Override
	void forget() {
		for (int i = 0; i < count; i++) {
			effects[i] = null;
			rows[i] = null;
			names[i] = null;
		}
		count = 0;
		readFrom = null;
	}

	/** Rebuilds the rows from the player's effects (the iterator is the one allocation, four times a second). */
	private void refresh(EffectSort sort) {
		Object language = HudData.language();
		if (language != infiniteLanguage) {
			infiniteLanguage = language;
			INFINITE.set(HudData.translate("effect.duration.infinite", "∞"));
		}
		int n = HudEffects.read(effects);
		while (n > effects.length) {
			grow();
			n = HudEffects.read(effects);
		}
		for (int i = 0; i < n; i++) {
			Object effect = effects[i];
			remaining[i] = HudEffects.remaining(effect);
			tieBreak[i] = HudEffects.id(effect);
			good[i] = HudEffects.beneficial(effect);
			Entry entry = entry(effect, language);
			rows[i] = entry;
			names[i] = entry.base;
		}
		for (int i = n; i < count; i++) {
			effects[i] = null;
			rows[i] = null;
			names[i] = null;
		}
		count = n;
		sortedBy = sort;
		noticeEnded(n);
		if (sort == EffectSort.NAME) {
			EffectOrder.sortByName(names, tieBreak, n, order);
		} else {
			EffectOrder.sort(remaining, tieBreak, n, order);
		}
	}

	/** A soft chime when an effect that was shown is gone (once per refresh, however many ended). */
	private void noticeEnded(int n) {
		boolean ended = false;
		for (int k = 0; k < lastCount; k++) {
			Object type = lastTypes[k];
			boolean still = false;
			for (int i = 0; i < n && !still; i++) {
				still = HudEffects.type(effects[i]) == type;
			}
			ended |= !still;
		}
		if (ended && lossSound && lossVolume > 0) {
			HudEffects.chime(lossVolume);
		}
		if (lastTypes.length < n) {
			lastTypes = new Object[effects.length];
		}
		for (int i = 0; i < n; i++) {
			lastTypes[i] = HudEffects.type(effects[i]);
		}
		for (int i = n; i < lastCount; i++) {
			lastTypes[i] = null;
		}
		lastCount = n;
	}

	private Entry entry(Object effect, Object language) {
		Object type = HudEffects.type(effect);
		Entry entry = entries.get(type);
		if (entry == null) {
			entry = new Entry(HudEffects.icon(effect));
			entries.put(type, entry);
		}
		entry.name(type, HudEffects.amplifier(effect), language);
		return entry;
	}

	private void grow() {
		int size = effects.length * 2;
		effects = Arrays.copyOf(effects, size);
		rows = Arrays.copyOf(rows, size);
		remaining = Arrays.copyOf(remaining, size);
		tieBreak = Arrays.copyOf(tieBreak, size);
		names = Arrays.copyOf(names, size);
		order = Arrays.copyOf(order, size);
		good = Arrays.copyOf(good, size);
	}

	private int rowsShown() {
		return hidden ? 0 : placeholder ? (tiles ? PLACEHOLDER_TILES : 1) : count;
	}

	/** The icon's square: the icon, plus Minecraft's effect frame around it in the Framed style. */
	private int cell() {
		return showIcons ? iconPx + 2 * framePad() : 0;
	}

	private int framePad() {
		return framed ? Math.max(1, iconPx / 6) : 0;
	}

	private boolean beside() {
		return side == LEFT || side == RIGHT;
	}

	@Override
	void measure(HudPen p, ModuleState state) {
		HudStyle s = p.s;
		int shown = rowsShown();
		if (shown == 0) {
			width = 0;
			height = 0;
			return;
		}
		if (tiles) {
			int saved = s.textScale;
			// Small, as the time under a tile should be: three quarters of the module's size, on whole pixels so the font stays crisp.
			s.textScale = Math.max(1, Math.round(0.75F * s.u));
			try {
				measureTiles(p, shown);
			} finally {
				s.textScale = saved;
			}
			return;
		}
		float tickrate = HudData.tickrate();
		nameColumn = 0;
		timeColumn = 0;
		for (int i = 0; i < shown; i++) {
			nameColumn = Math.max(nameColumn, nameWidth(p, i));
			timeColumn = Math.max(timeColumn, side == EMBED ? 0 : timeWidth(p, i, tickrate));
		}
		// Feather's look: the name above the time beside an 18-unit icon (Minecraft's own effect sprite size).
		stacked = twoLines && beside() && nameColumn > 0 && timeColumn > 0;
		iconPx = !showIcons ? 0 : stacked ? s.px(framed ? 14 : 18) : Math.max(6, s.px(framed || side == EMBED ? 13 : 9));
		if (horizontal) {
			int total = 0;
			for (int i = 0; i < shown; i++) {
				total += entryWidth(p, nameWidth(p, i), side == EMBED ? 0 : timeWidth(p, i, tickrate));
			}
			width = total + (shown - 1) * entryGap(p);
			height = rowHeight(p);
			return;
		}
		width = entryWidth(p, nameColumn, timeColumn);
		height = shown * rowHeight(p) + (shown - 1) * s.px(spacing);
	}

	private int rowHeight(HudPen p) {
		HudStyle s = p.s;
		int cell = cell();
		if (stacked) {
			return Math.max(cell, stackHeight(s));
		}
		if (beside() || side == EMBED) {
			return Math.max(cell, s.lineH);
		}
		boolean text = nameColumn > 0 || timeColumn > 0;
		return cell + (text ? (cell > 0 ? Math.max(1, s.px(2)) : 0) + s.lineH : 0);
	}

	/** Two lines of text and the gap between them. */
	private static int stackHeight(HudStyle s) {
		return 2 * s.lineH + Math.max(1, s.px(2));
	}

	/** The width of the text: name part and time with a space between, or 0. */
	private static int textWidth(HudStyle s, int name, int time) {
		return name + time + (name > 0 && time > 0 ? s.space : 0);
	}

	/** Icon and text as laid out for the text position. */
	private int entryWidth(HudPen p, int name, int time) {
		HudStyle s = p.s;
		int cell = cell();
		int text = stacked ? Math.max(name, time) : textWidth(s, name, time);
		if (beside() || side == EMBED) {
			return cell + (text > 0 ? (cell > 0 ? s.space : 0) + text : 0);
		}
		return Math.max(cell, text);
	}

	/** Between entries in a row: two spaces plus the spacing setting. */
	private int entryGap(HudPen p) {
		return 2 * p.s.space + p.s.px(spacing);
	}

	/** The name with its level ("Speed II"), as shown, or 0 when neither shows. */
	private int nameWidth(HudPen p, int row) {
		if (placeholder) {
			return showNames || showLevel ? p.width(PLACEHOLDER_NAME, HudFaces.VALUE) : 0;
		}
		Entry entry = rows[order[row]];
		int w = showNames ? p.width(entry.name, HudFaces.VALUE) : 0;
		if (showLevel && entry.leveled) {
			w += (w > 0 ? p.width(SPACE, HudFaces.VALUE) : 0) + p.width(entry.level, HudFaces.VALUE);
		}
		return w;
	}

	private int timeWidth(HudPen p, int row, float tickrate) {
		if (!showDuration) {
			return 0;
		}
		if (placeholder) {
			return p.width(HudField.DASH, HudFaces.VALUE);
		}
		Object effect = rowEffect(row);
		if (HudEffects.infinite(effect)) {
			return p.width(INFINITE, HudFaces.VALUE);
		}
		if (shorthand) {
			return p.width(rows[order[row]].shortTime(HudEffects.duration(effect), tickrate), HudFaces.VALUE);
		}
		int n = writeTime(effect, tickrate);
		return seconds ? p.width(duration, n - 1, HudFaces.VALUE) + p.width(SECONDS, HudFaces.VALUE) : p.width(duration, n, HudFaces.VALUE);
	}

	private int writeTime(Object effect, float tickrate) {
		return seconds ? HudFormat.seconds(HudEffects.duration(effect), tickrate, duration) : HudFormat.clock(HudEffects.duration(effect), tickrate, duration);
	}

	/** The effect shown on sorted row {@code row}. */
	private Object rowEffect(int row) {
		return effects[order[row]];
	}

	@Override
	void draw(HudPen p, ModuleState state, int x, int y) {
		HudStyle s = p.s;
		int shown = rowsShown();
		if (tiles) {
			int saved = s.textScale;
			int savedShadow = s.shadowPx;
			boolean savedMinecraftShadow = s.minecraftShadow;
			s.textScale = tileTextScale;
			// Minecraft's own shadow, one font pixel down and right (the Time shadow setting), as its HUD text has.
			s.shadowPx = tileShadow ? tileTextScale : 0;
			s.minecraftShadow = true;
			try {
				drawTiles(p, shown, x, y);
			} finally {
				s.textScale = saved;
				s.shadowPx = savedShadow;
				s.minecraftShadow = savedMinecraftShadow;
			}
			return;
		}
		int rowH = rowHeight(p);
		float tickrate = HudData.tickrate();
		float before = p.canvas().opacity();
		int left = x;
		int top = y;
		for (int i = 0; i < shown; i++) {
			float alpha = placeholder ? 1.0F : rowAlpha(rowEffect(i));
			p.canvas().setOpacity(before * alpha);
			int name = horizontal ? nameWidth(p, i) : nameColumn;
			int time = horizontal ? (side == EMBED ? 0 : timeWidth(p, i, tickrate)) : timeColumn;
			int entryW = horizontal ? entryWidth(p, name, time) : width;
			drawEntry(p, i, left, top, entryW, rowH, name, time, tickrate);
			if (horizontal) {
				left += entryW + entryGap(p);
			} else {
				top += rowH + s.px(spacing);
			}
		}
		p.canvas().setOpacity(before);
	}

	/** One entry in the box ({@code x}, {@code top}, {@code entryW} × {@code rowH}), laid out for the text position. */
	private void drawEntry(HudPen p, int i, int x, int top, int entryW, int rowH, int nameColumn, int timeColumn, float tickrate) {
		HudStyle s = p.s;
		int cell = cell();
		if (stacked) {
			drawStacked(p, i, x, top, entryW, rowH, nameColumn, tickrate);
			return;
		}
		int textW = textWidth(s, nameColumn, timeColumn);
		switch (side) {
			case LEFT -> {
				icon(p, i, x + entryW - cell, top + (rowH - cell) / 2);
				text(p, i, x + entryW - cell - (cell > 0 ? s.space : 0) - textW, p.baseline(top + (rowH - s.lineH) / 2), nameColumn, timeColumn,
					tickrate);
			}
			case BELOW -> {
				icon(p, i, x + (entryW - cell) / 2, top);
				int textTop = top + cell + (cell > 0 ? Math.max(1, s.px(2)) : 0);
				text(p, i, x + (entryW - textW) / 2, p.baseline(textTop), nameColumn, timeColumn, tickrate);
			}
			case ABOVE -> {
				text(p, i, x + (entryW - textW) / 2, p.baseline(top), nameColumn, timeColumn, tickrate);
				icon(p, i, x + (entryW - cell) / 2, top + rowH - cell);
			}
			case EMBED -> {
				int iconY = top + (rowH - cell) / 2;
				icon(p, i, x, iconY);
				embedTime(p, i, x, iconY, cell, tickrate);
				text(p, i, x + cell + (cell > 0 ? s.space : 0), p.baseline(top + (rowH - s.lineH) / 2), nameColumn, 0, tickrate);
			}
			default -> {
				icon(p, i, x, top + (rowH - cell) / 2);
				text(p, i, x + cell + (cell > 0 ? s.space : 0), p.baseline(top + (rowH - s.lineH) / 2), nameColumn, timeColumn, tickrate);
			}
		}
	}

	/**
	 * Feather's two lines: the icon at its side, the name above the time in a column beside it, left-aligned when the
	 * text is on the right and right-aligned when it is on the left.
	 */
	private void drawStacked(HudPen p, int i, int x, int top, int entryW, int rowH, int nameColumn, float tickrate) {
		HudStyle s = p.s;
		int cell = cell();
		int gap = cell > 0 ? s.space : 0;
		int textTop = top + (rowH - stackHeight(s)) / 2;
		int nameBaseline = p.baseline(textTop);
		int timeBaseline = p.baseline(textTop + s.lineH + Math.max(1, s.px(2)));
		if (side == LEFT) {
			icon(p, i, x + entryW - cell, top + (rowH - cell) / 2);
			int right = x + entryW - cell - gap;
			int name = nameWidth(p, i);
			text(p, i, right - name, nameBaseline, name, 0, tickrate);
			time(p, i, right, timeBaseline, tickrate);
		} else {
			icon(p, i, x, top + (rowH - cell) / 2);
			int left = x + cell + gap;
			text(p, i, left, nameBaseline, nameColumn, 0, tickrate);
			time(p, i, left + timeWidth(p, i, tickrate), timeBaseline, tickrate);
		}
	}

	// ---------- Tiles: Minecraft's own effect tiles, with the time under each ----------

	/** Whether sorted row {@code i} goes on the first line (beneficial, or every effect when the lines aren't split). */
	private boolean firstLine(int i) {
		return placeholder || !tileSplit || good[order[i]];
	}

	/** Names beside the tiles: only in a column, where there is room for them. */
	private boolean tileNames() {
		return tileColumn && (showNames || showLevel);
	}

	/** Tiles in a line of {@code n}, gaps included. */
	private int lineLength(int n) {
		return n <= 0 ? 0 : n * tilePx + (n - 1) * tileGap;
	}

	/** Between tiles stacked in a column: the tile spacing, and at least the 2 units between lines when a time sits under each. */
	private int columnGap() {
		return showDuration && !tileTimeInside ? Math.max(tileGap, tileLineGap) : tileGap;
	}

	/** Tiles stacked in a column of {@code n}, each with its time below, gaps included. */
	private int columnLength(int n) {
		return n <= 0 ? 0 : n * tileEntryH + (n - 1) * columnGap();
	}

	/** A column's width: its tiles, and its names beside them. */
	private int columnWidth(HudPen p, int names) {
		return tilePx + (names > 0 ? p.s.space + names : 0);
	}

	/**
	 * Sizes the tiles at this size step (a tile is Minecraft's 24 units, its icon 18) and the whole box: one line for the
	 * beneficial effects and one for the harmful ones (as Minecraft splits them), as rows or as columns.
	 */
	private void measureTiles(HudPen p, int shown) {
		HudStyle s = p.s;
		tileTextScale = s.textScale;
		tilePx = s.px(TILE);
		tileIcon = s.px(TILE_ICON);
		tileGap = s.px(tileGapUnits);
		tileLineGap = Math.max(1, s.px(TILE_LINE_GAP));
		boolean below = showDuration && !tileTimeInside;
		// The time under a tile: a unit of air, then a line of Minecraft's font (7 pixels of digits and one below).
		tileEntryH = tilePx + (below ? Math.max(1, s.px(1)) + 8 * tileTextScale : 0);
		goodCount = 0;
		badCount = 0;
		goodNames = 0;
		badNames = 0;
		boolean names = tileNames();
		for (int i = 0; i < shown; i++) {
			int name = names ? nameWidth(p, i) : 0;
			if (firstLine(i)) {
				goodCount++;
				goodNames = Math.max(goodNames, name);
			} else {
				badCount++;
				badNames = Math.max(badNames, name);
			}
		}
		int lines = (goodCount > 0 ? 1 : 0) + (badCount > 0 ? 1 : 0);
		if (tileColumn) {
			width = (goodCount > 0 ? columnWidth(p, goodNames) : 0) + (badCount > 0 ? columnWidth(p, badNames) : 0) + (lines - 1) * tileLineGap * 2;
			height = Math.max(columnLength(goodCount), columnLength(badCount));
		} else {
			width = Math.max(lineLength(goodCount), lineLength(badCount));
			height = lines * tileEntryH + (lines - 1) * tileLineGap;
		}
	}

	/**
	 * The tiles: each line hugs the anchored edge (the right one in Minecraft's corner), the beneficial line nearest the anchored
	 * top or bottom (or, in columns, nearest the anchored side), the soonest to end first in reading order.
	 */
	private void drawTiles(HudPen p, int shown, int x, int y) {
		float tickrate = HudData.tickrate();
		float before = p.canvas().opacity();
		try {
			if (tileColumn) {
				// Beneficial nearest the anchored side: on the right in a right-hand corner.
				boolean goodRight = anchorFx >= 1.0F;
				int goodW = goodCount > 0 ? columnWidth(p, goodNames) : 0;
				int badW = badCount > 0 ? columnWidth(p, badNames) : 0;
				int split = goodCount > 0 && badCount > 0 ? tileLineGap * 2 : 0;
				int goodLeft = goodRight ? x + badW + split : x;
				int badLeft = goodRight ? x : x + goodW + split;
				drawColumn(p, shown, true, goodLeft, goodW, goodNames, y, tickrate);
				drawColumn(p, shown, false, badLeft, badW, badNames, y, tickrate);
			} else {
				// Beneficial on top, as Minecraft draws them; at a bottom anchor nearest the bottom edge instead.
				boolean goodFirst = anchorFy < 1.0F || goodCount == 0 || badCount == 0;
				int firstTop = y;
				int secondTop = y + tileEntryH + tileLineGap;
				if (goodCount > 0) {
					drawLine(p, shown, true, x, goodFirst ? firstTop : secondTop, tickrate);
				}
				if (badCount > 0) {
					drawLine(p, shown, false, x, goodFirst && goodCount > 0 ? secondTop : firstTop, tickrate);
				}
			}
		} finally {
			p.canvas().setOpacity(before);
		}
	}

	/** One line of tiles, aligned in the box to the anchored side. */
	private void drawLine(HudPen p, int shown, boolean first, int x, int top, float tickrate) {
		int n = first ? goodCount : badCount;
		int left = x + Math.round((width - lineLength(n)) * anchorFx);
		for (int i = 0; i < shown; i++) {
			if (firstLine(i) == first) {
				tile(p, i, left, top, tickrate);
				left += tilePx + tileGap;
			}
		}
	}

	/** One column of tiles, aligned in the box to the anchored edge, the names on the side away from it. */
	private void drawColumn(HudPen p, int shown, boolean first, int left, int columnW, int names, int y, float tickrate) {
		int n = first ? goodCount : badCount;
		if (n == 0) {
			return;
		}
		HudStyle s = p.s;
		boolean namesLeft = anchorFx >= 1.0F;
		int tileX = namesLeft ? left + columnW - tilePx : left;
		int top = y + Math.round((height - columnLength(n)) * anchorFy);
		for (int i = 0; i < shown; i++) {
			if (firstLine(i) != first) {
				continue;
			}
			tile(p, i, tileX, top, tickrate);
			if (names > 0) {
				int w = nameWidth(p, i);
				int baseline = top + (tilePx - 7 * tileTextScale) / 2 + 7 * tileTextScale;
				int textX = namesLeft ? tileX - s.space - w : tileX + tilePx + s.space;
				text(p, i, textX, baseline, w, 0, tickrate);
			}
			top += tileEntryH + columnGap();
		}
	}

	/** One tile: its background, Status Effect Bars' bar, the icon (blinking as Minecraft's does near the end), the level and the time. */
	private void tile(HudPen p, int i, int x, int y, float tickrate) {
		HudStyle s = p.s;
		float before = p.canvas().opacity();
		Object effect = placeholder ? null : rowEffect(i);
		boolean ambient = effect != null && HudEffects.ambient(effect);
		boolean harmful = effect != null && !good[order[i]];
		switch (tileBackground) {
			case 0 -> p.sprite(HudEffects.tile(ambient), x, y, tilePx, 0xFFFFFFFF);
			case 1 -> {
				int radius = s.px(2);
				p.box(x, y, tilePx, tilePx, radius, tileFill);
				p.ring(x, y, tilePx, tilePx, radius, Math.max(1, Math.round(s.u / 2.0F)), harmful ? tileHarmfulBorder : tileBorder);
			}
			default -> {
			}
		}
		int inset = (tilePx - tileIcon) / 2;
		if (effect == null) {
			p.ring(x + inset, y + inset, tileIcon, tileIcon, s.classic() ? 0 : s.px(1.5F), Math.max(1, s.unitPx / 3), s.muted);
		} else {
			bar(p, i, x, y, tilePx);
			float alpha = tileAlpha(effect);
			p.sprite(rows[order[i]].sprite, x + inset, y + inset, tileIcon, com.flintclient.client.render.Palette.withAlpha(0xFFFFFFFF, alpha));
			tileLevel(p, rows[order[i]], x, y, before * (0.5F + 0.5F * alpha));
		}
		if (showDuration) {
			// The time fades with the icon, but never below half, so it can still be read as the effect runs out.
			float timeAlpha = effect == null ? 1.0F : 0.5F + 0.5F * tileAlpha(effect);
			p.canvas().setOpacity(before * timeAlpha);
			int cap = 7 * tileTextScale;
			int unit = Math.max(1, s.px(1));
			if (tileTimeInside) {
				// Along the tile's bottom, inside its 1-unit border, over a soft shade so it reads on any icon.
				int baseline = y + tilePx - 2 * unit;
				p.fill(x + unit, baseline - cap - unit, x + tilePx - unit, y + tilePx - unit, 0x99000000);
				tileTime(p, i, x + tilePx / 2, baseline, tickrate);
			} else {
				tileTime(p, i, x + tilePx / 2, y + tilePx + unit + cap, tickrate);
			}
			p.canvas().setOpacity(before);
		}
	}

	/** From level II, the level in the tile's top-right corner, inside its 1-unit border, fading with the icon but never below half. */
	private void tileLevel(HudPen p, Entry entry, int x, int y, float opacity) {
		if (tileLevel == ModuleSettings.Effects.TILE_LEVEL_OFF || !entry.leveled) {
			return;
		}
		HudText level = tileLevel == ModuleSettings.Effects.TILE_LEVEL_NUMBER ? entry.number : entry.level;
		int unit = Math.max(1, p.s.px(1));
		float before = p.canvas().opacity();
		p.canvas().setOpacity(opacity);
		p.text(level, HudFaces.VALUE, x + tilePx - unit - p.width(level, HudFaces.VALUE), y + unit + 7 * tileTextScale, 0xFFFFFFFF);
		p.canvas().setOpacity(before);
	}

	/**
	 * Minecraft's own blink for an effect running out (Hud.extractEffects): from the last ten seconds (the Blink from setting)
	 * the icon dims and flickers faster and deeper towards the end; beacon effects don't blink. Off with the setting or reduced
	 * motion.
	 */
	private float tileAlpha(Object effect) {
		int window = Math.max(20, endingTicks);
		if (!blink || Kit.reduced() || HudEffects.ambient(effect) || HudEffects.infinite(effect) || !HudEffects.endsWithin(effect, window)) {
			return 1.0F;
		}
		int remaining = HudEffects.duration(effect);
		int windowSeconds = window / 20;
		int usedSeconds = windowSeconds - remaining / 20;
		float alpha = Math.clamp(remaining / 10.0F / 5.0F * 0.5F, 0.0F, 0.5F)
			+ (float) Math.cos(remaining * Math.PI / 5.0) * Math.clamp(usedSeconds / (float) windowSeconds * 0.25F, 0.0F, 0.25F);
		return Math.clamp(alpha, 0.0F, 1.0F);
	}

	/**
	 * The time left, centred on {@code centerX}, short enough to sit under its tile: "1:49" up to ten minutes, then "12m" and
	 * "2h" ("90s" in the seconds format up to 999 seconds), the infinity sign for an endless effect, a dash in the editor.
	 */
	private void tileTime(HudPen p, int i, int centerX, int baseline, float tickrate) {
		if (placeholder) {
			p.text(HudField.DASH, HudFaces.VALUE, centerX - p.width(HudField.DASH, HudFaces.VALUE) / 2, baseline, p.s.muted);
			return;
		}
		Object effect = rowEffect(i);
		if (HudEffects.infinite(effect)) {
			p.text(INFINITE, HudFaces.VALUE, centerX - p.width(INFINITE, HudFaces.VALUE) / 2, baseline, timeColor);
			return;
		}
		int total = (int) (HudEffects.duration(effect) / Math.max(1.0F, tickrate));
		HudText unit = null;
		int n;
		if (seconds && total < 1000) {
			n = digitsOf(total, duration, 0);
			unit = SECONDS;
		} else if (total < 600) {
			n = digitsOf(total / 60, duration, 0);
			duration[n++] = ':';
			duration[n++] = (char) ('0' + total % 60 / 10);
			duration[n++] = (char) ('0' + total % 10);
		} else if (total < 3600) {
			n = digitsOf(total / 60, duration, 0);
			unit = MINUTES;
		} else {
			n = digitsOf(Math.min(total / 3600, 99_999), duration, 0);
			unit = HOURS;
		}
		int w = p.width(duration, n, HudFaces.VALUE) + (unit != null ? p.width(unit, HudFaces.VALUE) : 0);
		int end = p.digits(duration, n, HudFaces.VALUE, centerX - w / 2, baseline, timeColor);
		if (unit != null) {
			p.text(unit, HudFaces.VALUE, end, baseline, timeColor);
		}
	}

	/** Writes {@code value} (0 or more) as decimal digits into {@code out} from {@code at}; returns the end. */
	private static int digitsOf(int value, char[] out, int at) {
		int digits = 1;
		for (int v = value; v >= 10; v /= 10) {
			digits++;
		}
		for (int k = digits - 1, v = value; k >= 0; k--, v /= 10) {
			out[at + k] = (char) ('0' + v % 10);
		}
		return at + digits;
	}

	/** The effect's icon (in its frame in the Framed style), or an outline in the editor. */
	private void icon(HudPen p, int i, int x, int y) {
		if (!showIcons) {
			return;
		}
		HudStyle s = p.s;
		int pad = framePad();
		if (placeholder) {
			p.ring(x + pad, y + pad, iconPx, iconPx, s.classic() ? 0 : s.px(1.5F), Math.max(1, s.unitPx / 3), s.muted);
			return;
		}
		if (framed) {
			p.sprite(HudEffects.tile(HudEffects.ambient(rowEffect(i))), x, y, iconPx + 2 * pad, 0xFFFFFFFF);
		}
		bar(p, i, x, y, iconPx + 2 * pad);
		p.sprite(rows[order[i]].sprite, x + pad, y + pad, iconPx, 0xFFFFFFFF);
	}

	/**
	 * Status Effect Bars on this list's icon: its HUD layout over the icon's square as if it were Minecraft's 24-unit frame, so the
	 * bar keeps its place and thickness at any size.
	 */
	private void bar(HudPen p, int i, int x, int y, int cell) {
		ModuleState state = barState;
		if (state == null || p.recording() || !HudEffects.barsOn()) {
			return;
		}
		Object effect = rowEffect(i);
		float partialTick = HudData.partialTick();
		if (!HudEffects.barLayout(state, effect, 24, 24, partialTick)) {
			return;
		}
		float k = cell / 24.0F;
		barPart(p, x, y, k, 0, HudEffects.barColor(state, effect));
		barPart(p, x, y, k, 4, HudEffects.barBackground(state));
	}

	private static void barPart(HudPen p, int x, int y, float k, int at, int argb) {
		int x0 = Math.round(HudEffects.barRect(at) * k);
		int y0 = Math.round(HudEffects.barRect(at + 1) * k);
		int x1 = Math.round(HudEffects.barRect(at + 2) * k);
		int y1 = Math.round(HudEffects.barRect(at + 3) * k);
		p.fill(x + Math.min(x0, x1), y + Math.min(y0, y1), x + Math.max(x0, x1), y + Math.max(y0, y1), argb);
	}

	/** The name and level, then the time right-aligned in its column (so the colons line up). */
	private void text(HudPen p, int i, int x, int baseline, int nameColumn, int timeColumn, float tickrate) {
		HudStyle s = p.s;
		int at = x;
		if (nameColumn > 0) {
			if (placeholder) {
				p.text(PLACEHOLDER_NAME, HudFaces.VALUE, at, baseline, s.muted);
			} else {
				Entry entry = rows[order[i]];
				int ink = effectColors ? entry.color : s.text;
				int end = at;
				if (showNames) {
					end = p.text(entry.name, HudFaces.VALUE, end, baseline, ink);
				}
				if (showLevel && entry.leveled) {
					end += end > at ? p.width(SPACE, HudFaces.VALUE) : 0;
					p.text(entry.level, HudFaces.VALUE, end, baseline, ink);
				}
			}
			at += nameColumn + (timeColumn > 0 ? s.space : 0);
		}
		if (timeColumn <= 0) {
			return;
		}
		time(p, i, at + timeColumn, baseline, tickrate);
	}

	/** The time left, right-aligned at {@code right}. */
	private void time(HudPen p, int i, int right, int baseline, float tickrate) {
		if (placeholder) {
			p.text(HudField.DASH, HudFaces.VALUE, right - p.width(HudField.DASH, HudFaces.VALUE), baseline, p.s.muted);
			return;
		}
		Object effect = rowEffect(i);
		if (HudEffects.infinite(effect)) {
			p.text(INFINITE, HudFaces.VALUE, right - p.width(INFINITE, HudFaces.VALUE), baseline, timeColor);
		} else if (shorthand) {
			HudText text = rows[order[i]].shortTime(HudEffects.duration(effect), tickrate);
			p.text(text, HudFaces.VALUE, right - p.width(text, HudFaces.VALUE), baseline, timeColor);
		} else if (seconds) {
			int n = writeTime(effect, tickrate) - 1;
			int left = right - p.width(SECONDS, HudFaces.VALUE) - p.width(duration, n, HudFaces.VALUE);
			p.text(SECONDS, HudFaces.VALUE, p.digits(duration, n, HudFaces.VALUE, left, baseline, timeColor), baseline, timeColor);
		} else {
			int n = writeTime(effect, tickrate);
			p.digits(duration, n, HudFaces.VALUE, right - p.width(duration, n, HudFaces.VALUE), baseline, timeColor);
		}
	}

	/** On the icon: the time, small, along its bottom over a soft shade. */
	private void embedTime(HudPen p, int i, int x, int y, int cell, float tickrate) {
		if (!showDuration || cell <= 0) {
			return;
		}
		HudStyle s = p.s;
		int shade = Math.max(1, s.lineH * 3 / 4);
		p.fill(x, y + cell - shade, x + cell, y + cell, 0x8C000000);
		int w = timeWidth(p, i, tickrate);
		time(p, i, x + (cell + w) / 2, p.baseline(y + cell - s.lineH), tickrate);
	}

	/** An entry's opacity: a calm one-second pulse in its last ten seconds (never fully gone), else 1. */
	private float rowAlpha(Object effect) {
		if (!blink || Kit.reduced() || HudEffects.ambient(effect) || !HudEffects.endsWithin(effect, endingTicks)) {
			return 1.0F;
		}
		double phase = (System.nanoTime() % PULSE_NANOS) / (double) PULSE_NANOS;
		return (float) (0.45 + 0.55 * (0.5 + 0.5 * Math.cos(phase * 2.0 * Math.PI)));
	}

	@Override
	String emptyReason() {
		return "No active effects";
	}

	@Override
	@Nullable String noLabelReason(HudThemeId theme) {
		return "Effects are named, so there is no label";
	}

	@Override
	@Nullable String noAccentReason() {
		return "Effects has no labels to tint";
	}

	@Override
	int summary(ModuleState state, char[] out, long nowNanos) {
		Object player = HudData.player();
		if (player == null) {
			summarySize = -1;
			return -1;
		}
		int size = HudEffects.size();
		int identity = System.identityHashCode(player);
		if (size != summarySize || identity != summaryPlayer) {
			// Counting the shown ones iterates (an allocation), so only when the number of effects changed.
			summarySize = size;
			summaryPlayer = identity;
			summaryCount = HudEffects.read(NONE);
		}
		return HudFormat.effectsSummary(summaryCount, out);
	}

	/** What is kept per effect type: its sprite id, its name and its level at the level last seen. */
	private static final class Entry {
		final Object sprite;
		final HudText name = new HudText("");
		final HudText level = new HudText("");
		/** The level as a number ("2"), for the tile's corner. */
		final HudText number = new HudText("");
		/** The time left in short form ("1m 30s"), rewritten only when its whole seconds change. */
		private final HudText shortTime = new HudText("");
		private int shortSeconds = -1;
		/** The effect's own colour, brightened to read on a dark panel. */
		int color = 0xFFFFFFFF;
		String base = "";
		/** Above level I: a numeral to show. */
		boolean leveled;
		private int amplifier = Integer.MIN_VALUE;
		private @Nullable Object language;

		Entry(Object sprite) {
			this.sprite = sprite;
		}

		/** Rebuilds the name only when the level or the language changed. */
		void name(Object type, int amplifier, Object language) {
			if (amplifier == this.amplifier && language == this.language) {
				return;
			}
			this.amplifier = amplifier;
			this.language = language;
			color = com.flintclient.client.render.Palette.lerp(0xFF000000 | HudEffects.color(type), 0xFFFFFFFF, 0.3F);
			base = HudEffects.name(type);
			name.set(base);
			leveled = amplifier > 0;
			level.set(leveled ? level(amplifier + 1) : "");
			number.set(leveled ? Integer.toString(amplifier + 1) : "");
		}

		HudText shortTime(int ticks, float tickrate) {
			int seconds = (int) Math.ceil(ticks / Math.max(1.0F, tickrate));
			if (seconds != shortSeconds) {
				shortSeconds = seconds;
				shortTime.set(seconds >= 3600 ? seconds / 3600 + "h " + seconds / 60 % 60 + "m" : seconds >= 60 ? seconds / 60 + "m " + seconds % 60 + "s"
					: seconds + "s");
			}
			return shortTime;
		}

		/** Minecraft's own numeral for the level where it has one (I to X), Roman numerals beyond. */
		private static String level(int level) {
			String key = "enchantment.level." + level;
			return HudData.hasTranslation(key) ? HudData.translate(key, key) : HudFormat.roman(level);
		}
	}
}
