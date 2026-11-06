package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Realistic's cloth: it hangs at Feather's angles, streams out as the wearer runs and settles back without bouncing, moves the
 * same at any frame rate and on every frame (drawn between its steps), and stays whole however wildly it is pushed.
 */
class CapeClothTest {
	private static final int TIP = CapeCloth.POINTS - 1;
	private static final long SECOND = 1_000_000_000L;
	/** A sprint: blocks a tick. */
	private static final float SPRINT = 0.28F;
	/** Any clock start: the frame clock is System.nanoTime, which can be anything. */
	private static final long START = 123_456_789_000L;

	private static CapeCloth.Motion still() {
		return new CapeCloth.Motion().still();
	}

	private static CapeCloth.Motion forward(float blocksPerTick) {
		CapeCloth.Motion m = still();
		m.forward = blocksPerTick;
		return m;
	}

	/** The drawn tip's angle from straight down, towards behind (degrees). */
	private static double lift(CapeCloth cloth, int chain) {
		float a = cloth.alpha();
		return Math.toDegrees(Math.atan2(cloth.away(chain, TIP, a), cloth.down(chain, TIP, a)));
	}

	/** Frames at {@code fps} from {@code from} for {@code seconds}; answers the clock after the last frame. */
	private static long frames(CapeCloth cloth, CapeCloth.Motion m, long from, double seconds, double fps) {
		long end = from + (long) (seconds * SECOND);
		long at = from;
		for (int i = 1; at < end; i++) {
			at = Math.min(end, from + Math.round(i * SECOND / fps));
			cloth.advance(at, m);
		}
		return end;
	}

	/** A cloth with its clock started at {@link #START}. */
	private static CapeCloth started() {
		CapeCloth cloth = new CapeCloth();
		cloth.advance(START, still());
		return cloth;
	}

	@Test
	void atRestItHangsAtFeathersAngle() {
		CapeCloth cloth = started();
		frames(cloth, still(), START, 2, 60);
		for (int chain = 0; chain < CapeCloth.CHAINS; chain++) {
			assertEquals(CapeCloth.REST, lift(cloth, chain), 0.5, "chain " + chain);
			assertEquals(16, Math.hypot(cloth.away(chain, TIP, 1), cloth.down(chain, TIP, 1)), 0.05, "a pixel a link, straight");
			assertEquals(0, cloth.side(chain, TIP, 1), 1e-4);
		}
	}

	@Test
	void crouchingHangsAtTheCrouchAngle() {
		CapeCloth cloth = started();
		CapeCloth.Motion crouch = still();
		crouch.angle = CapeCloth.CROUCH;
		frames(cloth, crouch, START, 2, 144);
		assertEquals(CapeCloth.CROUCH, lift(cloth, CapeCloth.MIDDLE), 0.5);
	}

	@Test
	void runningStreamsItOutBehindAndItSettlesWithoutBouncing() {
		CapeCloth cloth = started();
		long at = frames(cloth, forward(SPRINT), START, 2, 144);
		double run = lift(cloth, CapeCloth.MIDDLE);
		assertTrue(run > 45, "sprinting lifts it well out behind: " + run);
		assertTrue(lift(cloth, CapeCloth.EDGE) > run, "the softer edges trail further than the middle");
		// Stopped: it falls back to hanging, never further than hanging, and never back up (no bounce).
		double before = run;
		for (int f = 1; f <= 288; f++) {
			at += SECOND / 144;
			cloth.advance(at, still());
			double now = lift(cloth, CapeCloth.MIDDLE);
			assertTrue(now <= before + 1e-3, "it only falls: " + before + " then " + now + " at frame " + f);
			assertTrue(now >= CapeCloth.REST - 0.5, "no overshoot past hanging: " + now);
			before = now;
		}
		assertEquals(CapeCloth.REST, before, 1, "settled within two seconds");
	}

