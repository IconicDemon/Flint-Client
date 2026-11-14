package com.flintclient.qa;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Frame stamps for screen-capture tests (what a Discord or OBS viewer sees): while on, every frame the game presents carries
 * its number as a row of black and white blocks in a corner of the picture (painted by the version's mixin just before the
 * frame goes to the window), and the time just before and just after the present call is kept. A capture of the screen then
 * tells, frame by frame, which game frame the viewer got: duplicates, skipped frames and uneven steps. Dev only.
 *
 * <p>Steps: {@code call com.flintclient.qa.QaStamp.on}, {@code call com.flintclient.qa.QaStamp.dump NAME} (writes
 * {@code stamps-NAME.csv}: frame, before_ns, after_ns; then off).
 */
public final class QaStamp {
	/** Block layout: white, black (where the row starts and which way up it is), 20 bits of the frame number, even parity. */
	public static final int BLOCKS = 23;
	public static final int DATA_BITS = 20;
	/** One block's side in pixels of the game's picture. */
	public static final int BLOCK = 20;
	private static final int MAX = 400_000;
	private static final int[] NUMBER = new int[MAX];
	private static final long[] BEFORE = new long[MAX];
	private static final long[] AFTER = new long[MAX];
	private static boolean on;
	private static int frame = 1;
	private static int count;
	private static long before;

	private QaStamp() {
	}

	public static String on() {
		on = true;
		count = 0;
		return "stamping from frame " + frame;
	}

	public static boolean active() {
		return on;
	}

	/** Whether block {@code i} of the frame being drawn now is white. */
	public static boolean white(int i) {
		int n = frame & ((1 << DATA_BITS) - 1);
		if (i == 0) {
			return true;
		}
		if (i == 1) {
			return false;
		}
		if (i < 2 + DATA_BITS) {
			return ((n >> (DATA_BITS - 1 - (i - 2))) & 1) != 0;
		}
		return (Integer.bitCount(n) & 1) != 0;
	}

	public static void beforePresent() {
		if (on) {
			before = System.nanoTime();
		}
	}

	public static void afterPresent() {
		if (!on) {
			return;
		}
		if (count < MAX) {
			NUMBER[count] = frame;
			BEFORE[count] = before;
			AFTER[count] = System.nanoTime();
			count++;
		}
		frame++;
	}

	public static String dump(String name) throws Exception {
		on = false;
		StringBuilder csv = new StringBuilder("frame,before_ns,after_ns\n");
		for (int i = 0; i < count; i++) {
			csv.append(NUMBER[i]).append(',').append(BEFORE[i]).append(',').append(AFTER[i]).append('\n');
		}
		Path out = Path.of(System.getProperty("flintqa.out", "."));
		Files.writeString(out.resolve("stamps-" + name + ".csv"), csv, StandardCharsets.UTF_8);
		return "stamps " + name + " frames=" + count;
	}
}
