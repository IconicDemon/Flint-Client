package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.model.DurationFormat;
import com.flintclient.client.control.model.EffectSort;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.ModuleSettings;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.Orientation;
import com.flintclient.client.control.platform.Mc263HudData;
import com.flintclient.client.ui.kit.Kit;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import org.jspecify.annotations.Nullable;

/**
 * Effects: one entry per active effect, stacked (the default) or in a row: its icon (Minecraft's own effect
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
	/** Entries start pulsing this many ticks before the end (Minecraft's own icons flash from 10 s). */
	private static final int ENDING_TICKS = 200;
	private static final long PULSE_NANOS = 1_000_000_000L;
	private static final HudText INFINITE = new HudText("∞");
	private static final HudText SPACE = new HudText(" ");
	/** The seconds format's unit: tabular digits cover numbers and punctuation only, so the "s" is drawn as text. */
	private static final HudText SECONDS = new HudText("s");
	private static final HudText PLACEHOLDER_NAME = HudField.DASH;
	private static @Nullable Language infiniteLanguage;

	private final Map<MobEffect, Entry> entries = new IdentityHashMap<>();
	private MobEffectInstance[] effects = new MobEffectInstance[8];
	private Entry[] rows = new Entry[8];
	private int[] remaining = new int[8];
	private int[] tieBreak = new int[8];
	private String[] names = new String[8];
	private int[] order = new int[8];
	private int count;
	private long lastRefresh;
	/** Who the list was read from, to notice a new player (respawn, another world) at once; weak, see above. */
	private @Nullable WeakReference<LocalPlayer> readFrom;
	private @Nullable EffectSort sortedBy;
	/** On the HUD while a screen lists the effects itself. */
	private boolean hidden;
	private boolean placeholder;
	private boolean horizontal;
	private boolean showIcons;
	private boolean showNames;
	private boolean showLevel;
	private boolean showDuration;
	private boolean seconds;
	private boolean blink;
	private int spacing;
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
	void update(ModuleState state, int mode, long nowNanos) {
		horizontal = state.choice(ModuleSettings.Effects.LAYOUT) == Orientation.HORIZONTAL;
		showNames = state.bool(ModuleSettings.Effects.SHOW_NAMES);
		showLevel = state.bool(ModuleSettings.Effects.SHOW_LEVEL);
		showDuration = state.bool(ModuleSettings.Effects.SHOW_DURATION);
		// With every part off the icons stay, so the module never draws an empty panel.
		showIcons = state.bool(ModuleSettings.Effects.SHOW_ICONS) || !showNames && !showLevel && !showDuration;
		seconds = state.choice(ModuleSettings.Effects.DURATION_FORMAT) == DurationFormat.SECONDS;
		spacing = state.integer(ModuleSettings.Effects.SPACING);
		blink = state.bool(ModuleSettings.Effects.BLINK_ENDING);
		EffectSort sort = state.choice(ModuleSettings.Effects.SORT);
		hidden = false;
		if (mode == PLACEHOLDER) {
			// The editor's outline size: a row of dashes. The live list is kept, so it isn't read again next frame.
			placeholder = true;
			return;
		}
		LocalPlayer player = Mc263HudData.player();
		if (player == null) {
			forget();
			placeholder = mode == PREVIEW;
			return;
		}
		LocalPlayer last = readFrom == null ? null : readFrom.get();
		if (player != last) {
			// A new player object (joined, respawned): one small allocation per player, never per frame.
			readFrom = new WeakReference<>(player);
			lastRefresh = nowNanos;
			refresh(player, sort);
		} else if (nowNanos - lastRefresh >= REFRESH_NANOS || sort != sortedBy) {
			lastRefresh = nowNanos;
			refresh(player, sort);
		}
		placeholder = count == 0 && mode == PREVIEW;
		hidden = mode == HUD && Mc263HudData.screenShowsEffects();
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
	private void refresh(LocalPlayer player, EffectSort sort) {
		Language language = Language.getInstance();
		if (language != infiniteLanguage) {
			infiniteLanguage = language;
			INFINITE.set(language.getOrDefault("effect.duration.infinite", "∞"));
		}
		int n = 0;
		for (MobEffectInstance effect : player.getActiveEffects()) {
			if (!effect.showIcon()) {
				continue;
			}
			if (n == effects.length) {
				grow();
			}
			effects[n] = effect;
			remaining[n] = effect.isInfiniteDuration() ? EffectOrder.INFINITE : effect.getDuration();
			tieBreak[n] = BuiltInRegistries.MOB_EFFECT.getId(effect.getEffect().value());
			Entry entry = entry(effect, language);
			rows[n] = entry;
			names[n] = entry.base;
			n++;
		}
		for (int i = n; i < count; i++) {
			effects[i] = null;
			rows[i] = null;
			names[i] = null;
		}
		count = n;
		sortedBy = sort;
		if (sort == EffectSort.NAME) {
			EffectOrder.sortByName(names, tieBreak, n, order);
		} else {
			EffectOrder.sort(remaining, tieBreak, n, order);
		}
	}

	private Entry entry(MobEffectInstance effect, Language language) {
		MobEffect type = effect.getEffect().value();
		Entry entry = entries.get(type);
		if (entry == null) {
			entry = new Entry(Hud.getMobEffectSprite(effect.getEffect()));
			entries.put(type, entry);
		}
		entry.name(type, effect.getAmplifier(), language);
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
	}

	private int rowsShown() {
		return hidden ? 0 : placeholder ? 1 : count;
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
		iconPx = showIcons ? Math.max(6, s.px(9)) : 0;
		int rowH = Math.max(iconPx, s.lineH);
		float tickrate = Mc263HudData.tickrate();
		if (horizontal) {
			int total = 0;
			for (int i = 0; i < shown; i++) {
				total += entryWidth(p, i, nameWidth(p, i), timeWidth(p, i, tickrate));
			}
			width = total + (shown - 1) * entryGap(p);
			height = rowH;
			return;
		}
		nameColumn = 0;
		timeColumn = 0;
		for (int i = 0; i < shown; i++) {
			nameColumn = Math.max(nameColumn, nameWidth(p, i));
			timeColumn = Math.max(timeColumn, timeWidth(p, i, tickrate));
		}
		width = entryWidth(p, -1, nameColumn, timeColumn);
		height = shown * rowH + (shown - 1) * s.px(spacing);
	}

	/** Icon, name part and time with the spaces between them; {@code row} is unused (the widths are given). */
	private int entryWidth(HudPen p, int row, int name, int time) {
		HudStyle s = p.s;
		int w = showIcons ? iconPx : 0;
		if (name > 0) {
			w += (w > 0 ? s.space : 0) + name;
		}
		if (time > 0) {
			w += (w > 0 ? s.space : 0) + time;
		}
		return w;
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
		MobEffectInstance effect = rowEffect(row);
		if (effect.isInfiniteDuration()) {
			return p.width(INFINITE, HudFaces.VALUE);
		}
		int n = writeTime(effect, tickrate);
		return seconds ? p.width(duration, n - 1, HudFaces.VALUE) + p.width(SECONDS, HudFaces.VALUE) : p.width(duration, n, HudFaces.VALUE);
	}

	private int writeTime(MobEffectInstance effect, float tickrate) {
		return seconds ? HudFormat.seconds(effect.getDuration(), tickrate, duration) : HudFormat.clock(effect.getDuration(), tickrate, duration);
	}

	/** The effect shown on sorted row {@code row}. */
	private MobEffectInstance rowEffect(int row) {
		return effects[order[row]];
	}

	@Override
	void draw(HudPen p, ModuleState state, int x, int y) {
		HudStyle s = p.s;
		int shown = rowsShown();
		int rowH = Math.max(iconPx, s.lineH);
		float tickrate = Mc263HudData.tickrate();
		float before = p.canvas().opacity();
		int left = x;
		int top = y;
		for (int i = 0; i < shown; i++) {
			float alpha = placeholder ? 1.0F : rowAlpha(rowEffect(i));
			p.canvas().setOpacity(before * alpha);
			int name = horizontal ? nameWidth(p, i) : nameColumn;
			int time = horizontal ? timeWidth(p, i, tickrate) : timeColumn;
			drawEntry(p, i, left, top, rowH, name, time, tickrate);
			if (horizontal) {
				left += entryWidth(p, i, name, time) + entryGap(p);
			} else {
				top += rowH + s.px(spacing);
			}
		}
		p.canvas().setOpacity(before);
	}

	/** One entry from ({@code x}, {@code top}): icon, name and level, then the time right-aligned in its column. */
	private void drawEntry(HudPen p, int i, int x, int top, int rowH, int nameColumn, int timeColumn, float tickrate) {
		HudStyle s = p.s;
		int at = x;
		if (showIcons) {
			int iconY = top + (rowH - iconPx) / 2;
			if (placeholder) {
				p.ring(at, iconY, iconPx, iconPx, s.classic() ? 0 : s.px(1.5F), Math.max(1, s.unitPx / 3), s.muted);
			} else {
				p.sprite(rows[order[i]].sprite, at, iconY, iconPx, 0xFFFFFFFF);
			}
			at += iconPx;
		}
		int baseline = p.baseline(top + (rowH - s.lineH) / 2);
		if (nameColumn > 0) {
			at += at > x ? s.space : 0;
			if (placeholder) {
				p.text(PLACEHOLDER_NAME, HudFaces.VALUE, at, baseline, s.muted);
			} else {
				Entry entry = rows[order[i]];
				int end = at;
				if (showNames) {
					end = p.text(entry.name, HudFaces.VALUE, end, baseline, s.text);
				}
				if (showLevel && entry.leveled) {
					end += end > at ? p.width(SPACE, HudFaces.VALUE) : 0;
					p.text(entry.level, HudFaces.VALUE, end, baseline, s.text);
				}
			}
			at += nameColumn;
		}
		if (timeColumn <= 0) {
			return;
		}
		at += at > x ? s.space : 0;
		// Times right-aligned in their column, so the colons line up.
		int right = at + timeColumn;
		if (placeholder) {
			p.text(HudField.DASH, HudFaces.VALUE, right - p.width(HudField.DASH, HudFaces.VALUE), baseline, s.muted);
			return;
		}
		MobEffectInstance effect = rowEffect(i);
		if (effect.isInfiniteDuration()) {
			p.text(INFINITE, HudFaces.VALUE, right - p.width(INFINITE, HudFaces.VALUE), baseline, s.muted);
		} else if (seconds) {
			int n = writeTime(effect, tickrate) - 1;
			int left = right - p.width(SECONDS, HudFaces.VALUE) - p.width(duration, n, HudFaces.VALUE);
			p.text(SECONDS, HudFaces.VALUE, p.digits(duration, n, HudFaces.VALUE, left, baseline, s.muted), baseline, s.muted);
		} else {
			int n = writeTime(effect, tickrate);
			p.digits(duration, n, HudFaces.VALUE, right - p.width(duration, n, HudFaces.VALUE), baseline, s.muted);
		}
	}

	/** An entry's opacity: a calm one-second pulse in its last ten seconds (never fully gone), else 1. */
	private float rowAlpha(MobEffectInstance effect) {
		if (!blink || Kit.reduced() || effect.isAmbient() || !effect.endsWithin(ENDING_TICKS)) {
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
		LocalPlayer player = Mc263HudData.player();
		if (player == null) {
			summarySize = -1;
			return -1;
		}
		int size = player.getActiveEffects().size();
		int identity = System.identityHashCode(player);
		if (size != summarySize || identity != summaryPlayer) {
			// Counting the shown ones iterates (an allocation), so only when the number of effects changed.
			summarySize = size;
			summaryPlayer = identity;
			int shown = 0;
			for (MobEffectInstance effect : player.getActiveEffects()) {
				if (effect.showIcon()) {
					shown++;
				}
			}
			summaryCount = shown;
		}
		return HudFormat.effectsSummary(summaryCount, out);
	}

	/** What is kept per effect type: its sprite id, its name and its level at the level last seen. */
	private static final class Entry {
		final Identifier sprite;
		final HudText name = new HudText("");
		final HudText level = new HudText("");
		String base = "";
		/** Above level I: a numeral to show. */
		boolean leveled;
		private int amplifier = Integer.MIN_VALUE;
		private @Nullable Language language;

		Entry(Identifier sprite) {
			this.sprite = sprite;
		}

		/** Rebuilds the name only when the level or the language changed. */
		void name(MobEffect type, int amplifier, Language language) {
			if (amplifier == this.amplifier && language == this.language) {
				return;
			}
			this.amplifier = amplifier;
			this.language = language;
			base = type.getDisplayName().getString();
			name.set(base);
			leveled = amplifier > 0;
			level.set(leveled ? level(amplifier + 1, language) : "");
		}

		/** Minecraft's own numeral for the level where it has one (I to X), Roman numerals beyond. */
		private static String level(int level, Language language) {
			String key = "enchantment.level." + level;
			return language.has(key) ? language.getOrDefault(key) : HudFormat.roman(level);
		}
	}
}