	@Test
	void moreGravityLiftsItLessAndSettlesItSooner() {
		CapeCloth light = started();
		CapeCloth heavy = started();
		CapeCloth.Motion lightRun = forward(SPRINT);
		lightRun.gravity = 5 / 25F;
		CapeCloth.Motion heavyRun = forward(SPRINT);
		heavyRun.gravity = 32 / 25F;
		long at = frames(light, lightRun, START, 2, 60);
		frames(heavy, heavyRun, START, 2, 60);
		assertTrue(lift(heavy, CapeCloth.MIDDLE) < lift(light, CapeCloth.MIDDLE));
		CapeCloth.Motion lightStill = still();
		lightStill.gravity = lightRun.gravity;
		CapeCloth.Motion heavyStill = still();
		heavyStill.gravity = heavyRun.gravity;
		frames(light, lightStill, at, 0.3, 60);
		frames(heavy, heavyStill, at, 0.3, 60);
		assertTrue(lift(heavy, CapeCloth.MIDDLE) - CapeCloth.REST < lift(light, CapeCloth.MIDDLE) - CapeCloth.REST);
	}

	@Test
	void itMovesTheSameAtAnyFrameRate() {
		// Running from the start: every step sees the same motion, so the drawing at a moment is the same at any frame rate.
		double[] times = {0.1, 0.25, 0.5, 0.75, 1.0, 1.5};
		double[] fpsList = {24, 30, 60, 75, 120, 144, 165, 240, 360, 1000};
		float[][] reference = null;
		for (double fps : fpsList) {
			CapeCloth cloth = started();
			float[][] seen = new float[times.length][];
			long at = START;
			for (int k = 0; k < times.length; k++) {
				at = frames(cloth, forward(SPRINT), at, times[k] - (k == 0 ? 0 : times[k - 1]), fps);
				float a = cloth.alpha();
				seen[k] = new float[] {cloth.away(CapeCloth.MIDDLE, TIP, a), cloth.down(CapeCloth.MIDDLE, TIP, a), cloth.away(CapeCloth.EDGE, 8, a)};
			}
			if (reference == null) {
				reference = seen;
				continue;
			}
			for (int k = 0; k < times.length; k++) {
				for (int j = 0; j < 3; j++) {
					assertEquals(reference[k][j], seen[k][j], 2e-3, fps + " FPS at " + times[k] + " s");
				}
			}
		}
	}

	@Test
	void aMotionThatChangesMidwayStaysWithinAFrameOfTheSame() {
		// Start running, stop, crouch: each step takes the motion of the frame it is stepped in, so slower frame rates may see a
		// change up to a frame late, and no more.
		double[] fpsList = {30, 60, 144, 240, 1000};
		float[] reference = null;
		for (double fps : fpsList) {
			CapeCloth cloth = started();
			long at = frames(cloth, still(), START, 0.5, fps);
			at = frames(cloth, forward(SPRINT), at, 1, fps);
			CapeCloth.Motion crouch = still();
			crouch.angle = CapeCloth.CROUCH;
			at = frames(cloth, crouch, at, 0.4, fps);
			float a = cloth.alpha();
			float[] seen = {cloth.away(CapeCloth.MIDDLE, TIP, a), cloth.down(CapeCloth.MIDDLE, TIP, a)};
			if (reference == null) {
				reference = seen;
				continue;
			}
			assertEquals(reference[0], seen[0], 0.5, fps + " FPS");
			assertEquals(reference[1], seen[1], 0.5, fps + " FPS");
		}
	}

	@Test
	void itIsDrawnBetweenItsStepsSoItMovesOnEveryFrame() {
		// 1000 FPS while it starts to stream out: a sixtieth of a second holds 16 or 17 frames, and each one moves the tip a little.
		CapeCloth cloth = started();
		long at = START;
		float lastAway = cloth.away(CapeCloth.MIDDLE, TIP, cloth.alpha());
		float lastDown = cloth.down(CapeCloth.MIDDLE, TIP, cloth.alpha());
		double biggest = 0;
		int held = 0;
		for (int f = 0; f < 400; f++) {
			at += SECOND / 1000;
			cloth.advance(at, forward(SPRINT));
			float a = cloth.alpha();
			float away = cloth.away(CapeCloth.MIDDLE, TIP, a);
			float down = cloth.down(CapeCloth.MIDDLE, TIP, a);
			double moved = Math.hypot(away - lastAway, down - lastDown);
			biggest = Math.max(biggest, moved);
			if (f > 20 && moved < 1e-6) {
				held++;
			}
			lastAway = away;
			lastDown = down;
		}
		assertEquals(0, held, "no frame shows the same cape twice while it moves");
		assertTrue(biggest < 0.12, "no jump between frames, a millisecond apart: " + biggest);
	}

