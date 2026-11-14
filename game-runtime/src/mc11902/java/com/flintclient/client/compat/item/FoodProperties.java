package com.flintclient.client.compat.item;

import org.jspecify.annotations.Nullable;

/**
 * 1.20.5's food component made from Minecraft 1.19.2's food properties: the hunger it fills, the saturation it gives (1.19.2
 * keeps a modifier: eating gives nutrition × modifier × 2, which 1.20.5 stores), and whether you can eat it when full.
 */
public record FoodProperties(int nutrition, float saturation, boolean canAlwaysEat) {
	/** The component for 1.19.2's food properties, or null for none. */
	public static @Nullable FoodProperties of(net.minecraft.world.food.@Nullable FoodProperties food) {
		return food == null ? null
			: new FoodProperties(food.getNutrition(), food.getNutrition() * food.getSaturationModifier() * 2.0F, food.canAlwaysEat());
	}
}
