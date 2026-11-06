package com.flintclient.qa;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Wavy Capes frame by frame, for the dev tour: {@code call com.flintclient.qa.QaCapes.begin}, then the tour's moves, then
 * {@code call com.flintclient.qa.QaCapes.end NAME}. Every rendered frame (from the head of {@code runTick}) it reads your
 * cape as Flint last drew it ({@code Mc263Capes.devTip}: the tip's and the middle row's position in the cape part's pixels),
 * and {@code end} writes {@code capes-NAME.csv} to the run folder and answers how smooth the tip moved: how often it turned
 * back (jitter), how much its velocity changed per second, its biggest step in one frame, and how many frames it held still
 * while moving (a cloth stepped without interpolation). Dev only.
 */
public final class QaCapes {
	/** Degrees the third-person camera is turned round the player (26.3's CameraOrbitMixin): 90 films the cape from the side. */
	public static volatile float orbit;
	private static final int MAX = 200_000;
	private static final long[] AT = new long[MAX];
	private static final float[] DATA = new float[MAX * 6];
	private static final int[] DRAWS = new int[MAX];
	private static final float[] TIP = new float[6];
	private static MethodHandle tip;
	private static boolean recording;
	private static int count;
	private static long start;

	private QaCapes() {
	}

	/** From the head of every frame (QaFrames.frame). */
	public static void frame() {
		if (!recording || count >= MAX) {
			return;
		}
		try {
			int draws = (int) tip.invokeExact(TIP);
			AT[count] = System.nanoTime() - start;
			DRAWS[count] = draws;
			System.arraycopy(TIP, 0, DATA, count * 6, 6);
			count++;
		} catch (Throwable e) {
			recording = false;
			FlintQa.log("capes trace stopped: " + e);
		}
	}

	/** {@code call com.flintclient.qa.QaCapes.orbit 90}: the third-person camera turned round the player (0: behind again). */
	public static String orbit(int degrees) {
		orbit = degrees;
		return "camera turned " + orbit + " degrees round the player";
	}

	public static String begin() throws Exception {
		if (tip == null) {
			Class<?> capes = Class.forName("com.flintclient.client.control.platform.Mc263Capes");
			tip = MethodHandles.publicLookup().findStatic(capes, "devTip", MethodType.methodType(int.class, float[].class));
		}
		count = 0;
		start = System.nanoTime();
		recording = true;
		return "recording your cape every frame";
	}

	public static String end(String name) throws Exception {
		recording = false;
		int n = count;
		if (n < 10) {
			return "capes " + name + " too few frames (" + n + ")";
		}
		StringBuilder csv = new StringBuilder("t_s,draws,tip_x,tip_y,tip_z,mid_x,mid_y,mid_z\n");
		for (int i = 0; i < n; i++) {
			csv.append(String.format(Locale.ROOT, "%.5f,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f%n", AT[i] / 1e9, DRAWS[i], DATA[i * 6], DATA[i * 6 + 1],
				DATA[i * 6 + 2], DATA[i * 6 + 3], DATA[i * 6 + 4], DATA[i * 6 + 5]));
		}
		Path out = Path.of(System.getProperty("flintqa.out", "."));
		Files.writeString(out.resolve("capes-" + name + ".csv"), csv, StandardCharsets.UTF_8);
		// Only frames that drew the cape anew (the head of frame k sees frame k-1's drawing).
		double seconds = (AT[n - 1] - AT[0]) / 1e9;
		int drawn = 0;
		int reversals = 0;
		int held = 0;
		int moving = 0;
		double maxStep = 0;
		double velocityChange = 0;
		double path = 0;
		double lastVy = 0;
		double lastVz = 0;
		double lastDy = 0;
		double lastDz = 0;
		boolean haveV = false;
		int prev = -1;
		for (int i = 1; i < n; i++) {
			if (DRAWS[i] == DRAWS[i - 1]) {
				continue;
			}
			drawn++;
			if (prev < 0) {
				prev = i;
				continue;
			}
			double dt = (AT[i] - AT[prev]) / 1e9;
			double dx = DATA[i * 6] - DATA[prev * 6];
			double dy = DATA[i * 6 + 1] - DATA[prev * 6 + 1];
			double dz = DATA[i * 6 + 2] - DATA[prev * 6 + 2];
			double step = Math.sqrt(dx * dx + dy * dy + dz * dz);
			path += step;
			maxStep = Math.max(maxStep, step);
			// Held: no motion at all in a frame whose neighbours moved (a cloth that only changes on some frames).
			if (step < 1e-6 && (Math.abs(lastDy) > 1e-3 || Math.abs(lastDz) > 1e-3)) {
				held++;
			}
			if (step > 1e-3) {
				moving++;
			}
			// A reversal: the tip's up-down or in-out motion turns back between two frames that both moved (> 0.002 px).
			if (Math.abs(dy) > 2e-3 && Math.abs(lastDy) > 2e-3 && Math.signum(dy) != Math.signum(lastDy)) {
				reversals++;
			}
			if (Math.abs(dz) > 2e-3 && Math.abs(lastDz) > 2e-3 && Math.signum(dz) != Math.signum(lastDz)) {
				reversals++;
			}
			if (dt > 0) {
				double vy = dy / dt;
				double vz = dz / dt;
				if (haveV) {
					velocityChange += Math.hypot(vy - lastVy, vz - lastVz);
				}
				lastVy = vy;
				lastVz = vz;
				haveV = true;
			}
			lastDy = dy;
			lastDz = dz;
			prev = i;
		}
		return String.format(Locale.ROOT,
			"capes %s frames=%d drawn=%d fps=%.1f path=%.2fpx reversalsPerS=%.2f velocityChangePxPerS2=%.1f maxStepPx=%.3f heldWhileMoving=%d moving=%d",
			name, n, drawn, n / Math.max(seconds, 1e-9), path, reversals / Math.max(seconds, 1e-9), velocityChange / Math.max(seconds, 1e-9),
			maxStep, held, moving);
	}
}
