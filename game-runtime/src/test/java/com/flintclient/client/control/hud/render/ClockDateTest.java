package com.flintclient.client.control.hud.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The Time module's calendar maths agree with java.time on every day across four centuries. */
class ClockDateTest {
	@Test
	void civilDatesMatchJavaTime() {
		long[] out = new long[3];
		for (long day = LocalDate.of(1900, 1, 1).toEpochDay(); day <= LocalDate.of(2300, 12, 31).toEpochDay(); day++) {
			LocalDate expected = LocalDate.ofEpochDay(day);
			InfoViews.ClockView.civil(day, out);
			assertArrayEquals(new long[] {expected.getYear(), expected.getMonthValue(), expected.getDayOfMonth()}, out, expected.toString());
		}
	}
}
