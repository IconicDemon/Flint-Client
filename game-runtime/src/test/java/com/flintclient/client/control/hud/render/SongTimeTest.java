package com.flintclient.client.control.hud.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Music Player's times, as music apps write them. */
class SongTimeTest {
	private static String time(long millis) {
		char[] out = new char[HudFormat.CHARS];
		return new String(out, 0, HudFormat.songTime(millis, out, 0));
	}

	@Test
	void minutesAndSecondsWithoutALeadingZero() {
		assertEquals("0:00", time(0));
		assertEquals("0:07", time(7_999), "whole seconds, rounded down");
		assertEquals("1:36", time(96_000));
		assertEquals("4:03", time(243_000));
		assertEquals("59:59", time(3_599_000));
		assertEquals("0:00", time(-5_000), "never negative");
	}

	@Test
	void hoursFromAnHourUp() {
		assertEquals("1:00:00", time(3_600_000));
		assertEquals("1:02:03", time(3_723_000));
		assertEquals("12:00:05", time(43_205_000));
	}

	@Test
	void writtenFromWhereItIsAsked() {
		char[] out = new char[HudFormat.CHARS];
		out[0] = '[';
		int end = HudFormat.songTime(65_000, out, 1);
		assertEquals("[1:05", new String(out, 0, end));
	}
}
