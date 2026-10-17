package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.model.CpsButtons;
import com.flintclient.client.control.model.KeystrokesLayout;

/**
 * The HUD's numbers as text, written into reused {@code char} buffers so values that change every frame
 * allocate nothing: whole numbers, fixed decimals (frame rate, frame time), coordinates, effect durations (clock or
 * seconds) and degrees, the small rules behind them (compass points, signal bars, latency steps, durability and its
 * bar), and the one-line summaries the Control Center's module list shows. Pure, so it is unit-tested without
 * Minecraft.
 */
public final class HudFormat {
	/** U+2212, the typographic minus: as wide as a digit in Inter, and in Minecraft's own font too. */
	public static final char MINUS = '−';
	/** Room for any value written here: a sign, 19 digits, a point and a decimal, or a long duration. */
	public static final int CHARS = 24;
	/** The eight compass points, from yaw 0 (south) turning clockwise as Minecraft counts yaw. */
	public static final String[] POINTS = {"South", "South-West", "West", "North-West", "North", "North-East", "East", "South-East"};
	/** {@link #POINTS} as letters ("N", "NE"), for the compact direction and the coordinates' facing. */
	public static final String[] SHORT_POINTS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
	/** Which way the coordinates grow facing each of {@link #POINTS} (yaw 0 faces +Z, 90 faces −X). */
	public static final String[] AXES = {"+Z", "−X +Z", "−X", "−X −Z", "−Z", "+X −Z", "+X", "+X +Z"};
	/** The ping module's latency steps ({@link #latencyBand}): Minecraft's player-list thresholds. */
	public static final int BAND_GOOD = 0;
	public static final int BAND_FAIR = 1;
	public static final int BAND_SLOW = 2;
	public static final int BAND_BAD = 3;
	private static final long[] TENS = {1, 10, 100, 1000};
	/** The largest value {@link #fixed} writes; beyond it the HUD shows this. */
	private static final double FIXED_MAX = 99_999.0;

	private HudFormat() {
	}

	/** Writes {@code value} at {@code out[0..]}; returns the length. */
	public static int integer(long value, char[] out) {
		return integer(value, out, 0);
	}

	/** Writes {@code value} from {@code out[at]}; returns the index after it. */
	public static int integer(long value, char[] out, int at) {
		int n = at;
		long magnitude = value;
		if (value < 0) {
			out[n++] = MINUS;
			// Long.MIN_VALUE has no positive twin; the HUD never shows anything near it.
			magnitude = value == Long.MIN_VALUE ? Long.MAX_VALUE : -value;
		}
		long divisor = 1;
		while (magnitude / divisor >= 10) {
			divisor *= 10;
		}
		for (; divisor > 0; divisor /= 10) {
			out[n++] = (char) ('0' + (magnitude / divisor) % 10);
		}
		return n;
	}

	/**
	 * {@code value} rounded to {@code decimals} decimals (0 to 3), half up: the frame rate ("144", "143.8") and the
	 * frame time ("6.9"). Not a number writes 0; values are kept within ±{@value #FIXED_MAX}.
	 */
	public static int fixed(double value, int decimals, char[] out) {
		int places = Math.clamp(decimals, 0, 3);
		long factor = TENS[places];
		double clamped = Double.isNaN(value) ? 0 : Math.clamp(value, -FIXED_MAX, FIXED_MAX);
		long steps = Math.round(clamped * factor);
		int n = 0;
		if (steps < 0) {
			out[n++] = MINUS;
		}
		long magnitude = Math.abs(steps);
		n = integer(magnitude / factor, out, n);
		if (places > 0) {
			out[n++] = '.';
			long fraction = magnitude % factor;
			for (long d = factor / 10; d > 0; d /= 10) {
				out[n++] = (char) ('0' + (fraction / d) % 10);
			}
		}
		return n;
	}

	/**
	 * A coordinate with {@code decimals} decimals (0 to 3), rounded down like Minecraft's block positions, so
	 * {@code -0.3} is block {@code −1} and every decimal shown stays inside the block the whole number names.
	 */
	public static int coordinate(double value, int decimals, char[] out) {
		return coordinate(value, decimals, out, 0);
	}

	/** {@link #coordinate(double, int, char[])} written from {@code out[at]}; returns the index after it. */
	public static int coordinate(double value, int decimals, char[] out, int at) {
		int places = Math.clamp(decimals, 0, 3);
		long factor = TENS[places];
		double scaled = Math.floor(value * factor);
		long steps = Double.isNaN(scaled) ? 0 : (long) Math.clamp(scaled, -1.0E15, 1.0E15);
		int n = at;
		long magnitude = Math.abs(steps);
		if (steps < 0) {
			out[n++] = MINUS;
		}
		n = integer(magnitude / factor, out, n);
		if (places > 0) {
			out[n++] = '.';
			long fraction = magnitude % factor;
			for (long d = factor / 10; d > 0; d /= 10) {
				out[n++] = (char) ('0' + (fraction / d) % 10);
			}
		}
		return n;
	}

