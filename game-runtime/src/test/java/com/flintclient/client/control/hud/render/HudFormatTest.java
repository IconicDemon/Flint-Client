package com.flintclient.client.control.hud.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.flintclient.client.control.model.CpsButtons;
import com.flintclient.client.control.model.KeystrokesLayout;
import java.lang.management.ManagementFactory;
import net.minecraft.util.StringUtil;
import org.junit.jupiter.api.Test;

class HudFormatTest {
	private static final char[] OUT = new char[HudFormat.CHARS];

	private static String integer(long value) {
		return new String(OUT, 0, HudFormat.integer(value, OUT));
	}

	private static String coordinate(double value, int decimals) {
		return new String(OUT, 0, HudFormat.coordinate(value, decimals, OUT));
	}

	private static String duration(int ticks, float tickrate) {
		return new String(OUT, 0, HudFormat.duration(ticks, tickrate, OUT));
	}

	@Test
	void wholeNumbersUseTheTypographicMinus() {
		assertEquals("0", integer(0));
		assertEquals("144", integer(144));
		assertEquals("−45", integer(-45));
		assertEquals("9223372036854775807", integer(Long.MAX_VALUE));
		assertTrue(integer(Long.MIN_VALUE).length() <= HudFormat.CHARS);
	}

	@Test
	void coordinatesRoundDownLikeBlockPositions() {
		assertEquals("12", coordinate(12.96, 0));
		assertEquals("12.9", coordinate(12.96, 1), "the decimal stays inside the block the whole number names");
		assertEquals("−1", coordinate(-0.3, 0), "x = -0.3 is in block -1");
		assertEquals("−0.1", coordinate(-0.04, 1));
		assertEquals("0", coordinate(0.0, 0));
		assertEquals("0.0", coordinate(0.0, 1));
		assertEquals("5.0", coordinate(5.0, 1));
		assertEquals("−29999984", coordinate(-29_999_983.5, 0));
		assertEquals("64", coordinate(64.0, 0));
		assertEquals("0", coordinate(Double.NaN, 0));
		assertTrue(coordinate(-1.0E300, 1).length() <= HudFormat.CHARS, "huge values are clamped, never overflow the buffer");
	}

	@Test
	void durationsMatchMinecraftsOwnFormat() {
		float[] tickrates = {20.0F, 10.0F, 1.0F, 33.3F, 100.0F};
		for (float tickrate : tickrates) {
			for (int ticks = 0; ticks < 200_000; ticks += 37) {
				assertEquals(StringUtil.formatTickDuration(ticks, tickrate), duration(ticks, tickrate), ticks + " ticks at " + tickrate);
			}
			for (int ticks : new int[] {72_000, 71_999, 1_000_000, Integer.MAX_VALUE}) {
				assertEquals(StringUtil.formatTickDuration(ticks, tickrate), duration(ticks, tickrate), ticks + " ticks at " + tickrate);
			}
		}
		assertEquals("01:30", duration(1800, 20.0F));
		assertEquals("01:00:00", duration(72_000, 20.0F));
	}

	private static String fixed(double value, int decimals) {
		return new String(OUT, 0, HudFormat.fixed(value, decimals, OUT));
	}

	private static String clock(int ticks, float tickrate) {
		return new String(OUT, 0, HudFormat.clock(ticks, tickrate, OUT));
	}

	private static String seconds(int ticks, float tickrate) {
		return new String(OUT, 0, HudFormat.seconds(ticks, tickrate, OUT));
	}

	@Test
	void frameRatesAndFrameTimesRoundHalfUp() {
		assertEquals("144", fixed(143.5, 0));
		assertEquals("143", fixed(143.49, 0));
		assertEquals("143.8", fixed(143.75, 1));
		assertEquals("60.0", fixed(60.0, 1));
		assertEquals("6.9", fixed(1000.0 / 144.0, 1), "the frame time at 144 FPS");
		assertEquals("16.7", fixed(1000.0 / 60.0, 1));
		assertEquals("0.0", fixed(0.04, 1));
		assertEquals("0", fixed(Double.NaN, 0), "no number: zero, never garbage");
		assertEquals("99999", fixed(1.0E9, 0), "kept within five digits");
		assertEquals("−2.50", fixed(-2.5, 2));
		assertEquals("1.000", fixed(0.9999, 3));
	}

