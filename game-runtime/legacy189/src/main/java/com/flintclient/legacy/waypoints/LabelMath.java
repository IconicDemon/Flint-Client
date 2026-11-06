package com.flintclient.legacy.waypoints;

/**
 * The numbers behind a waypoint label on 1.8.9, the same as wWaypoints' (and Flint's modern runtimes'): how big a label is at a
 * distance, how its distance reads, and how it fades when you are close or look away. No Minecraft classes, so it is tested as
 * it is.
 */
public final class LabelMath {
	private static final String[] UNDER_THOUSAND = new String[1000];

	static {
		for (int i = 0; i < UNDER_THOUSAND.length; i++) {
			UNDER_THOUSAND[i] = "[" + i + "m]";
		}
	}

	private LabelMath() {
	}

	/**
	 * A label's size at {@code distance} blocks from the camera, as a fraction of its full size: about 1.03 up close, 0.92 at 10
	 * blocks, 0.59 at 1,000 and 0.35 at 100,000 (two logistic steps on log10 of the distance).
	 */
	public static float scale(double distance) {
		double x = Math.log10(Math.max(0.01, distance));
		double near = 1 / (1 + Math.exp(2.8 * (x - 1.4)));
		double far = 1 / (1 + Math.exp(2.5 * (x - 4.0)));
		return (float) ((33 + 43 * near + 27 * far) / 100);
	}

	/** "[37m]", "[1.2K]", "[45K]", "[3.4M]". */
	public static String distanceText(int blocks) {
		int m = Math.max(0, blocks);
		if (m < 1000) {
			return UNDER_THOUSAND[m];
		}
		if (m < 10_000) {
			int tenths = (m + 50) / 100;
			return tenths >= 100 ? "[10K]" : "[" + tenths / 10 + "." + tenths % 10 + "K]";
		}
		if (m < 1_000_000) {
			int k = (m + 500) / 1000;
			return k >= 1000 ? "[1.0M]" : "[" + k + "K]";
		}
		if (m < 10_000_000) {
			int tenths = (m + 50_000) / 100_000;
			return tenths >= 100 ? "[10M]" : "[" + tenths / 10 + "." + tenths % 10 + "M]";
		}
		return "[" + m / 1_000_000 + "M]";
	}

	/** Opacity of a label {@code distance} blocks away that fades out from {@code start} down to {@code end} blocks. */
	public static float nearFade(double distance, double start, double end) {
		if (start <= 0 && end <= 0) {
			return 1;
		}
		if (distance <= end) {
			return 0;
		}
		if (distance >= start) {
			return 1;
		}
		return (float) ((distance - end) / Math.max(0.001, start - end));
	}

	/** The distance under a label shows while you look within 5 degrees of it and is gone past 9. */
	public static float facingAlpha(double angleDegrees) {
		if (angleDegrees >= 9) {
			return 0;
		}
		return angleDegrees <= 5 ? 1 : (float) (1 - (angleDegrees - 5) / 4);
	}

	/** How strongly a label you aim at comes to the top: full within 2 degrees, none past 7, smooth between. */
	public static float aimAlpha(double angleDegrees) {
		if (angleDegrees <= 2) {
			return 1;
		}
		if (angleDegrees >= 7) {
			return 0;
		}
		float v = (float) (1 - (angleDegrees - 2) / 5);
		return v * v * (3 - 2 * v);
	}

	/** The angle in degrees between a look direction (unit) and the way to a point ({@code dx}, {@code dy}, {@code dz}). */
	public static double angle(double lookX, double lookY, double lookZ, double dx, double dy, double dz) {
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 1e-6) {
			return 0;
		}
		double dot = (lookX * dx + lookY * dy + lookZ * dz) / len;
		return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
	}
}