	/**
	 * An effect's remaining time as Minecraft shows it ({@code StringUtil.formatTickDuration}): minutes and
	 * seconds as {@code 01:30}, with hours in front from an hour up ({@code 01:05:00}). {@code tickrate} is the
	 * world's ticks per second.
	 */
	public static int duration(int ticks, float tickrate, char[] out) {
		// The same float division and floor as Minecraft, so the shown second changes on the same tick.
		float exact = ticks / tickrate;
		int seconds = (int) exact;
		if (exact < seconds) {
			seconds--;
		}
		int minutes = seconds / 60;
		seconds %= 60;
		int hours = minutes / 60;
		minutes %= 60;
		int n = 0;
		if (hours > 0) {
			n = twoDigits(hours, out, n);
			out[n++] = ':';
		}
		n = twoDigits(minutes, out, n);
		out[n++] = ':';
		return twoDigits(seconds, out, n);
	}

	/** At least two digits ({@code %02d}): negative numbers keep their sign before the padding. */
	private static int twoDigits(int value, char[] out, int at) {
		int n = at;
		int magnitude = value;
		if (value < 0) {
			out[n++] = '-';
			magnitude = -value;
		}
		if (magnitude < 10) {
			out[n++] = '0';
		}
		return integer(magnitude, out, n);
	}

	/**
	 * An effect's time left as a clock without a leading zero: {@code 1:30}, {@code 0:05}, and from an hour up
	 * {@code 1:05:00}. Seconds are counted exactly as Minecraft counts them ({@link #duration}), so the shown
	 * second changes on the same tick as its own timer.
	 */
	public static int clock(int ticks, float tickrate, char[] out) {
		int seconds = wholeSeconds(ticks, tickrate);
		int minutes = seconds / 60;
		seconds %= 60;
		int hours = minutes / 60;
		minutes %= 60;
		int n = 0;
		if (hours > 0) {
			n = integer(hours, out, n);
			out[n++] = ':';
			n = twoDigits(minutes, out, n);
		} else {
			n = integer(minutes, out, n);
		}
		out[n++] = ':';
		return twoDigits(seconds, out, n);
	}

	/** An effect's time left in whole seconds: {@code 90s}. */
	public static int seconds(int ticks, float tickrate, char[] out) {
		int n = integer(wholeSeconds(ticks, tickrate), out, 0);
		out[n++] = 's';
		return n;
	}

	/** Whole seconds in {@code ticks} at {@code tickrate}, never negative, with Minecraft's float division and floor. */
	static int wholeSeconds(int ticks, float tickrate) {
		float exact = Math.max(0, ticks) / tickrate;
		int seconds = (int) exact;
		if (exact < seconds) {
			seconds--;
		}
		return Math.max(0, seconds);
	}

	/** {@code n} in Roman numerals (1 to 3999), or in digits outside that range. Built once per effect level. */
	public static String roman(int n) {
		if (n < 1 || n > 3999) {
			return Integer.toString(n);
		}
		int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
		String[] symbols = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
		StringBuilder out = new StringBuilder();
		int rest = n;
		for (int i = 0; i < values.length; i++) {
			while (rest >= values[i]) {
				out.append(symbols[i]);
				rest -= values[i];
			}
		}
		return out.toString();
	}

	/**
	 * Which of {@link #POINTS} a yaw faces: the nearest eighth, the same rounding Minecraft uses for its
	 * four directions ({@code Direction.fromYRot}: {@code floor(yaw / 90 + 0.5)}).
	 */
	public static int point(float yaw) {
		return (int) Math.floor(yaw / 45.0 + 0.5) & 7;
	}

	/** Degrees shown for a yaw already wrapped to −180..180: the nearest whole degree, with −180 shown as 180. */
	public static int degrees(float wrappedYaw) {
		int whole = Math.round(wrappedYaw);
		return whole == -180 ? 180 : whole;
	}

	/**
	 * How many of the ping module's four bars are filled: Minecraft's own player-list thresholds (150, 300 and
	 * 600 ms) with its lowest two levels as one, so a connection always shows at least one bar.
	 */
	public static int signalBars(int latencyMillis) {
		if (latencyMillis < 0) {
			return 0;
		}
		if (latencyMillis < 150) {
			return 4;
		}
		if (latencyMillis < 300) {
			return 3;
		}
		return latencyMillis < 600 ? 2 : 1;
	}

	/**
	 * Which latency step a ping is in, at Minecraft's player-list thresholds: under 150 ms {@link #BAND_GOOD}, under
	 * 300 {@link #BAND_FAIR}, under 600 {@link #BAND_SLOW}, else {@link #BAND_BAD}; -1 when unknown.
	 */
	public static int latencyBand(int latencyMillis) {
		if (latencyMillis < 0) {
			return -1;
		}
		if (latencyMillis < 150) {
			return BAND_GOOD;
		}
		if (latencyMillis < 300) {
			return BAND_FAIR;
		}
		return latencyMillis < 600 ? BAND_SLOW : BAND_BAD;
	}

