package com.flintclient.client.compat;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * The effects eating something gives, as Saturation reads them. 1.21.2 moved them to the item's Consumable component;
 * 1.21.1 keeps them on its food properties.
 */
public final class FoodEffects {
	private FoodEffects() {
	}

	/** Whether eating it makes you sick (a harmful effect: rotten flesh, raw chicken, spider eyes). */
	public static boolean harmful(ItemStack stack) {
		FoodProperties food = stack.get(DataComponents.FOOD);
		if (food == null) {
			return false;
		}
		for (FoodProperties.PossibleEffect effect : food.effects()) {
			if (effect.effect().getEffect().value().getCategory() == MobEffectCategory.HARMFUL) {
				return true;
			}
		}
		return false;
	}

	/** The health a regeneration effect from eating it gives back (AppleSkin's estimate), or 0. */
	public static float regeneration(ItemStack stack) {
		FoodProperties food = stack.get(DataComponents.FOOD);
		if (food == null) {
			return 0;
		}
		for (FoodProperties.PossibleEffect effect : food.effects()) {
			MobEffectInstance instance = effect.effect();
			if (instance.getEffect().equals(MobEffects.REGENERATION)) {
				return (float) Math.floor(instance.getDuration() / (double) Math.max(50 >> instance.getAmplifier(), 1));
			}
		}
		return 0;
	}
}
