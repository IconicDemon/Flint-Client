/*
 * Drawing adapted from: Player Reach Display by KikiJiji (MIT, META-INF/licenses/MIT-PlayerReachDisplay.txt), PotionCounter by
 * uku (MIT, META-INF/licenses/MIT-PotionCounter.txt), ptdisplay by JuiceyBeans (LGPL-3.0, META-INF/licenses/LGPL-3.0-ptdisplay.txt),
 * Pack Displayer by Rxeign (CC0-1.0) and the day counter datapack by justwilly (CC0-1.0).
 */
package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.HudMods;
import com.flintclient.client.control.model.mods.MoreHudMods;
import com.flintclient.client.platform.Frame;
import com.flintclient.client.platform.HudData;
import com.flintclient.client.platform.ItemPainter;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The HUD modules drawn exactly as their original mods draw them, with Minecraft's own GUI drawing (its font, shadows, items
 * and textures) in GUI units, scaled to the module's size in the HUD editor: Day Counter (the day counter datapack), Playtime
 * (ptdisplay), Reach Display (Player Reach Display), Potion Counter (uku's PotionCounter) and Pack Display (Pack Displayer).
 * Flint only places them, so they have no panel or HUD look of Flint's. Text is made only when what it says changes.
 */
final class OriginalViews {
	private OriginalViews() {
	}

	/** A view that lays itself out and draws in Minecraft's GUI units, as the original mod's HUD drawing does. */
	abstract static class VanillaView extends ModuleView {
		/** The content's size in GUI units at 1×, from {@link #layout} (0: nothing to show). */
		float guiW;
		float guiH;

		VanillaView(String moduleId, int surface) {
			super(moduleId, surface);
		}

		/** Sets {@link #guiW} and {@link #guiH} for what {@link #update} read. */
		abstract void layout(ModuleState state);

		/** Draws the content with its top-left corner at (0, 0), in GUI units. */
		abstract void paint(Frame frame, ModuleState state);

		@Override
		final void measure(HudPen p, ModuleState state) {
			layout(state);
			if (guiW <= 0 || guiH <= 0) {
				width = 0;
				height = 0;
				return;
			}
			width = Math.max(1, Math.round(guiW * p.s.u));
			height = Math.max(1, Math.round(guiH * p.s.u));
		}

		@Override
		final void draw(HudPen p, ModuleState state, int x, int y) {
			if (p.recording()) {
				// A miniature: the module's area as one soft block.
				p.fill(x, y, x + width, y + height, 0x99FFFFFF);
				return;
			}
			Frame frame = p.frame();
			frame.pushPlace(x, y, p.s.u);
			try {
				paint(frame, state);
			} finally {
				frame.pop();
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
		@Nullable String noLabelReason(HudThemeId theme) {
			return "Drawn as the original mod draws it";
		}

		@Override
		@Nullable String noPaddingReason() {
			return "Drawn as the original mod draws it";
		}

		@Override
		@Nullable String noAccentReason() {
			return "Drawn as the original mod draws it";
		}
	}

	// ---------- Day Counter ----------

	/** "DAY 12" in white with Minecraft's shadow, where the action bar shows (it makes way for a real action bar message). */
	static final class DayView extends VanillaView {
		private long shownDay = Long.MIN_VALUE;
		private String text = "";
		private boolean visible;

		DayView(int surface) {
			super(HudMods.DayCounter.ID, surface);
		}

		@Override
		void update(ModuleState state, int mode, long nowNanos) {
			long day;
			if (mode == HUD) {
				long ticks = HudData.dayTicks();
				visible = ticks >= 0 && !HudData.actionBarShowing();
				day = ticks < 0 ? 0 : ticks / 24000L;
			} else {
				long ticks = mode == PREVIEW ? HudData.dayTicks() : -1;
				visible = true;
				day = ticks < 0 ? 12 : ticks / 24000L;
			}
			if (day != shownDay) {
				shownDay = day;
				text = "DAY " + day;
			}
		}

		@Override
		void layout(ModuleState state) {
			guiW = visible ? ItemPainter.width(text) : 0;
			guiH = visible ? 9 : 0;
		}

		@Override
		void paint(Frame frame, ModuleState state) {
			ItemPainter.text(frame, text, 0, 0, 0xFFFFFFFF, true);
		}

		@Override
		String emptyReason() {
			return "The action bar is showing a message";
		}

		@Override
		int summary(ModuleState state, char[] out, long nowNanos) {
			long ticks = HudData.dayTicks();
			return ticks < 0 ? -1 : HudFormat.text("Day " + ticks / 24000L, out, 0);
		}
	}

	// ---------- Playtime ----------

	/** Your playtime statistic as ptdisplay shows it: "1:02:09" (or the statistics format), in its colour and shadow. */
	static final class PlaytimeView extends VanillaView {
		private int shownTicks = Integer.MIN_VALUE;
		private boolean shownStat;
		private String text = "";
		private boolean visible;

		PlaytimeView(int surface) {
			super(HudMods.Playtime.ID, surface);
		}

		@Override
		void update(ModuleState state, int mode, long nowNanos) {
			int ticks = mode == HUD || mode == PREVIEW ? HudData.playTimeTicks() : -1;
			visible = ticks >= 0 || mode != HUD;
			if (ticks < 0) {
				ticks = (62 * 60 + 9) * 20;
			}
			boolean stat = state.bool(HudMods.Playtime.STAT_FORMAT);
			if (ticks != shownTicks || stat != shownStat) {
				shownTicks = ticks;
				shownStat = stat;
				text = stat ? HudData.statTime(ticks) : clock(ticks);
			}
		}

		/** ptdisplay's own format: m:ss, h:mm:ss, d:hh:mm:ss, y:dd:hh:mm:ss. */
		static String clock(int time) {
			int s = time / 20;
			int m = s / 60;
			int h = m / 60;
			int d = h / 24;
			int y = d / 365;
			String zS = String.format(Locale.ROOT, "%02d", s % 60);
			String zM = String.format(Locale.ROOT, "%02d", m % 60);
			String zH = String.format(Locale.ROOT, "%02d", h % 24);
			String zD = String.format(Locale.ROOT, "%02d", d % 365);
			if (y >= 1) {
				return y + ":" + zD + ":" + zH + ":" + zM + ":" + zS;
			} else if (d >= 1) {
				return d + ":" + zH + ":" + zM + ":" + zS;
			} else {
				return h >= 1 ? h + ":" + zM + ":" + zS : m + ":" + zS;
			}
		}

		@Override
		void layout(ModuleState state) {
			guiW = visible ? ItemPainter.width(text) : 0;
			guiH = visible ? 9 : 0;
		}

		@Override
		void paint(Frame frame, ModuleState state) {
			ItemPainter.text(frame, text, 0, 0, 0xFF000000 | state.rgb(HudMods.Playtime.COLOR), state.bool(HudMods.Playtime.DROP_SHADOW));
		}

		@Override
		int summary(ModuleState state, char[] out, long nowNanos) {
			int ticks = HudData.playTimeTicks();
			return ticks < 0 ? -1 : HudFormat.text(clock(ticks), out, 0);
		}
	}

	// ---------- Reach Display ----------

	/** The last hit's distance as Player Reach Display shows it: "2.94 blocks" on a translucent box, its own colours and scale. */
	static final class ReachView extends VanillaView {
		private static final DecimalFormat FORMAT = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
		private double shownDistance = Double.NaN;
		private int shownMode = -1;
		private String text = "";
		private float scale = 1;
		private int padding;
		private int shadowExtra;
		private int rawW;
		private int rawH;

		ReachView(int surface) {
			super(HudMods.ReachDisplay.ID, surface);
		}

		@Override
		void update(ModuleState state, int mode, long nowNanos) {
			double distance = mode == HUD || mode == PREVIEW ? HudData.reachShown(state) : 2.94;
			int display = state.option(HudMods.ReachDisplay.DISPLAY_MODE);
			if (distance != shownDistance || display != shownMode) {
				shownDistance = distance;
				shownMode = display;
				String number = FORMAT.format(distance);
				text = switch (display) {
					case 0 -> number;
					case 2 -> number + " M";
					default -> number + " blocks";
				};
			}
		}

		@Override
		void layout(ModuleState state) {
			scale = Math.max(0.1F, state.integer(HudMods.ReachDisplay.SCALE) / 100.0F);
			padding = state.bool(HudMods.ReachDisplay.BACKGROUND) ? 2 : 0;
			shadowExtra = state.bool(HudMods.ReachDisplay.SHADOW) ? 1 : 0;
			rawW = ItemPainter.width(text) + padding * 2 + shadowExtra;
			rawH = 9 + padding * 2 + shadowExtra;
			guiW = (float) Math.ceil(rawW * scale);
			guiH = (float) Math.ceil(rawH * scale);
		}

		@Override
		void paint(Frame frame, ModuleState state) {
			int text = 0xFF000000 | state.rgb(HudMods.ReachDisplay.TEXT_COLOR);
			int shadow = 0xFF000000 | state.rgb(HudMods.ReachDisplay.SHADOW_COLOR);
			int background = alpha(state.integer(HudMods.ReachDisplay.BACKGROUND_OPACITY)) | state.rgb(HudMods.ReachDisplay.BACKGROUND_COLOR);
			if (state.bool(HudMods.ReachDisplay.DISTANCE_COLOR)) {
				int bands = state.integer(HudMods.ReachDisplay.BANDS);
				float from = 0;
				for (int i = 0; i < bands; i++) {
					// Each band starts above the one before it, as the original keeps them.
					from = i == 0 ? 0 : Math.max(state.decimal(HudMods.ReachDisplay.FROM[i]), from + 0.01F);
					if (!(shownDistance >= from - 1.0E-6)) {
						break;
					}
					text = 0xFF000000 | state.rgb(HudMods.ReachDisplay.BAND_TEXT_COLOR[i]);
					shadow = 0xFF000000 | state.rgb(HudMods.ReachDisplay.BAND_SHADOW_COLOR[i]);
					background = alpha(state.integer(HudMods.ReachDisplay.BAND_BACKGROUND_OPACITY[i])) | state.rgb(HudMods.ReachDisplay.BAND_BACKGROUND_COLOR[i]);
				}
			}
			frame.pushPlace(0, 0, scale);
			try {
				if (state.bool(HudMods.ReachDisplay.BACKGROUND)) {
					frame.fill(0, 0, rawW, rawH, background);
				}
				if (state.bool(HudMods.ReachDisplay.SHADOW)) {
					ItemPainter.text(frame, this.text, padding + 1, padding + 1, shadow, false);
				}
				ItemPainter.text(frame, this.text, padding, padding, text, false);
			} finally {
				frame.pop();
			}
		}

		private static int alpha(int percent) {
			return Math.round(255 * Math.clamp(percent, 0, 100) / 100.0F) << 24;
		}

		@Override
		int summary(ModuleState state, char[] out, long nowNanos) {
			return HudFormat.text(FORMAT.format(HudData.reachShown(state)), out, 0);
		}
	}

	// ---------- Potion Counter ----------

	/**
	 * Your splash potions as uku's PotionCounter shows them: each kind's potion with its count beside it, 18 units apart. On the
	 * right of the screen the list lines up on the right (counts to the left), and at the bottom it grows upwards.
	 */
	static final class PotionView extends VanillaView {
		/** Sample rows for previews, made the first time one is drawn (items don't exist before the game has loaded). */
		private static @Nullable List<HudLooks.PotionRow> sampleRows;
		private List<HudLooks.PotionRow> rows = List.of();
		private final String[] counts = new String[128];
		private boolean right;
		private boolean bottom;
		private long readAt;

		PotionView(int surface) {
			super(HudMods.PotionCounter.ID, surface);
		}

		private static List<HudLooks.PotionRow> sampleRows() {
			if (sampleRows == null) {
				sampleRows = List.of(
					new HudLooks.PotionRow(ItemPainter.splashPotion("strong_healing"), 32, false, true),
					new HudLooks.PotionRow(ItemPainter.splashPotion("long_strength"), 7, true, false),
					new HudLooks.PotionRow(ItemPainter.splashPotion("swiftness"), 1, false, false));
			}
			return sampleRows;
		}

		@Override
		void update(ModuleState state, int mode, long nowNanos) {
			Anchor anchor = state.placement().anchor();
			right = anchor.fx() == 1.0F || anchor.fx() == 0.5F && state.placement().x() > 0;
			bottom = anchor.fy() == 1.0F || anchor.fy() == 0.5F && state.placement().y() > 0;
			if (mode == SAMPLE || mode == PLACEHOLDER) {
				rows = sampleRows();
				return;
			}
			// The inventory is read twenty times a second (the original reads it every frame).
			if (nowNanos - readAt >= 50_000_000L || rows == sampleRows) {
				readAt = nowNanos;
				rows = HudData.potions(state);
				if (rows.isEmpty() && mode == PREVIEW) {
					rows = sampleRows();
				}
			}
		}

		private String count(int n) {
			if (n < 0 || n >= counts.length) {
				return String.valueOf(n);
			}
			String text = counts[n];
			if (text == null) {
				text = String.valueOf(n);
				counts[n] = text;
			}
			return text;
		}

		private static int upgrades(HudLooks.PotionRow row) {
			return (row.longer() ? 16 : 0) + (row.stronger() ? 16 : 0);
		}

		@Override
		void layout(ModuleState state) {
			if (rows.isEmpty()) {
				guiW = 0;
				guiH = 0;
				return;
			}
			int widest = 0;
			for (HudLooks.PotionRow row : rows) {
				// The potion, its upgrades, a gap of 2 and the count (its shadow one more).
				widest = Math.max(widest, 18 + upgrades(row) + ItemPainter.width(count(row.count())) + 1);
			}
			guiW = widest;
			guiH = 18 * (rows.size() - 1) + 16;
		}

		@Override
		void paint(Frame frame, ModuleState state) {
			int x = right ? (int) guiW : 0;
			int y = bottom ? (int) guiH : 0;
			for (int i = 0; i < rows.size(); i++) {
				HudLooks.PotionRow row = rows.get(i);
				int ly = y + 18 * i * (bottom ? -1 : 1);
				int top = bottom ? ly - 16 : ly;
				int textOffset = 0;
				if (row.longer()) {
					textOffset += 16;
					ItemPainter.item(frame, redstone(), right ? x - 16 - textOffset : x + textOffset, top);
				}
				if (row.stronger()) {
					textOffset += 16;
					ItemPainter.item(frame, glowstone(), right ? x - 16 - textOffset : x + textOffset, top);
				}
				String text = count(row.count());
				int textWidth = ItemPainter.width(text);
				ItemPainter.item(frame, row.icon(), right ? x - 16 : x, top);
				ItemPainter.text(frame, text, right ? x - 18 - textWidth - textOffset : x + 18 + textOffset, top + 9 / 2, -1, true);
			}
		}

		private static @Nullable Object redstone;
		private static @Nullable Object glowstone;

		/** The redstone drawn by longer potions, the glowstone by stronger ones (made once items exist). */
		private static Object redstone() {
			if (redstone == null) {
				redstone = ItemPainter.sample("redstone", 1);
			}
			return redstone;
		}

		private static Object glowstone() {
			if (glowstone == null) {
				glowstone = ItemPainter.sample("glowstone_dust", 1);
			}
			return glowstone;
		}

		@Override
		void forget() {
			rows = List.of();
		}

		@Override
		String emptyReason() {
			return "No splash potions";
		}
	}

	// ---------- Pack Display ----------

	/** Pack Displayer's box: the pack's icon (or a coloured tile with its initial) and its name, on a black translucent box. */
	static final class PackView extends VanillaView {
		private static final HudLooks.Pack SAMPLE_PACK = new HudLooks.Pack("Faithful 32x", "Faithful 32x", "", null);
		private HudLooks.@Nullable Pack pack;
		private HudLooks.@Nullable Pack laidOut;
		private int laidOutWidth = -1;
		private String name = "";
		private int textWidth;
		private int iconSize;
		private int hudW;
		private int hudH;

		PackView(int surface) {
			super(MoreHudMods.PackDisplay.ID, surface);
		}

		@Override
		void update(ModuleState state, int mode, long nowNanos) {
			pack = mode == HUD || mode == PREVIEW ? HudData.topPack() : SAMPLE_PACK;
			if (pack == null && mode != HUD) {
				pack = SAMPLE_PACK;
			}
		}

		@Override
		void layout(ModuleState state) {
			if (pack == null) {
				guiW = 0;
				guiH = 0;
				return;
			}
			int screenWidth = HudData.guiWidth();
			if (pack != laidOut || screenWidth != laidOutWidth) {
				laidOut = pack;
				laidOutWidth = screenWidth;
				// Names wider than half the screen end in "...".
				int maxTextWidth = screenWidth / 2;
				String raw = pack.title();
				int available = maxTextWidth - ItemPainter.width("...");
				name = ItemPainter.width(raw) > maxTextWidth && available > 0 ? ItemPainter.cut(raw, available) + "..." : raw;
				textWidth = ItemPainter.width(name);
			}
			float scale = state.integer(MoreHudMods.PackDisplay.SCALE) / 100.0F;
			int padding = 6;
			iconSize = state.bool(MoreHudMods.PackDisplay.ICON) ? Math.max(0, (int) (16.0F * scale)) : 0;
			hudW = textWidth + padding * 2 + iconSize + (iconSize > 0 ? padding : 0);
			hudH = Math.max(9, iconSize) + padding * 2;
			guiW = hudW;
			guiH = hudH;
		}

		@Override
		void paint(Frame frame, ModuleState state) {
			HudLooks.Pack look = pack;
			if (look == null) {
				return;
			}
			int padding = 6;
			int tx = iconSize > 0 ? padding + iconSize + padding : (hudW - textWidth) / 2;
			int ty = (hudH - 9) / 2;
			int alpha = state.integer(MoreHudMods.PackDisplay.OPACITY) * 255 / 100;
			frame.fill(0, 0, hudW, hudH, alpha << 24);
			if (iconSize > 0) {
				int iconX = padding;
				int iconY = (hudH - iconSize) / 2;
				if (look.icon() != null) {
					ItemPainter.texture(frame, look.icon(), iconX, iconY, iconSize, iconSize);
				} else {
					// No pack.png: a tile in the pack's own colour with its initial.
					int hash = look.id().hashCode();
					int color = 0xFF000000 | (hash >> 16 & 0xFF) << 16 | (hash >> 8 & 0xFF) << 8 | hash & 0xFF;
					frame.fill(iconX, iconY, iconX + iconSize, iconY + iconSize, color);
					String initial = look.id().isEmpty() ? "?" : look.id().substring(0, 1).toUpperCase(Locale.ROOT);
					ItemPainter.text(frame, initial, iconX + (iconSize - ItemPainter.width(initial)) / 2, iconY + (iconSize - 9) / 2, -1, false);
				}
			}
			ItemPainter.text(frame, name, tx, ty, -1, false);
		}

		@Override
		void forget() {
			pack = null;
			laidOut = null;
		}

		@Override
		String emptyReason() {
			return "No texture pack on";
		}
	}
}