	/** Remaining durability in whole percent, rounded down so 99.6 % never shows as a full 100 %. */
	public static int percentLeft(int damage, int maxDamage) {
		if (maxDamage <= 0) {
			return 100;
		}
		int left = Math.clamp(maxDamage - damage, 0, maxDamage);
		return (int) ((long) left * 100 / maxDamage);
	}

	/** Below a tenth of the durability left: the armour warning's default threshold. */
	public static boolean lowDurability(int damage, int maxDamage) {
		return lowDurability(damage, maxDamage, 10);
	}

	/** Below {@code thresholdPercent} % of the durability left (exactly at it is not yet low). */
	public static boolean lowDurability(int damage, int maxDamage, int thresholdPercent) {
		if (maxDamage <= 0) {
			return false;
		}
		long left = Math.clamp(maxDamage - damage, 0, maxDamage);
		return left * 100 < (long) maxDamage * thresholdPercent;
	}

	/**
	 * How many of a durability bar's {@code lengthPx} pixels are filled: the durability left, rounded as
	 * Minecraft rounds its own item bar, but never empty while any use is left (and never full once one is used).
	 */
	public static int barFill(int damage, int maxDamage, int lengthPx) {
		if (lengthPx <= 0) {
			return 0;
		}
		if (maxDamage <= 0) {
			return lengthPx;
		}
		int left = Math.clamp(maxDamage - damage, 0, maxDamage);
		if (left == 0) {
			return 0;
		}
		int fill = Math.round(lengthPx - (float) (maxDamage - left) * lengthPx / maxDamage);
		if (left < maxDamage) {
			fill = Math.min(fill, lengthPx - 1);
		}
		return Math.clamp(fill, 1, lengthPx);
	}

	// ---------- the module list's summaries ----------

	/** Copies {@code text} to {@code out} from {@code at}; returns the index after it. */
	public static int text(String text, char[] out, int at) {
		text.getChars(0, text.length(), out, at);
		return at + text.length();
	}

	/** "144 FPS". */
	public static int fpsSummary(int fps, char[] out) {
		return text(" FPS", out, integer(fps, out, 0));
	}

	/** "32 ms". */
	public static int pingSummary(int latencyMillis, char[] out) {
		return text(" ms", out, integer(latencyMillis, out, 0));
	}

	/** "8 · 3 CPS", "8 CPS" (left), "3 CPS" (right) or "11 CPS" (combined). */
	public static int cpsSummary(CpsButtons buttons, int left, int right, char[] out) {
		int n = switch (buttons) {
			case LEFT -> integer(left, out, 0);
			case RIGHT -> integer(right, out, 0);
			case COMBINED -> integer((long) left + right, out, 0);
			case BOTH -> integer(right, out, text(" · ", out, integer(left, out, 0)));
		};
		return text(" CPS", out, n);
	}

	/** "WASD · mouse · jump", from the movement keys' labels ({@code keys}, or "Keys" when empty) and the layout. */
	public static int keystrokesSummary(char[] keys, int keysLength, KeystrokesLayout layout, char[] out) {
		int n;
		if (keysLength > 0) {
			System.arraycopy(keys, 0, out, 0, keysLength);
			n = keysLength;
		} else {
			n = text("Keys", out, 0);
		}
		if (layout.mouse()) {
			n = text(" · mouse", out, n);
		}
		if (layout.jump()) {
			n = text(" · jump", out, n);
		}
		return n;
	}

	/** "−4 · 72 · 3": the block the player stands in. */
	public static int coordinatesSummary(double x, double y, double z, char[] out) {
		int n = coordinate(x, 0, out, 0);
		n = coordinate(y, 0, out, text(" · ", out, n));
		return coordinate(z, 0, out, text(" · ", out, n));
	}

	/** "4 pieces · lowest 87%", "1 piece", or "No armor"; {@code lowestPercent} below 0 when nothing worn has durability. */
	public static int armorSummary(int pieces, int lowestPercent, char[] out) {
		if (pieces <= 0) {
			return text("No armor", out, 0);
		}
		int n = text(pieces == 1 ? " piece" : " pieces", out, integer(pieces, out, 0));
		if (lowestPercent >= 0) {
			n = integer(lowestPercent, out, text(" · lowest ", out, n));
			out[n++] = '%';
		}
		return n;
	}

	/** "3 effects", "1 effect" or "No effects". */
	public static int effectsSummary(int effects, char[] out) {
		if (effects <= 0) {
			return text("No effects", out, 0);
		}
		return text(effects == 1 ? " effect" : " effects", out, integer(effects, out, 0));
	}

	/** Whether {@code text} holds exactly {@code chars[0..length)}, so a summary String is made only when its text changed. */
	public static boolean same(String text, char[] chars, int length) {
		if (text.length() != length) {
			return false;
		}
		for (int i = 0; i < length; i++) {
			if (text.charAt(i) != chars[i]) {
				return false;
			}
		}
		return true;
	}
}
