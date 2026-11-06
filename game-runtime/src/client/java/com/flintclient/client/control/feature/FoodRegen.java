package com.flintclient.client.control.feature;

/**
 * Saturation's health gain estimate, as AppleSkin works it out: how much health natural regeneration gives back from a
 * food level, saturation and exhaustion before the food level drops under 18 (fast regeneration from saturation at a full
 * bar, one point per 6 exhaustion from 18 points, exhaustion using up saturation, then hunger). No Minecraft classes.
 *
 * <p>Adapted from AppleSkin's {@code FoodHelper.getEstimatedHealthIncrement} (squeek502, Unlicense;
 * META-INF/licenses/Unlicense-AppleSkin.txt).
 */
public final class FoodRegen {
	/** Exhaustion that costs one saturation or hunger point. */
	public static final float MAX_EXHAUSTION = 4;
	/** Exhaustion one point of regeneration costs. */
	static final float REGEN_EXHAUSTION_INCREMENT = 6;

	private FoodRegen() {
	}

	/** Health natural regeneration gives back from {@code foodLevel}, {@code saturationLevel} and {@code exhaustionLevel}. */
	public static float estimate(int foodLevel, float saturationLevel, float exhaustionLevel) {
		float health = 0;
		if (!Float.isFinite(exhaustionLevel) || !Float.isFinite(saturationLevel)) {
			return 0;
		}
		while (foodLevel >= 18) {
			while (exhaustionLevel > MAX_EXHAUSTION) {
				exhaustionLevel -= MAX_EXHAUSTION;
				if (saturationLevel > 0) {
					saturationLevel = Math.max(saturationLevel - 1, 0);
				} else {
					foodLevel--;
				}
			}
			if (foodLevel >= 20 && Float.compare(saturationLevel, Float.MIN_NORMAL) > 0) {
				float limitedSaturationLevel = Math.min(saturationLevel, REGEN_EXHAUSTION_INCREMENT);
				float exhaustionUntilAboveMax = Math.nextUp(MAX_EXHAUSTION) - exhaustionLevel;
				int iterations = Math.max(1, (int) Math.ceil(exhaustionUntilAboveMax / limitedSaturationLevel));
				health += limitedSaturationLevel / REGEN_EXHAUSTION_INCREMENT * iterations;
				exhaustionLevel += limitedSaturationLevel * iterations;
			} else if (foodLevel >= 18) {
				health++;
				exhaustionLevel += REGEN_EXHAUSTION_INCREMENT;
			}
		}
		return health;
	}

	/**
	 * The health natural regeneration gives back after eating a food ({@code nutrition}, {@code saturationGain}) at
	 * {@code food} and {@code saturation}: the food level it reaches, if that's 18 or more, with saturation capped at it.
	 */
	public static float afterEating(int food, float saturation, float exhaustion, int nutrition, float saturationGain) {
		int foodLevel = Math.min(food + nutrition, 20);
		if (foodLevel < 18) {
			return 0;
		}
		return estimate(foodLevel, Math.min(saturation + saturationGain, foodLevel), exhaustion);
	}
}
