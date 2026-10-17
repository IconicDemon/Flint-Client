package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.feature.Features;
import com.flintclient.client.control.hud.TextOut;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.HudMods;
import com.flintclient.client.control.model.mods.PlayerMods;
import com.flintclient.client.control.model.mods.VisualMods;
import com.flintclient.client.control.platform.Mc263GameData;

/**
 * The one-line counters: each says only what its text is; {@link TextModuleView} draws it in the shared box look. Live
 * values come from the version layer ({@link Mc263GameData}) or Flint's own trackers ({@link Features}); settings
 * previews ({@link ModuleView#SAMPLE}) show fixed sample values.
 */
final class InfoViews {
	private InfoViews() {
	}

	/** Writes a word before or after a number per a "Prefix / Suffix / None" setting (0, 1, 2). */
	static void labelled(TextOut o, int mode, String prefix, double value, int decimals, String suffix) {
		if (mode == 0) {
			o.text(prefix).ch(' ');
		}
		o.fixed(value, decimals);
		if (mode == 1) {
			o.ch(' ').text(suffix);
		}
	}

	static final class MemoryView extends TextModuleView {
		private static final long MB = 1024L * 1024L;
		private final TextOut o = new TextOut();

		MemoryView(int surface) {
			super(HudMods.MemoryUsage.ID, surface, HudMods.MemoryUsage.STYLE);
		}

		@Override
		long refreshNanos() {
			return 250_000_000L;
		}

		@Override
		int compose(ModuleState state, int mode, long nowNanos, char[] out) {
			long max;
			long used;
			if (mode == SAMPLE) {
				max = 4096 * MB;
				used = 1638 * MB;
			} else {
				Runtime runtime = Runtime.getRuntime();
				max = runtime.maxMemory();
				used = runtime.totalMemory() - runtime.freeMemory();
			}
			o.start(out);
			if (state.bool(HudMods.MemoryUsage.SHOW_LABEL)) {
				o.text("MEM ");
			}
			int format = state.option(HudMods.MemoryUsage.FORMAT);
			if (format != 1) {
				o.integer(Math.round(100.0 * used / Math.max(1, max))).ch('%');
			}
			if (format == 2) {
				o.text(" · ");
			}
			if (format != 0) {
				o.fixed(used / (double) (1024 * MB), 1).text(" / ").fixed(max / (double) (1024 * MB), 1).text(" GB");
			}
			return o.length();
		}
	}

	static final class PlaytimeView extends TextModuleView {
		private final TextOut o = new TextOut();

		PlaytimeView(int surface) {
			super(HudMods.Playtime.ID, surface, HudMods.Playtime.STYLE);
		}

		@Override
		long refreshNanos() {
			return 200_000_000L;
		}

		@Override
		int compose(ModuleState state, int mode, long nowNanos, char[] out) {
			long ms;
			if (mode == SAMPLE) {
				ms = (9 * 60 + 34) * 1000L;
			} else {
				ms = state.option(HudMods.Playtime.TIME_TYPE) == 0 ? Features.sinceLaunchMs() : Features.inWorldMs();
				if (ms < 0) {
					return -1;
				}
			}
			long seconds = ms / 1000;
			boolean hours = state.bool(HudMods.Playtime.HOURS);
			boolean showSeconds = state.bool(HudMods.Playtime.SECONDS);
			long h = hours ? seconds / 3600 : 0;
			long m = hours ? seconds / 60 % 60 : seconds / 60;
			long s = seconds % 60;
			o.start(out);
			if (state.bool(HudMods.Playtime.SUFFIX)) {
				if (hours) {
					o.integer(h).text("h ");
				}
				o.two(m).ch('m');
				if (showSeconds) {
					o.ch(' ').two(s).ch('s');
				}
			} else {
				if (hours) {
					o.two(h).text(" : ");
				}
				o.two(m);
				if (showSeconds) {
					o.text(" : ").two(s);
				}
			}
			return o.length();
		}
	}

	static final class DayView extends TextModuleView {
		private final TextOut o = new TextOut();

		DayView(int surface) {
			super(HudMods.DayCounter.ID, surface, HudMods.DayCounter.STYLE);
		}

		@Override
		long refreshNanos() {
			return 500_000_000L;
		}

		@Override
		int compose(ModuleState state, int mode, long nowNanos, char[] out) {
			long ticks = mode == SAMPLE ? 12 * 24000L : Mc263GameData.dayTicks();
			if (ticks < 0) {
				return -1;
			}
			long day = ticks / 24000L;
			o.start(out);
			labelled(o, state.option(HudMods.DayCounter.LABEL), "Day", day, 0, day == 1 ? "day" : "days");
			return o.length();
		}
	}

	static final class AnglesView extends TextModuleView {
		private final TextOut o = new TextOut();

		AnglesView(int surface) {
			super(HudMods.ViewAngles.ID, surface, HudMods.ViewAngles.STYLE);
		}

