package com.flintclient.client.control.hud.render;

/**
 * The order of the effects module's rows: by default the effect that ends soonest first, effects without an end
 * (infinite) last; or by name, ignoring case. Equal keys keep a fixed order (by {@code tieBreak}, the effect's
 * registry number) so rows never swap places while two timers count down together. Insertion sorts over indices
 * into reused arrays: a handful of effects, sorted at most four times a second, with nothing allocated. Pure, for
 * tests.
 */
public final class EffectOrder {
	/** Minecraft's duration for an effect that never ends ({@code MobEffectInstance.INFINITE_DURATION}). */
	public static final int INFINITE = -1;

	private EffectOrder() {
	}

	/**
	 * Fills {@code order[0..n)} with the indices {@code 0..n)} sorted by {@code remaining} ticks (with
	 * {@link #INFINITE} after every finite time), then by {@code tieBreak}.
	 */
	public static void sort(int[] remaining, int[] tieBreak, int n, int[] order) {
		for (int i = 0; i < n; i++) {
			order[i] = i;
		}
		for (int i = 1; i < n; i++) {
			int item = order[i];
			int j = i - 1;
			while (j >= 0 && after(order[j], item, remaining, tieBreak)) {
				order[j + 1] = order[j];
				j--;
			}
			order[j + 1] = item;
		}
	}

	/** Fills {@code order[0..n)} with the indices {@code 0..n)} sorted by {@code names} (ignoring case), then by {@code tieBreak}. */
	public static void sortByName(String[] names, int[] tieBreak, int n, int[] order) {
		for (int i = 0; i < n; i++) {
			order[i] = i;
		}
		for (int i = 1; i < n; i++) {
			int item = order[i];
			int j = i - 1;
			while (j >= 0 && afterByName(order[j], item, names, tieBreak)) {
				order[j + 1] = order[j];
				j--;
			}
			order[j + 1] = item;
		}
	}

	static boolean afterByName(int a, int b, String[] names, int[] tieBreak) {
		int compared = String.CASE_INSENSITIVE_ORDER.compare(names[a], names[b]);
		if (compared != 0) {
			return compared > 0;
		}
		return tieBreak[a] > tieBreak[b];
	}

	/** Whether row {@code a} belongs after row {@code b}. */
	static boolean after(int a, int b, int[] remaining, int[] tieBreak) {
		long ka = key(remaining[a]);
		long kb = key(remaining[b]);
		if (ka != kb) {
			return ka > kb;
		}
		return tieBreak[a] > tieBreak[b];
	}

	private static long key(int ticks) {
		return ticks == INFINITE ? Long.MAX_VALUE : ticks;
	}
}
