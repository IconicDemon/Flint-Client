package com.flintclient.client.control.menu;

/** The menu's small sums, kept apart from drawing so they can be tested without Minecraft. Pure. */
final class MenuMath {
	private MenuMath() {
	}

	/** {@code raw} on a slider's steps: clamped to {@code min..max}, then the nearest whole step from {@code min}. */
	static int snap(int raw, int min, int max, int step) {
		int clamped = Math.clamp(raw, min, max);
		int snapped = min + Math.round((clamped - min) / (float) Math.max(1, step)) * Math.max(1, step);
		return Math.clamp(snapped, min, max);
	}

	/** How many decimals a decimal setting's step needs to show (0.05 → 2, 0.5 → 1, 1 → 0; at most 4). */
	static int decimals(float step) {
		for (int d = 0; d < 4; d++) {
			float scaled = step * (float) Math.pow(10, d);
			if (Math.abs(scaled - Math.round(scaled)) < 1e-4F) {
				return d;
			}
		}
		return 4;
	}

	/** A typed number ("12", " 3.5 ", "-2", "0,25"), rounded to a whole number; null when it isn't one. */
	static @org.jspecify.annotations.Nullable Integer parseWhole(String text) {
		Double v = parseNumber(text);
		return v == null ? null : (int) Math.round(Math.clamp(v, Integer.MIN_VALUE, Integer.MAX_VALUE));
	}

	/** A typed number, a comma read as a decimal point; null when it isn't a finite number. */
	static @org.jspecify.annotations.Nullable Double parseNumber(String text) {
		String t = text.strip().replace(',', '.');
		if (t.isEmpty()) {
			return null;
		}
		try {
			double v = Double.parseDouble(t);
			return Double.isFinite(v) ? v : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
