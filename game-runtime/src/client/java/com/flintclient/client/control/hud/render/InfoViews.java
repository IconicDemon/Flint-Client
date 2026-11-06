package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.feature.Features;
import com.flintclient.client.control.hud.TextOut;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.HudMods;
import com.flintclient.client.control.model.mods.PlayerMods;
import com.flintclient.client.control.model.mods.VisualMods;
import com.flintclient.client.platform.HudData;

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

	static final class AnglesView extends TextModuleView {
		private final TextOut o = new TextOut();

		AnglesView(int surface) {
			super(HudMods.ViewAngles.ID, surface, HudMods.ViewAngles.STYLE);
		}

		@Override
		int compose(ModuleState state, int mode, long nowNanos, char[] out) {
			float yaw = mode == SAMPLE ? 172.5F : HudData.yaw();
			float pitch = mode == SAMPLE ? 12.0F : HudData.pitch();
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
			double y = mode == SAMPLE ? 72 : HudData.y();
			if (Double.isNaN(y)) {
				return -1;
			}
			int top = mode == SAMPLE ? 319 : HudData.maxY();
			int bottom = mode == SAMPLE ? -64 : HudData.minY();
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
			float saturation = mode == SAMPLE ? 5.0F : HudData.saturation();
			if (Float.isNaN(saturation) || mode == HUD && !state.bool(HudMods.Saturation.HUD)) {
				return -1;
			}
			o.start(out);
			if (state.bool(HudMods.Saturation.SHOW_LABEL)) {
				o.text("Sat ");
			}
			if (state.bool(HudMods.Saturation.SHOW_FOOD)) {
				o.integer(mode == SAMPLE ? 17 : HudData.food()).text(" · ");
			}
			o.fixed(saturation, 1);
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
			if (!HudData.inWorld()) {
				return -1;
			}
			String text;
			if (HudData.flying()) {
				text = state.text(PlayerMods.ToggleSprint.FLYING);
			} else if (Features.MOVEMENT.holdSneak()) {
				text = state.text(PlayerMods.ToggleSprint.SNEAK_TOGGLED);
			} else if (HudData.sneaking()) {
				text = state.text(PlayerMods.ToggleSprint.SNEAK_HELD);
			} else if (Features.MOVEMENT.holdSprint()) {
				text = state.text(PlayerMods.ToggleSprint.SPRINT_TOGGLED);
			} else if (Features.MOVEMENT.sprintKeyDown()) {
				text = state.text(PlayerMods.ToggleSprint.SPRINT_HELD);
			} else if (HudData.sprinting()) {
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
			} else if (!HudData.horse(state.bool(HudMods.HorseStats.WHEN_LOOKING), stats)) {
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

	/** Time: the real time and date from the epoch (no allocation), and the world's time of day in its own colour. */
	static final class ClockView extends TextModuleView {
		private static final java.util.TimeZone ZONE = java.util.TimeZone.getDefault();
		private static final String[] DAYS = {"Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed"};
		private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
		private final TextOut o = new TextOut();

		ClockView(int surface) {
			super(HudMods.Clock.ID, surface, HudMods.Clock.STYLE);
		}

		@Override
		long refreshNanos() {
			return 250_000_000L;
		}

		@Override
		int compose(ModuleState state, int mode, long nowNanos, char[] out) {
			long now = mode == SAMPLE ? 1_792_669_500_000L : System.currentTimeMillis();
			long local = now + ZONE.getOffset(now);
			long days = Math.floorDiv(local, 86_400_000L);
			long ofDay = Math.floorMod(local, 86_400_000L) / 1000;
			boolean stacked = state.option(HudMods.Clock.ORIENTATION) == 0;
			o.start(out);
			int line = 0;
			if (state.bool(HudMods.Clock.SHOW_TIME)) {
				int format = state.option(HudMods.Clock.TIME_FORMAT);
				time(ofDay / 3600, ofDay / 60 % 60, format == 1 || format == 3 ? ofDay % 60 : -1, format >= 2);
				line++;
			}
			int dateFormat = state.option(HudMods.Clock.DATE_FORMAT);
			if (dateFormat != 0) {
				gap(stacked, line++);
				date(days, dateFormat);
			}
			if (state.bool(HudMods.Clock.SHOW_WORLD_TIME)) {
				long ticks = mode == SAMPLE ? 6000 : HudData.dayTicks();
				if (ticks >= 0) {
					long ofWorldDay = Math.floorMod(ticks, 24000L);
					long minutes = (ofWorldDay * 1440 / 24000 + 360) % 1440;
					gap(stacked, line);
					if (stacked) {
						colors[line] = 0xFF000000 | state.rgb(HudMods.Clock.WORLD_TIME_COLOR);
					}
					line++;
					time(minutes / 60, minutes % 60, -1, state.option(HudMods.Clock.WORLD_TIME_FORMAT) == 1);
				}
			}
			return line == 0 ? -1 : o.length();
		}

		private void gap(boolean stacked, int line) {
			if (line > 0) {
				if (stacked) {
					o.newline();
				} else {
					o.text("  ");
				}
			}
		}

		private void time(long h, long m, long seconds, boolean twelve) {
			if (twelve) {
				long shown = h % 12 == 0 ? 12 : h % 12;
				o.integer(shown).ch(':').two(m);
				if (seconds >= 0) {
					o.ch(':').two(seconds);
				}
				o.text(h < 12 ? " AM" : " PM");
			} else {
				o.two(h).ch(':').two(m);
				if (seconds >= 0) {
					o.ch(':').two(seconds);
				}
			}
		}

		private final long[] ymd = new long[3];

		/** Year, month (1-12) and day of {@code days} since 1970-01-01 into {@code out} (Hinnant's civil-from-days). */
		static void civil(long days, long[] out) {
			long z = days + 719_468;
			long era = Math.floorDiv(z, 146_097);
			long doe = z - era * 146_097;
			long yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
			long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
			long mp = (5 * doy + 2) / 153;
			out[2] = doy - (153 * mp + 2) / 5 + 1;
			out[1] = mp < 10 ? mp + 3 : mp - 9;
			out[0] = yoe + era * 400 + (out[1] <= 2 ? 1 : 0);
		}

		/** The date of {@code days} since 1970-01-01, in {@code format}. */
		private void date(long days, int format) {
			civil(days, ymd);
			long y = ymd[0];
			long m = ymd[1];
			long d = ymd[2];
			switch (format) {
				case 1 -> o.integer(y).ch('/').two(m).ch('/').two(d);
				case 2 -> o.two(d).ch('/').two(m).ch('/').integer(y);
				case 3 -> o.two(m).ch('/').two(d).ch('/').integer(y);
				default -> o.text(DAYS[(int) Math.floorMod(days, 7L)]).ch(' ').integer(d).ch(' ').text(MONTHS[(int) m - 1]);
			}
		}
	}

	/** TPS: the server's measured tick rate, one decimal; dashes until two time updates have arrived. */
	static final class TpsView extends TextModuleView {
		private static final int GOOD = 0xFF6FD68A;
		private static final int FAIR = 0xFFE8C45A;
		private static final int POOR = 0xFFE46A5E;
		private final TextOut o = new TextOut();

		TpsView(int surface) {
			super(HudMods.Tps.ID, surface, HudMods.Tps.STYLE);
		}

		@Override
		long refreshNanos() {
			return 500_000_000L;
		}

		@Override
		int compose(ModuleState state, int mode, long nowNanos, char[] out) {
			double tps = mode == SAMPLE ? 20.0 : com.flintclient.client.control.feature.Features.SERVER_TICKS.tps();
			boolean reversed = state.bool(HudMods.Tps.REVERSED);
			o.start(out);
			if (!reversed) {
				o.text("TPS ");
			}
			if (tps < 0) {
				o.text("—");
			} else {
				o.fixed(tps, 1);
				if (state.bool(HudMods.Tps.DYNAMIC_COLOR)) {
					colors[0] = tps >= 19.5 ? GOOD : tps >= 15 ? FAIR : POOR;
				}
			}
			if (reversed) {
				o.text(" TPS");
			}
			return o.length();
		}
	}

	/** Server Address: the server's address ("Singleplayer" in a local world), and the time spent on it. */
	static final class ServerAddressView extends TextModuleView {
		private final TextOut o = new TextOut();

		ServerAddressView(int surface) {
			super(HudMods.ServerAddress.ID, surface, HudMods.ServerAddress.STYLE);
		}

		@Override
		long refreshNanos() {
			return 500_000_000L;
		}

		@Override
		int compose(ModuleState state, int mode, long nowNanos, char[] out) {
			String address = mode == SAMPLE ? "play.example.net" : HudData.serverAddress();
			if (address == null) {
				return -1;
			}
			o.start(out).text(address);
			if (state.bool(HudMods.ServerAddress.SHOW_PLAY_TIME)) {
				long ms = mode == SAMPLE ? (42 * 60 + 7) * 1000L : com.flintclient.client.control.feature.Features.inWorldMs();
				if (ms >= 0) {
					long seconds = ms / 1000;
					boolean hours = state.bool(HudMods.ServerAddress.HOURS);
					o.text("  ");
					if (hours) {
						o.two(seconds / 3600).ch(':');
					}
					o.two(hours ? seconds / 60 % 60 : seconds / 60);
					if (state.bool(HudMods.ServerAddress.SECONDS)) {
						o.ch(':').two(seconds % 60);
					}
				}
			}
			return o.length();
		}
	}

	/** Stopwatch: the watch's time (minutes:seconds, hours once past one, tenths or milliseconds), dimmed while paused. */
	static final class StopwatchView extends TextModuleView {
		private final TextOut o = new TextOut();

		StopwatchView(int surface) {
			super(HudMods.Stopwatch.ID, surface, HudMods.Stopwatch.STYLE);
		}

		@Override
		int compose(ModuleState state, int mode, long nowNanos, char[] out) {
			com.flintclient.client.control.feature.StopwatchState watch = com.flintclient.client.control.feature.Features.STOPWATCH;
			long ms = mode == SAMPLE ? 83_456 : watch.elapsed(com.flintclient.client.control.feature.Features.nowMs());
			long seconds = ms / 1000;
			o.start(out);
			if (seconds >= 3600) {
				o.integer(seconds / 3600).ch(':');
			}
			o.two(seconds / 60 % 60).ch(':').two(seconds % 60);
			if (state.bool(HudMods.Stopwatch.MILLIS)) {
				long rest = ms % 1000;
				o.ch('.').ch((char) ('0' + rest / 100)).ch((char) ('0' + rest / 10 % 10)).ch((char) ('0' + rest % 10));
			}
			if (mode != SAMPLE && watch.paused()) {
				o.text("  paused");
			}
			return o.length();
		}
	}
}
