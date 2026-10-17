package com.flintclient.client.control.hud;

/**
 * Writes HUD text into a reused char buffer without allocating: a cursor over {@code out}, with the pieces counters are
 * made of (text, whole numbers, fixed decimals, two-digit clock fields). Pure Java, shared by every Minecraft version.
 */
public final class TextOut {
	private static final long[] TENS = {1, 10, 100, 1000};
	private char[] out = new char[0];
	private int at;

	/** Starts writing at {@code out[0]}. */
	public TextOut start(char[] buffer) {
		out = buffer;
		at = 0;
		return this;
	}

	/** Characters written so far. */
	public int length() {
		return at;
	}

	public TextOut text(String text) {
		int n = Math.min(text.length(), out.length - at);
		text.getChars(0, n, out, at);
		at += n;
		return this;
	}

	public TextOut ch(char ch) {
		if (at < out.length) {
			out[at++] = ch;
		}
		return this;
	}

	/** A line break (the next line of a multi-line counter). */
	public TextOut newline() {
		return ch('\n');
	}

	public TextOut integer(long value) {
		if (value < 0) {
			ch('-');
			value = value == Long.MIN_VALUE ? Long.MAX_VALUE : -value;
		}
		long divisor = 1;
		while (value / divisor >= 10) {
			divisor *= 10;
		}
		for (; divisor > 0; divisor /= 10) {
			ch((char) ('0' + (value / divisor) % 10));
		}
		return this;
	}

	/** {@code value} rounded half up to {@code decimals} (0 to 3) places; not-a-number writes a dash. */
	public TextOut fixed(double value, int decimals) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return ch('—');
		}
		int places = Math.clamp(decimals, 0, 3);
		long factor = TENS[places];
		long steps = Math.round(Math.clamp(value, -9.0E12, 9.0E12) * factor);
		if (steps < 0) {
			ch('-');
		}
		long magnitude = Math.abs(steps);
		integer(magnitude / factor);
		if (places > 0) {
			ch('.');
			long fraction = magnitude % factor;
			for (long d = factor / 10; d > 0; d /= 10) {
				ch((char) ('0' + (fraction / d) % 10));
			}
		}
		return this;
	}

	/** Two digits with a leading zero ("07"). */
	public TextOut two(long value) {
		long v = Math.max(0, value);
		if (v < 10) {
			ch('0');
		}
		return integer(v);
	}
}