		@Override
		int compose(ModuleState state, int mode, long nowNanos, char[] out) {
			float yaw = mode == SAMPLE ? 172.5F : Mc263GameData.yaw();
			float pitch = mode == SAMPLE ? 12.0F : Mc263GameData.pitch();
			if (Float.isNaN(yaw)) {
				return -1;
			}
			if (state.bool(HudMods.ViewAngles.WRAP)) {
				yaw = ((yaw % 360) + 540) % 360 - 180;
			}
			int decimals = state.option(HudMods.ViewAngles.DECIMALS);
			boolean labels = state.bool(HudMods.ViewAngles.LABELS);
			o.start(out);
			if (labels) {
				o.text("Yaw ");
			}
			o.fixed(yaw, decimals).text(labels ? "  Pitch " : " / ").fixed(pitch, decimals);
			return o.length();
		}
	}

	static final class HeightView extends TextModuleView {
		private final TextOut o = new TextOut();

		HeightView(int surface) {
			super(HudMods.HeightLimit.ID, surface, HudMods.HeightLimit.STYLE);
		}

		@Override
		long refreshNanos() {
			return 100_000_000L;
		}

		@Override
		int compose(ModuleState state, int mode, long nowNanos, char[] out) {
			double y = mode == SAMPLE ? 72 : Mc263GameData.y();
			if (Double.isNaN(y)) {
				return -1;
			}
			int top = mode == SAMPLE ? 319 : Mc263GameData.maxY();
			int bottom = mode == SAMPLE ? -64 : Mc263GameData.minY();
			int block = (int) Math.floor(y);
			int toTop = top - block;
			int toBottom = block - bottom;
			int show = state.option(HudMods.HeightLimit.SHOW);
			int nearest = show == 0 ? toTop : show == 1 ? toBottom : Math.min(toTop, toBottom);
			if (mode == HUD && state.bool(HudMods.HeightLimit.ONLY_NEAR) && nearest > state.integer(HudMods.HeightLimit.NEAR)) {
				return -1;
			}
			o.start(out);
			if (show != 1) {
				o.text("Top ").integer(Math.max(0, toTop));
			}
			if (show == 2) {
				o.text(" · ");
			}
			if (show != 0) {
				o.text("Bottom ").integer(Math.max(0, toBottom));
			}
			if (nearest <= 5) {
				colors[0] = 0xFF000000 | state.rgb(HudMods.HeightLimit.WARN_COLOR);
			}
			return o.length();
		}
	}

	static final class SaturationView extends TextModuleView {
		private final TextOut o = new TextOut();

		SaturationView(int surface) {
			super(HudMods.Saturation.ID, surface, HudMods.Saturation.STYLE);
		}

		@Override
		long refreshNanos() {
			return 100_000_000L;
		}

		@Override
		int compose(ModuleState state, int mode, long nowNanos, char[] out) {
			float saturation = mode == SAMPLE ? 5.0F : Mc263GameData.saturation();
			if (Float.isNaN(saturation) || mode == HUD && !state.bool(HudMods.Saturation.HUD)) {
				return -1;
			}
			o.start(out);
			if (state.bool(HudMods.Saturation.SHOW_LABEL)) {
				o.text("Sat ");
			}
			if (state.bool(HudMods.Saturation.SHOW_FOOD)) {
				o.integer(mode == SAMPLE ? 17 : Mc263GameData.food()).text(" · ");
			}
			o.fixed(saturation, 1);
			return o.length();
		}
	}

	static final class PotionCountView extends TextModuleView {
		private final TextOut o = new TextOut();

		PotionCountView(int surface) {
			super(HudMods.PotionCounter.ID, surface, HudMods.PotionCounter.STYLE);
		}

		@Override
		long refreshNanos() {
			return 100_000_000L;
		}

		@Override
		int compose(ModuleState state, int mode, long nowNanos, char[] out) {
			int count = mode == SAMPLE ? 12 : Mc263GameData.potions(state.option(HudMods.PotionCounter.COUNT));
			if (count < 0) {
				return -1;
			}
			o.start(out);
			labelled(o, state.option(HudMods.PotionCounter.LABEL), "Pots", count, 0, count == 1 ? "pot" : "pots");
			return o.length();
		}
	}

	static final class ReachView extends TextModuleView {
		private static final long SHOW_MS = 3000;
		private final TextOut o = new TextOut();

		ReachView(int surface) {
			super(HudMods.ReachDisplay.ID, surface, HudMods.ReachDisplay.STYLE);
		}

		@Override
		int compose(ModuleState state, int mode, long nowNanos, char[] out) {
			double reach = mode == SAMPLE ? 2.94 : Features.COMBAT.reach(Features.nowMs(), SHOW_MS);
			o.start(out);
			if (Double.isNaN(reach)) {
				if (!state.bool(HudMods.ReachDisplay.NO_HIT)) {
					return -1;
				}
				return o.text("No hit").length();
			}
			labelled(o, state.option(HudMods.ReachDisplay.LABEL), "Reach", reach, state.integer(HudMods.ReachDisplay.DIGITS), "blocks");
			return o.length();
		}
	}