	@Test
	void coordinatesWithTwoDecimalsStayInTheirBlock() {
		assertEquals("12.96", coordinate(12.969, 2));
		assertEquals("−0.05", coordinate(-0.041, 2));
		assertEquals("−1.00", coordinate(-1.0, 2));
		assertEquals("5", coordinate(5.999, 0));
		char[] out = new char[HudFormat.CHARS];
		out[0] = 'X';
		int end = HudFormat.coordinate(-3.5, 1, out, 1);
		assertEquals("X−3.5", new String(out, 0, end), "written from an offset, returning the end");
	}

	@Test
	void clockTimesHaveNoLeadingZero() {
		assertEquals("1:30", clock(1800, 20.0F));
		assertEquals("0:05", clock(100, 20.0F));
		assertEquals("0:00", clock(0, 20.0F));
		assertEquals("0:00", clock(-40, 20.0F), "never negative");
		assertEquals("59:59", clock(71_999, 20.0F));
		assertEquals("1:00:00", clock(72_000, 20.0F));
		assertEquals("1:05:00", clock(78_000, 20.0F));
		assertEquals("12:00:00", clock(864_000, 20.0F));
		assertEquals("0:45", clock(450, 10.0F), "at the world's own tick rate");
	}

	@Test
	void secondsAreWholeAndMarked() {
		assertEquals("90s", seconds(1800, 20.0F));
		assertEquals("0s", seconds(19, 20.0F));
		assertEquals("1s", seconds(20, 20.0F));
		assertEquals("3600s", seconds(72_000, 20.0F));
		assertEquals("0s", seconds(-5, 20.0F));
	}

	@Test
	void everyTimeFormatChangesOnTheSameTickAsMinecrafts() {
		float[] tickrates = {20.0F, 10.0F, 1.0F, 33.3F, 100.0F};
		for (float tickrate : tickrates) {
			for (int ticks = 0; ticks < 200_000; ticks += 37) {
				String minecraft = StringUtil.formatTickDuration(ticks, tickrate);
				String[] parts = minecraft.split(":");
				int total = 0;
				for (String part : parts) {
					total = total * 60 + Integer.parseInt(part);
				}
				assertEquals(total + "s", seconds(ticks, tickrate), ticks + " ticks at " + tickrate);
				String clock = clock(ticks, tickrate);
				assertEquals(minecraft.replaceFirst("^0(\\d)", "$1"), clock, ticks + " ticks at " + tickrate);
			}
		}
	}