	@Test
	void alphaIsWhereTheFrameStandsBetweenSteps() {
		CapeCloth cloth = started();
		cloth.advance(START + CapeCloth.STEP_NANOS / 4, still());
		assertEquals(0.25, cloth.alpha(), 1e-3);
		cloth.advance(START + CapeCloth.STEP_NANOS + CapeCloth.STEP_NANOS / 2, still());
		assertEquals(0.5, cloth.alpha(), 1e-3);
	}

	@Test
	void aLongGapCatchesUpAQuarterSecondAtMost() {
		CapeCloth gap = started();
		gap.advance(START + 10 * SECOND, forward(SPRINT));
		CapeCloth fifteen = new CapeCloth();
		fifteen.settle(forward(SPRINT), 15);
		assertEquals(fifteen.away(CapeCloth.MIDDLE, TIP, 1), gap.away(CapeCloth.MIDDLE, TIP, 1), 1e-5);
		assertEquals(fifteen.down(CapeCloth.MIDDLE, TIP, 1), gap.down(CapeCloth.MIDDLE, TIP, 1), 1e-5);
	}

	@Test
	void heldWhilePausedAndNothingCaughtUpAfter() {
		CapeCloth cloth = started();
		long at = frames(cloth, forward(SPRINT), START, 0.5, 60);
		float away = cloth.away(CapeCloth.MIDDLE, TIP, cloth.alpha());
		cloth.hold(at + 5 * SECOND);
		cloth.advance(at + 5 * SECOND + 1_000_000, forward(SPRINT));
		assertEquals(away, cloth.away(CapeCloth.MIDDLE, TIP, cloth.alpha()), 0.1, "a millisecond after the pause, not five seconds");
	}

	@Test
	void strafingSwingsItTheOtherWayAndStrafeZeroNot() {
		CapeCloth cloth = started();
		CapeCloth.Motion right = still();
		right.right = 0.2F;
		frames(cloth, right, START, 1, 60);
		assertTrue(cloth.side(CapeCloth.MIDDLE, TIP, 1) < -1, "moving right, it trails left: " + cloth.side(CapeCloth.MIDDLE, TIP, 1));
		CapeCloth none = started();
		right.sideways = 0;
		frames(none, right, START, 1, 60);
		assertEquals(0, none.side(CapeCloth.MIDDLE, TIP, 1), 1e-6);
	}

	@Test
	void fallingLiftsItAndRisingLetsItHang() {
		CapeCloth fall = started();
		CapeCloth.Motion falling = still();
		falling.up = -1.5F;
		frames(fall, falling, START, 0.5, 60);
		assertTrue(lift(fall, CapeCloth.MIDDLE) > 90, "falling fast, it streams up behind: " + lift(fall, CapeCloth.MIDDLE));
		CapeCloth rise = started();
		CapeCloth.Motion rising = still();
		rising.up = 0.42F;
		frames(rise, rising, START, 0.3, 60);
		assertTrue(lift(rise, CapeCloth.MIDDLE) < CapeCloth.REST, "rising, it hangs straighter");
		for (int i = 0; i < CapeCloth.POINTS; i++) {
			assertTrue(rise.away(CapeCloth.MIDDLE, i, 1) >= 0, "never in front of the back");
		}
	}

	@Test
	void swimmingFollowsTheBodysPitch() {
		CapeCloth cloth = started();
		CapeCloth.Motion swim = still();
		swim.swimming = true;
		swim.forward = SPRINT;
		long at = frames(cloth, swim, START, 1, 60);
		assertEquals(CapeCloth.REST, lift(cloth, CapeCloth.MIDDLE), 0.5, "swimming, moving along the body doesn't lift it (Feather)");
		swim.pitch = 30;
		frames(cloth, swim, at, 0.05, 60);
		assertTrue(lift(cloth, CapeCloth.MIDDLE) > CapeCloth.REST + 1, "pitching down swings it up: " + lift(cloth, CapeCloth.MIDDLE));
	}