	static final class ComboView extends TextModuleView {
		private final TextOut o = new TextOut();

		ComboView(int surface) {
			super(HudMods.ComboCounter.ID, surface, HudMods.ComboCounter.STYLE);
		}

		@Override
		int compose(ModuleState state, int mode, long nowNanos, char[] out) {
			int combo = mode == SAMPLE ? 4 : Features.COMBAT.combo(Features.nowMs());
			o.start(out);
			if (combo <= 0) {
				if (!state.bool(HudMods.ComboCounter.NO_COMBO)) {
					return -1;
				}
				return o.text("No Combo").length();
			}
			labelled(o, state.option(HudMods.ComboCounter.LABEL), "Combo", combo, 0, combo == 1 ? "hit" : "hits");
			return o.length();
		}
	}

	static final class SprintView extends TextModuleView {
		private final TextOut o = new TextOut();

		SprintView(int surface) {
			super(PlayerMods.ToggleSprint.ID, surface, PlayerMods.ToggleSprint.STYLE);
		}

		@Override
		int compose(ModuleState state, int mode, long nowNanos, char[] out) {
			if (state.bool(PlayerMods.ToggleSprint.HIDE_TEXT) && mode == HUD) {
				return -1;
			}
			o.start(out);
			if (mode == SAMPLE) {
				return o.text(state.text(PlayerMods.ToggleSprint.SPRINT_TOGGLED)).length();
			}
			if (!Mc263GameData.inWorld()) {
				return -1;
			}
			String text;
			if (Mc263GameData.flying()) {
				text = state.text(PlayerMods.ToggleSprint.FLYING);
			} else if (Features.MOVEMENT.holdSneak()) {
				text = state.text(PlayerMods.ToggleSprint.SNEAK_TOGGLED);
			} else if (Mc263GameData.sneaking()) {
				text = state.text(PlayerMods.ToggleSprint.SNEAK_HELD);
			} else if (Features.MOVEMENT.holdSprint()) {
				text = state.text(PlayerMods.ToggleSprint.SPRINT_TOGGLED);
			} else if (Features.MOVEMENT.sprintKeyDown()) {
				text = state.text(PlayerMods.ToggleSprint.SPRINT_HELD);
			} else if (Mc263GameData.sprinting()) {
				text = state.text(PlayerMods.ToggleSprint.SPRINT_VANILLA);
			} else {
				return -1;
			}
			return text.isEmpty() ? -1 : o.text(text).length();
		}
	}

	static final class HorseView extends TextModuleView {
		private static final int GOOD = 0xFF6BD66B;
		private static final int BAD = 0xFFE8605A;
		private final TextOut o = new TextOut();
		private final double[] stats = new double[4];

		HorseView(int surface) {
			super(HudMods.HorseStats.ID, surface, HudMods.HorseStats.STYLE);
		}

		@Override
		long refreshNanos() {
			return 200_000_000L;
		}

		@Override
		int compose(ModuleState state, int mode, long nowNanos, char[] out) {
			if (mode == SAMPLE) {
				stats[0] = 11.2;
				stats[1] = 3.6;
				stats[2] = 26;
				stats[3] = 26;
			} else if (!Mc263GameData.horse(state.bool(HudMods.HorseStats.WHEN_LOOKING), stats)) {
				return -1;
			}
			o.start(out);
			o.text("Speed ").fixed(stats[0], 1).text(" b/s").newline();
			o.text("Jump ").fixed(stats[1], 1).text(" blocks").newline();
			o.text("Health ").fixed(stats[2] / 2, 1).text(" / ").fixed(stats[3] / 2, 1).text(" \u2665");
			if (state.bool(HudMods.HorseStats.COLOR)) {
				colors[0] = stats[0] >= 11 ? GOOD : stats[0] <= 7 ? BAD : 0;
				colors[1] = stats[1] >= 4 ? GOOD : stats[1] <= 2.5 ? BAD : 0;
				colors[2] = stats[3] >= 25 ? GOOD : stats[3] <= 20 ? BAD : 0;
			}
			return o.length();
		}
	}

	static final class MomentumView extends TextModuleView {
		private final TextOut o = new TextOut();

		MomentumView(int surface) {
			super(VisualMods.Momentum.ID, surface, VisualMods.Momentum.STYLE);
		}

		@Override
		long refreshNanos() {
			return 100_000_000L;
		}

		@Override
		int compose(ModuleState state, int mode, long nowNanos, char[] out) {
			double speed = mode == SAMPLE ? 4.32 : Features.SPEED.speed(state.bool(VisualMods.Momentum.GROUND_ONLY),
				state.bool(VisualMods.Momentum.AVERAGE));
			if (Double.isNaN(speed)) {
				return -1;
			}
			o.start(out);
			labelled(o, state.option(VisualMods.Momentum.LABEL), "Speed", speed, state.integer(VisualMods.Momentum.DECIMALS), "b/s");
			return o.length();
		}
	}
}