	@Test
	void levelsAreRomanNumerals() {
		String[] expected = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII", "XIII", "XIV", "XV"};
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i], HudFormat.roman(i + 1));
		}
		assertEquals("XL", HudFormat.roman(40));
		assertEquals("XCIX", HudFormat.roman(99));
		assertEquals("CCLVI", HudFormat.roman(256));
		assertEquals("MMMCMXCIX", HudFormat.roman(3999));
		assertEquals("4000", HudFormat.roman(4000));
		assertEquals("0", HudFormat.roman(0));
	}

	@Test
	void yawMapsToTheCompassPointsMinecraftUses() {
		// Minecraft: yaw 0 faces south (+Z), 90 west (-X), ±180 north (-Z), -90 east (+X).
		assertEquals("South", HudFormat.POINTS[HudFormat.point(0)]);
		assertEquals("+Z", HudFormat.AXES[HudFormat.point(0)]);
		assertEquals("West", HudFormat.POINTS[HudFormat.point(90)]);
		assertEquals("−X", HudFormat.AXES[HudFormat.point(90)]);
		assertEquals("North", HudFormat.POINTS[HudFormat.point(180)]);
		assertEquals("North", HudFormat.POINTS[HudFormat.point(-180)]);
		assertEquals("−Z", HudFormat.AXES[HudFormat.point(-180)]);
		assertEquals("East", HudFormat.POINTS[HudFormat.point(-90)]);
		assertEquals("+X", HudFormat.AXES[HudFormat.point(-90)]);
		assertEquals("South-West", HudFormat.POINTS[HudFormat.point(45)]);
		assertEquals("−X +Z", HudFormat.AXES[HudFormat.point(45)]);
		assertEquals("North-East", HudFormat.POINTS[HudFormat.point(-135)]);
		assertEquals("+X −Z", HudFormat.AXES[HudFormat.point(-135)]);
		assertEquals("South-East", HudFormat.POINTS[HudFormat.point(-45)]);
		assertEquals("North-West", HudFormat.POINTS[HudFormat.point(135)]);
		assertEquals("South", HudFormat.POINTS[HudFormat.point(22.4F)], "each point covers 45 degrees around it");
		assertEquals("South-West", HudFormat.POINTS[HudFormat.point(22.6F)]);
		// The same rounding as Minecraft's four directions: floor(yaw / 90 + 0.5) & 3 picks S, W, N, E.
		for (float yaw = -180; yaw <= 180; yaw += 0.5F) {
			int four = (int) Math.floor(yaw / 90.0 + 0.5) & 3;
			int eight = HudFormat.point(yaw);
			if (eight % 2 == 0) {
				assertEquals(four, eight / 2, "cardinal point at yaw " + yaw);
			}
		}
	}

	@Test
	void shortPointsMatchTheNames() {
		for (int i = 0; i < 8; i++) {
			StringBuilder letters = new StringBuilder();
			for (String word : HudFormat.POINTS[i].split("-")) {
				letters.append(word.charAt(0));
			}
			assertEquals(letters.toString(), HudFormat.SHORT_POINTS[i]);
		}
		assertEquals("N", HudFormat.SHORT_POINTS[HudFormat.point(180)]);
		assertEquals("SE", HudFormat.SHORT_POINTS[HudFormat.point(-45)]);
	}

	@Test
	void degreesAreWholeAndNeverMinus180() {
		assertEquals(172, HudFormat.degrees(171.6F));
		assertEquals(-90, HudFormat.degrees(-90.2F));
		assertEquals(180, HudFormat.degrees(-180.0F));
		assertEquals(180, HudFormat.degrees(179.7F));
		assertEquals(0, HudFormat.degrees(-0.4F));
	}

	@Test
	void signalBarsFollowMinecraftsLatencyLevels() {
		assertEquals(0, HudFormat.signalBars(-1), "unknown latency fills nothing");
		assertEquals(4, HudFormat.signalBars(0));
		assertEquals(4, HudFormat.signalBars(149));
		assertEquals(3, HudFormat.signalBars(150));
		assertEquals(3, HudFormat.signalBars(299));
		assertEquals(2, HudFormat.signalBars(300));
		assertEquals(2, HudFormat.signalBars(599));
		assertEquals(1, HudFormat.signalBars(600));
		assertEquals(1, HudFormat.signalBars(5000), "a working connection always shows a bar");
	}

	@Test
	void latencyStepsAreMinecraftsPlayerListThresholds() {
		assertEquals(-1, HudFormat.latencyBand(-1));
		assertEquals(HudFormat.BAND_GOOD, HudFormat.latencyBand(0));
		assertEquals(HudFormat.BAND_GOOD, HudFormat.latencyBand(149));
		assertEquals(HudFormat.BAND_FAIR, HudFormat.latencyBand(150));
		assertEquals(HudFormat.BAND_FAIR, HudFormat.latencyBand(299));
		assertEquals(HudFormat.BAND_SLOW, HudFormat.latencyBand(300));
		assertEquals(HudFormat.BAND_SLOW, HudFormat.latencyBand(599));
		assertEquals(HudFormat.BAND_BAD, HudFormat.latencyBand(600));
		for (int ms = 0; ms < 2000; ms += 7) {
			assertEquals(4 - HudFormat.latencyBand(ms), HudFormat.signalBars(ms), "the colour step and the bars agree at " + ms + " ms");
		}
	}

	@Test
	void lowDurabilityFollowsTheThreshold() {
		assertFalse(HudFormat.lowDurability(80, 100, 20), "exactly at the threshold is not yet low");
		assertTrue(HudFormat.lowDurability(81, 100, 20));
		assertFalse(HudFormat.lowDurability(94, 100, 5));
		assertTrue(HudFormat.lowDurability(96, 100, 5));
		assertTrue(HudFormat.lowDurability(51, 100, 50));
		assertTrue(HudFormat.lowDurability(1561, 1561, 5), "broken is always low");
		assertFalse(HudFormat.lowDurability(10, 0, 50), "an item without durability never is");
		for (int damage = 0; damage <= 363; damage++) {
			assertEquals(HudFormat.lowDurability(damage, 363), HudFormat.lowDurability(damage, 363, 10), "the default is 10 %");
		}
	}

	@Test
	void durabilityBarsUseTheRealDamage() {
		assertEquals(32, HudFormat.barFill(0, 363, 32), "unused: full");
		assertEquals(31, HudFormat.barFill(1, 363, 32), "one use gone shows");
		assertEquals(16, HudFormat.barFill(50, 100, 32));
		assertEquals(1, HudFormat.barFill(362, 363, 32), "one use left still shows");
		assertEquals(0, HudFormat.barFill(363, 363, 32));
		assertEquals(32, HudFormat.barFill(5, 0, 32), "no durability: full");
		assertEquals(0, HudFormat.barFill(0, 100, 0));
		for (int damage = 0; damage < 400; damage++) {
			assertTrue(HudFormat.barFill(damage, 400, 48) >= HudFormat.barFill(damage + 1, 400, 48), "never grows with damage");
		}
	}

	@Test
	void summariesReadNaturally() {
		assertEquals("144 FPS", summary(HudFormat.fpsSummary(144, OUT)));
		assertEquals("32 ms", summary(HudFormat.pingSummary(32, OUT)));
		assertEquals("8 · 3 CPS", summary(HudFormat.cpsSummary(CpsButtons.BOTH, 8, 3, OUT)));
		assertEquals("8 CPS", summary(HudFormat.cpsSummary(CpsButtons.LEFT, 8, 3, OUT)));
		assertEquals("3 CPS", summary(HudFormat.cpsSummary(CpsButtons.RIGHT, 8, 3, OUT)));
		assertEquals("11 CPS", summary(HudFormat.cpsSummary(CpsButtons.COMBINED, 8, 3, OUT)));
		char[] keys = "WASD".toCharArray();
		assertEquals("WASD", summary(HudFormat.keystrokesSummary(keys, 4, KeystrokesLayout.KEYS, OUT)));
		assertEquals("WASD · mouse", summary(HudFormat.keystrokesSummary(keys, 4, KeystrokesLayout.KEYS_MOUSE, OUT)));
		assertEquals("WASD · mouse · jump", summary(HudFormat.keystrokesSummary(keys, 4, KeystrokesLayout.KEYS_MOUSE_JUMP, OUT)));
		assertEquals("Keys · mouse", summary(HudFormat.keystrokesSummary(keys, 0, KeystrokesLayout.KEYS_MOUSE, OUT)));
		assertEquals("−4 · 72 · 3", summary(HudFormat.coordinatesSummary(-3.2, 72.9, 3.99, OUT)));
		assertEquals("4 pieces · lowest 87%", summary(HudFormat.armorSummary(4, 87, OUT)));
		assertEquals("1 piece", summary(HudFormat.armorSummary(1, -1, OUT)));
		assertEquals("No armor", summary(HudFormat.armorSummary(0, -1, OUT)));
		assertEquals("3 effects", summary(HudFormat.effectsSummary(3, OUT)));
		assertEquals("1 effect", summary(HudFormat.effectsSummary(1, OUT)));
		assertEquals("No effects", summary(HudFormat.effectsSummary(0, OUT)));
		int n = HudFormat.fpsSummary(60, OUT);
		assertTrue(HudFormat.same("60 FPS", OUT, n));
		assertFalse(HudFormat.same("61 FPS", OUT, n));
		assertFalse(HudFormat.same("60 FPS ", OUT, n));
	}

	private static String summary(int length) {
		return new String(OUT, 0, length);
	}

	@Test
	void durabilityPercentAndWarning() {
		assertEquals(100, HudFormat.percentLeft(0, 363));
		assertEquals(99, HudFormat.percentLeft(1, 363), "never rounds up to a full 100 %");
		assertEquals(0, HudFormat.percentLeft(363, 363));
		assertEquals(50, HudFormat.percentLeft(50, 100));
		assertEquals(100, HudFormat.percentLeft(0, 0), "an item without durability is whole");
		assertFalse(HudFormat.lowDurability(90, 100), "exactly 10 % left is not yet low");
		assertTrue(HudFormat.lowDurability(91, 100));
		assertTrue(HudFormat.lowDurability(363, 363));
		assertFalse(HudFormat.lowDurability(0, 0));
	}

	@Test
	void formattingEveryFrameAllocatesNothing() {
		assumeTrue(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean);
		com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());
		char[] out = new char[HudFormat.CHARS];
		long sink = run(out, 5_000);
		long before = threads.getCurrentThreadAllocatedBytes();
		sink += run(out, 50_000);
		long allocated = threads.getCurrentThreadAllocatedBytes() - before;
		assertTrue(sink > 0);
		assertTrue(allocated < 2048, allocated + " bytes for 50,000 frames of HUD numbers");
	}

	private static long run(char[] out, int frames) {
		long sink = 0;
		for (int i = 0; i < frames; i++) {
			sink += HudFormat.integer(i * 7L - 3000, out);
			sink += HudFormat.coordinate(i * 0.37 - 900, i & 1, out);
			sink += HudFormat.duration(i * 3, 20.0F, out);
			sink += HudFormat.point(i * 1.7F - 180) + HudFormat.degrees(i * 0.3F - 180) + HudFormat.signalBars(i % 900);
			sink += HudFormat.percentLeft(i % 300, 300) + (HudFormat.lowDurability(i % 300, 300) ? 1 : 0);
			sink += HudFormat.fixed(i * 0.173, i & 1, out) + HudFormat.fixed(1000.0 / (1 + i % 400), 1, out);
			sink += HudFormat.clock(i * 3, 20.0F, out) + HudFormat.seconds(i * 3, 20.0F, out);
			sink += HudFormat.latencyBand(i % 900) + HudFormat.barFill(i % 300, 300, 32) + (HudFormat.lowDurability(i % 300, 300, 25) ? 1 : 0);
			sink += HudFormat.coordinate(i * 0.37 - 900, 2, out, 0);
			sink += HudFormat.cpsSummary(CpsButtons.BOTH, i % 20, i % 7, out) + HudFormat.fpsSummary(i % 400, out);
			sink += HudFormat.coordinatesSummary(i * 0.5, 64, -i * 0.25, out) + HudFormat.armorSummary(i % 5, i % 101, out);
			sink += HudFormat.effectsSummary(i % 4, out) + HudFormat.keystrokesSummary(out, 0, KeystrokesLayout.KEYS_MOUSE_JUMP, out);
			sink += HudFormat.same("144 FPS", out, 7) ? 1 : 0;
		}
		return sink;
	}
}