	@Test
	void aFrameAllocatesNothing() {
		java.lang.management.ThreadMXBean threads = java.lang.management.ManagementFactory.getThreadMXBean();
		org.junit.jupiter.api.Assumptions.assumeTrue(threads instanceof com.sun.management.ThreadMXBean, "needs HotSpot's thread bean");
		com.sun.management.ThreadMXBean hotspot = (com.sun.management.ThreadMXBean) threads;
		org.junit.jupiter.api.Assumptions.assumeTrue(hotspot.isThreadAllocatedMemorySupported() && hotspot.isThreadAllocatedMemoryEnabled());
		CapeCloth cloth = started();
		CapeCloth.Motion m = forward(SPRINT);
		m.right = 0.1F;
		m.turn = 3;
		long at = START;
		double sink = 0;
		for (int f = 0; f < 2_000; f++) {
			at += 4_166_667L;
			cloth.advance(at, m);
			sink += cloth.away(CapeCloth.EDGE, TIP, cloth.alpha());
		}
		long before = hotspot.getCurrentThreadAllocatedBytes();
		for (int f = 0; f < 50_000; f++) {
			at += 4_166_667L;
			cloth.advance(at, m);
			float a = cloth.alpha();
			for (int i = 0; i < CapeCloth.POINTS; i++) {
				sink += cloth.away(CapeCloth.MIDDLE, i, a) + cloth.down(CapeCloth.EDGE, i, a) + cloth.side(CapeCloth.MIDDLE, i, a);
			}
		}
		long allocated = hotspot.getCurrentThreadAllocatedBytes() - before;
		assertTrue(sink != 0);
		assertTrue(allocated < 4096, "50,000 frames at 240 FPS allocated " + allocated + " bytes");
	}

	@Test
	void itStaysWholeHoweverWildlyItIsMoved() {
		Random random = new Random(7);
		CapeCloth cloth = started();
		CapeCloth.Motion m = still();
		long at = START;
		float[] wild = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1e9F, -1e9F, 0, 4, -4, 0.3F};
		for (int f = 0; f < 20_000; f++) {
			at += 1_000_000L + (long) (random.nextDouble() * 60_000_000L);
			m.forward = wild[random.nextInt(wild.length)];
			m.up = wild[random.nextInt(wild.length)];
			m.right = wild[random.nextInt(wild.length)];
			m.turn = wild[random.nextInt(wild.length)];
			m.pitch = wild[random.nextInt(wild.length)];
			m.swimming = random.nextBoolean();
			m.underwater = random.nextBoolean();
			m.angle = random.nextBoolean() ? CapeCloth.REST : wild[random.nextInt(wild.length)];
			m.hangAway = random.nextFloat() * 2 - 1;
			m.hangDown = random.nextBoolean() ? Float.NaN : random.nextFloat() * 2 - 1;
			m.gravity = random.nextBoolean() ? 1 : wild[random.nextInt(wild.length)];
			m.height = random.nextFloat() * 3;
			m.sideways = random.nextFloat() * 5;
			cloth.advance(at, m);
			float a = cloth.alpha();
			for (int chain = 0; chain < CapeCloth.CHAINS; chain++) {
				for (int i = 0; i < CapeCloth.POINTS; i++) {
					float away = cloth.away(chain, i, a);
					float down = cloth.down(chain, i, a);
					float side = cloth.side(chain, i, a);
					assertTrue(Float.isFinite(away) && Float.isFinite(down) && Float.isFinite(side), "finite at frame " + f);
					assertTrue(away >= 0, "never in front of the back");
					assertTrue(Math.abs(down) <= i + 1e-3 && Math.abs(side) <= i + 1e-3 && away <= i + 1e-3, "no longer than itself");
					if (i > 0) {
						float la = cloth.away(chain, i, 1) - cloth.away(chain, i - 1, 1);
						float ld = cloth.down(chain, i, 1) - cloth.down(chain, i - 1, 1);
						float ls = cloth.side(chain, i, 1) - cloth.side(chain, i - 1, 1);
						assertTrue(Math.sqrt(la * la + ld * ld + ls * ls) <= 1 + 1e-3, "no link stretched");
					}
				}
			}
		}
	}
}
