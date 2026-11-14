package com.flintclient.client.control.feature;

/**
 * How Minecraft wears an item down, worked out from the item's values alone (no Minecraft classes), so every line's tooltip
 * and HUD estimates follow the game's own rules and a unit test pins them.
 *
 * <p>Unbreaking, as every line from 1.19 to 26.x has it: each point of damage is lost only with a chance. 1.21 made it data
 * ({@code data/minecraft/enchantment/unbreaking.json}: an {@code item_damage} {@code remove_binomial} effect that removes a
 * point with the chance {@code 2L / (10 + 5(L-1))} for {@code #minecraft:enchantable/armor}, else {@code L / (L+1)}); 1.19.x
 * has the same rule in {@code DigDurabilityEnchantment.shouldIgnoreDurabilityDrop} (an ArmorItem keeps its point 60% of
 * the time, then {@code nextInt(L+1) > 0}). So a point is lost with the chance {@code 1/(L+1)}, or for armor
 * {@code 0.6 + 0.4/(L+1)}. An elytra is not armor here on any line (it isn't in {@code enchantable/armor} and isn't an
 * ArmorItem), so it gets the {@code 1/(L+1)} rule: Unbreaking III makes it last four times as long.
 *
 * <p>Gliding (LivingEntity.updateFallFlying on every line): one point of damage every 20 ticks of flight (the tick count
 * is {@code fallFlyTicks + 1}; every 10th tick, every other time), and gliding stops when the next point would break it
 * (damage {@code >= max - 1}). A creative player's items, and an item that isn't damageable (the unbreakable component or
 * tag), never wear. Mending repairs from experience and doesn't change the wear itself.
 */
public final class ItemWear {
	/** Ticks of gliding per point of elytra damage. */
	public static final int GLIDE_TICKS_PER_POINT = 20;
	/** Ticks in a second at Minecraft's normal tick rate. */
	public static final float NORMAL_TICK_RATE = 20;

	private ItemWear() {
	}

	/**
	 * The chance one point of damage is really taken from an item with Unbreaking at {@code level} (1 without it);
	 * {@code armor}: the item is in {@code #minecraft:enchantable/armor} (1.21+) or is an ArmorItem (1.19.x).
	 */
	public static double lossChance(int level, boolean armor) {
		if (level <= 0) {
			return 1;
		}
		if (armor) {
			// 1.21's fraction 2L / (10 + 5(L-1)) of points removed, which is 0.4 * L/(L+1).
			return 1 - 2.0 * level / (10.0 + 5.0 * (level - 1));
		}
		// 1.21's fraction L / (2 + (L-1)) removed: 1/(L+1) kept.
		return 1 - level / (2.0 + (level - 1));
	}

	/** Points of damage an elytra can still take while gliding (it stops one point before breaking). */
	public static int glidePoints(int maxDamage, int damage) {
		return Math.max(0, maxDamage - damage - 1);
	}

	/**
	 * The expected seconds of gliding an elytra has left, at {@code tickRate} ticks a second: {@code -1} when it never wears
	 * ({@code !damageable} or {@code creative}), 0 when it can't glide any more.
	 */
	public static double flightSeconds(int maxDamage, int damage, boolean damageable, int unbreaking, boolean armor,
		boolean creative, float tickRate) {
		if (!damageable || creative || maxDamage <= 0) {
			return -1;
		}
		int points = glidePoints(maxDamage, damage);
		float rate = tickRate > 0 && Float.isFinite(tickRate) ? tickRate : NORMAL_TICK_RATE;
		return points * (GLIDE_TICKS_PER_POINT / (double) rate) / lossChance(unbreaking, armor);
	}

	/** The elytra tooltip's line: "Flight left: ~28m 44s", "Flight left: 0s", "Flight left: ∞ (Creative)". */
	public static String flightLine(double seconds, int unbreaking, boolean creative) {
		return "Flight left: " + flightText(seconds, unbreaking > 0) + (creative ? " (Creative)" : "");
	}

	/**
	 * A tooltip's flight time: "~28m 44s" (an average when Unbreaking makes it a matter of chance), "7m 11s" without, "0s"
	 * for a worn-out elytra, "1h 5m" past an hour, "∞" when it never wears.
	 */
	public static String flightText(double seconds, boolean average) {
		if (seconds < 0 || Double.isNaN(seconds)) {
			return "∞";
		}
		long total = (long) Math.floor(seconds + 1e-6);
		if (total == 0) {
			return "0s";
		}
		String prefix = average ? "~" : "";
		long h = total / 3600;
		long m = total % 3600 / 60;
		long s = total % 60;
		if (h > 0) {
			return prefix + h + "h " + m + "m";
		}
		return prefix + (m > 0 ? m + "m " + s + "s" : s + "s");
	}
}
