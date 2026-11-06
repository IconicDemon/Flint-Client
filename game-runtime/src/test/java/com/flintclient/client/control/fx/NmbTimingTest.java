package com.flintclient.client.control.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NmbTimingTest {
	private static final int HZ = 200;

	/** What is left of the trail after {@code seconds} of frames at {@code fps}. */
	private static double leftAfter(int mode, float strength, int fps, double seconds) {
		double left = 1;
		int frames = (int) Math.round(seconds * fps);
		for (int i = 0; i < frames; i++) {
			left *= NmbTiming.kept(mode, strength, 1F / fps, HZ);
		}
		return left;
	}

	@Test
	void trailsFadeByTimeNotFrames() {
		for (int mode : new int[] {NmbTiming.ACCUMULATION_MIX, NmbTiming.ACCUMULATION_MAX}) {
			double at60 = leftAfter(mode, 1, 60, 0.05);
			double at240 = leftAfter(mode, 1, 240, 0.05);
			double at600 = leftAfter(mode, 1, 600, 0.05);
			assertEquals(at60, at240, 1e-4, "mode " + mode);
			assertEquals(at60, at600, 1e-4, "mode " + mode);
		}
	}

	@Test
	void accumulationMatchesNaturalMotionBlurAtTheRefreshRate() {
		// Natural Motion Blur keeps 1 - 0.5^(strength x 7 / 3) (max) or x 5 / 3 (mix) each frame.
		assertEquals(1 - Math.pow(0.5, 7 / 3.0), NmbTiming.kept(NmbTiming.ACCUMULATION_MAX, 1, 1F / HZ, HZ), 1e-5);
		assertEquals(1 - Math.pow(0.5, 5 / 3.0), NmbTiming.kept(NmbTiming.ACCUMULATION_MIX, 1, 1F / HZ, HZ), 1e-5);
	}

	@Test
	void aSlowFrameShowsMostlyItself() {
		// A 40 ms hitch at 1,000 FPS keeps far less of the old image than an ordinary frame.
		float normal = NmbTiming.kept(NmbTiming.ACCUMULATION_MIX, 1, 0.001F, HZ);
		float hitch = NmbTiming.kept(NmbTiming.ACCUMULATION_MIX, 1, 0.040F, HZ);
		assertTrue(normal > 0.5F);
		assertTrue(hitch < 0.1F);
	}

	@Test
	void keptIsCappedAndZeroStrengthKeepsNothing() {
		assertTrue(NmbTiming.kept(NmbTiming.ACCUMULATION_MAX, 2, 0.0001F, HZ) <= NmbTiming.MAX_KEPT + 1e-6);
		assertEquals(0, NmbTiming.kept(NmbTiming.ACCUMULATION_MAX, 0, 0.001F, HZ));
		assertEquals(0, NmbTiming.kept(NmbTiming.ACCUMULATION_MIX, 0, 0.001F, HZ));
	}

	@Test
	void velocityScalesWithFramesPerRefresh() {
		assertEquals(1, NmbTiming.velocityStrength(1, 100, HZ, true), 1e-6);
		assertEquals(3, NmbTiming.velocityStrength(1, 600, HZ, true), 1e-6);
		assertEquals(1, NmbTiming.velocityStrength(1, 600, HZ, false), 1e-6);
		// The first frame (no frame rate yet) and an unknown monitor blur as set.
		assertEquals(1.5F, NmbTiming.velocityStrength(1.5F, 0, HZ, true), 1e-6);
		assertEquals(1.5F, NmbTiming.velocityStrength(1.5F, 600, 0, true), 1e-6);
	}

	@Test
	void theBlurIsAsLongAtAnyFrameRate() {
		// A camera turning at a steady speed: each frame's motion shrinks as the frame rate rises, the scaled blur doesn't.
		double screensPerSecond = 2.0;
		double at60 = screensPerSecond / 60 * NmbTiming.velocityStrength(1, 60, 60, true);
		double at240 = screensPerSecond / 240 * NmbTiming.velocityStrength(1, 240, 60, true);
		double at1000 = screensPerSecond / 1000 * NmbTiming.velocityStrength(1, 1000, 60, true);
		assertEquals(at60, at240, 1e-6);
		assertEquals(at60, at1000, 1e-6);
	}

	@Test
	void samplesFollowTheBlurLengthAndTheQuality() {
		// Balanced is Natural Motion Blur's density at strength 1: 100 samples per screen of blur, at least 4.
		assertEquals(NmbTiming.MIN_SAMPLES, NmbTiming.samples(0.001F, NmbTiming.BALANCED, false));
		assertEquals(40, NmbTiming.samples(0.4F, NmbTiming.BALANCED, false));
		assertEquals(20, NmbTiming.samples(0.4F, NmbTiming.PERFORMANCE, false));
		assertEquals(80, NmbTiming.samples(0.4F, NmbTiming.HIGH, false));
		// A very long blur stops at the quality's most.
		assertEquals(NmbTiming.maxSamples(NmbTiming.BALANCED), NmbTiming.samples(8, NmbTiming.BALANCED, false));
		// Weighted takes 1.35 times as many, at least 6, and never past the shaders' loop.
		assertEquals(54, NmbTiming.samples(0.4F, NmbTiming.BALANCED, true));
		assertEquals(6, NmbTiming.samples(0.001F, NmbTiming.BALANCED, true));
		assertTrue(NmbTiming.samples(100, NmbTiming.HIGH, true) <= NmbTiming.LOOP_BOUND);
		assertEquals(NmbTiming.LOOP_BOUND, NmbTiming.samples(100, NmbTiming.HIGH, true));
	}

	@Test
	void playersAndMobsAreBlurredByTheCameraOnly() {
		// First person, moving: the terrain gets the movement before players and mobs, everything the camera's turn after.
		assertEquals(NmbTiming.MODE_MOVEMENT, NmbTiming.earlyMode(true, false, true));
		assertEquals(NmbTiming.MODE_CAMERA, NmbTiming.lateMode(true, false, true, true));
		// Only turning: the movement part is exactly zero, so there is no early pass; the turn after.
		assertEquals(NmbTiming.NONE, NmbTiming.earlyMode(true, false, false));
		assertEquals(NmbTiming.MODE_CAMERA, NmbTiming.lateMode(true, false, false, false));
		// Blur when moving off: only the turn, after the world.
		assertEquals(NmbTiming.NONE, NmbTiming.earlyMode(false, false, true));
		assertEquals(NmbTiming.MODE_CAMERA, NmbTiming.lateMode(false, false, false, true));
		// Third person or riding: all of it before players, mobs and the vehicle, nothing after.
		assertEquals(NmbTiming.MODE_FULL, NmbTiming.earlyMode(true, true, true));
		assertEquals(NmbTiming.MODE_FULL, NmbTiming.earlyMode(true, true, false));
		assertEquals(NmbTiming.NONE, NmbTiming.lateMode(true, true, true, true));
		assertEquals(NmbTiming.MODE_CAMERA, NmbTiming.earlyMode(false, true, true));
		// Without the early pass (a shader pack, another mod drawing the terrain), the late pass draws it all.
		assertEquals(NmbTiming.MODE_FULL, NmbTiming.lateMode(true, false, false, true));
		assertEquals(NmbTiming.MODE_FULL, NmbTiming.lateMode(true, true, false, false));
		assertEquals(NmbTiming.MODE_CAMERA, NmbTiming.lateMode(false, true, false, true));
	}

	/** A column-major perspective projection (OpenGL, depth -1..1). */
	private static float[] perspective(float fovY, float aspect, float near, float far) {
		float f = (float) (1 / Math.tan(fovY / 2));
		float[] m = new float[16];
		m[0] = f / aspect;
		m[5] = f;
		m[10] = (far + near) / (near - far);
		m[11] = -1;
		m[14] = 2 * far * near / (near - far);
		return m;
	}

	/** The inverse of {@link #perspective}'s matrix, column-major. */
	private static float[] inversePerspective(float[] p) {
		float[] m = new float[16];
		m[0] = 1 / p[0];
		m[5] = 1 / p[5];
		m[11] = 1 / p[14];
		m[14] = -1;
		m[15] = p[10] / p[14];
		return m;
	}

	/** A column-major rotation about the vertical axis. */
	private static float[] yaw(double radians) {
		float c = (float) Math.cos(radians);
		float s = (float) Math.sin(radians);
		return new float[] {c, 0, -s, 0, 0, 1, 0, 0, s, 0, c, 0, 0, 0, 0, 1};
	}

	@Test
	void aTurnIsMeasuredInPixels() {
		float[] projection = perspective((float) Math.toRadians(70), 16 / 9F, 0.05F, 256);
		float[] inverse = inversePerspective(projection);
		float[] identity = yaw(0);
		// No turn: nothing moved.
		assertEquals(0, NmbTiming.turnPixels(inverse, identity, identity, projection, 0, 1920, 1080), 1e-3);
		// A tenth of a degree moves the centre about 1.4 pixels and the corners about 3.5 on a 1920-wide view 70 degrees tall.
		float tenth = NmbTiming.turnPixels(inverse, identity, yaw(Math.toRadians(0.1)), projection, 0, 1920, 1080);
		assertTrue(tenth > 2.5F && tenth < 5, "moved " + tenth);
		// A thousandth of a degree is under half a pixel: a drift, no pass.
		assertTrue(NmbTiming.turnPixels(inverse, identity, yaw(Math.toRadians(0.001)), projection, 0, 1920, 1080) < 0.5F);
	}

	@Test
	void whichAlgorithmsDrawWhat() {
		assertTrue(NmbTiming.usesVelocity(NmbTiming.VELOCITY));
		assertTrue(NmbTiming.usesVelocity(NmbTiming.HYBRID));
		assertFalse(NmbTiming.usesVelocity(NmbTiming.FRAME_BLENDING));
		assertTrue(NmbTiming.usesFrameBlending(NmbTiming.FRAME_BLENDING));
		assertTrue(NmbTiming.usesFrameBlending(NmbTiming.HYBRID));
		assertFalse(NmbTiming.usesFrameBlending(NmbTiming.ACCUMULATION_MIX));
		assertTrue(NmbTiming.usesAccumulation(NmbTiming.ACCUMULATION_MAX));
		assertTrue(NmbTiming.usesAccumulation(NmbTiming.ACCUMULATION_MIX));
		assertFalse(NmbTiming.usesAccumulation(NmbTiming.VELOCITY));
	}

	@Test
	void aPauseOrATeleportStartsOver() {
		assertTrue(NmbTiming.continues(1 / 144F, 0.2, 0, 0.1));
		assertFalse(NmbTiming.continues(0, 0, 0, 0), "the first frame");
		assertFalse(NmbTiming.continues(0.5F, 0, 0, 0), "a pause");
		assertFalse(NmbTiming.continues(1 / 60F, 30, 0, 0), "a teleport");
		// Fast flight at a low frame rate is still motion.
		assertTrue(NmbTiming.continues(0.1F, 6, 1, 0));
	}

	@Test
	void hybridFillerCoversTheBlendsGaps() {
		// At the refresh rate one displayed frame is one blended frame: the filler is the strength.
		assertEquals(1, NmbTiming.hybridFiller(1, HZ, HZ, HZ, 12), 1e-6);
		// No frame rate yet: the strength as set.
		assertEquals(1.5F, NmbTiming.hybridFiller(1.5F, 0, 0, HZ, 0), 1e-6);
		// Far more frames than the blend keeps: at least the gap between two kept ones.
		assertTrue(NmbTiming.hybridFiller(1, 4000, 4000, 200, 24) >= 1);
	}

	@Test
	void smoothedFpsFollowsByFifteenPercent() {
		assertEquals(100, NmbTiming.smoothFps(0, 100), 1e-6);
		assertEquals(115, NmbTiming.smoothFps(100, 200), 1e-4);
		assertEquals(100, NmbTiming.smoothFps(100, 0), 1e-6);
	}

	@Test
	void frameBlendWeightsAllocateNothingAndCoverTheExposure() {
		double[] times = new double[NmbTiming.MAX_HISTORY];
		int[] order = new int[NmbTiming.MAX_HISTORY];
		for (int i = 0; i < times.length; i++) {
			times[i] = 10 + i / 1000.0;
			order[i] = i;
		}
		int[] slots = new int[NmbTiming.MAX_HISTORY];
		float[] weights = new float[NmbTiming.MAX_HISTORY];
		double now = times[times.length - 1];
		int count = NmbTiming.weights(now, times, order, times.length, 1000, HZ, 1, slots, weights);
		float total = 0;
		for (int i = 0; i < count; i++) {
			total += weights[i];
		}
		// One refresh period (5 ms) of 1 ms frames.
		assertEquals(5, count);
		assertEquals(0.005, total, 1e-6);
	}

	@Test
	void frameBlendingAtTheRefreshRateShowsOneFrame() {
		// Natural Motion Blur's exposure is strength refresh periods (or frames, below the refresh rate): at strength 1 and
		// 60 FPS on 60 Hz only the newest frame falls inside, so frame blending changes nothing.
		double[] times = new double[NmbTiming.MAX_HISTORY];
		int[] order = new int[NmbTiming.MAX_HISTORY];
		for (int i = 0; i < times.length; i++) {
			times[i] = 10 + i / 60.0;
			order[i] = i;
		}
		int[] slots = new int[NmbTiming.MAX_HISTORY];
		float[] weights = new float[NmbTiming.MAX_HISTORY];
		assertEquals(1, NmbTiming.weights(times[11], times, order, 12, 60, 60, 1, slots, weights));
		// Strength 2 blends the last two.
		assertEquals(2, NmbTiming.weights(times[11], times, order, 12, 60, 60, 2, slots, weights));
	}

	@Test
	void frameBlendWeightsKeepTheirTotalWhenCompacted() {
		double[] times = new double[NmbTiming.MAX_HISTORY];
		int[] order = new int[NmbTiming.MAX_HISTORY];
		for (int i = 0; i < times.length; i++) {
			times[i] = 10 + i / 1000.0;
			order[i] = i;
		}
		int[] slots = new int[NmbTiming.MAX_HISTORY];
		float[] weights = new float[NmbTiming.MAX_HISTORY];
		// 12 frames inside the exposure, at most 4 read: 4 evenly spread, the whole exposure's weight.
		int count = NmbTiming.weights(times[11], times, order, 12, 1000, 50, 1, slots, weights, 4);
		float total = 0;
		for (int i = 0; i < count; i++) {
			total += weights[i];
		}
		assertEquals(4, count);
		assertEquals(0.012, total, 1e-6);
		assertEquals(0, slots[0]);
		assertEquals(11, slots[3]);
	}
}
