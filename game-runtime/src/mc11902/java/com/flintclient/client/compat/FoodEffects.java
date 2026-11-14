package com.flintclient.client.compat;

import com.mojang.datafixers.util.Pair;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * The effects eating something gives, as Saturation reads them. Minecraft 1.19.2 keeps them on the item's food properties,
 * each with the chance it's given.
 */
public final class FoodEffects {
	private FoodEffects() {
	}

	/** Whether eating it makes you sick (a harmful effect: rotten flesh, raw chicken, spider eyes). */
	public static boolean harmful(ItemStack stack) {
		FoodProperties food = stack.getItem().getFoodProperties();
		if (food == null) {
			return false;
		}
		for (Pair<MobEffectInstance, Float> effect : food.getEffects()) {
			if (effect.getFirst().getEffect().getCategory() == MobEffectCategory.HARMFUL) {
				return true;
			}
		}
		return false;
	}

	/** The health a regeneration effect from eating it gives back (AppleSkin's estimate), or 0. */
	public static float regeneration(ItemStack stack) {
		FoodProperties food = stack.getItem().getFoodProperties();
		if (food == null) {
			return 0;
		}
		for (Pair<MobEffectInstance, Float> effect : food.getEffects()) {
			MobEffectInstance instance = effect.getFirst();
			if (instance.getEffect() == MobEffects.REGENERATION) {
				return (float) Math.floor(instance.getDuration() / (double) Math.max(50 >> instance.getAmplifier(), 1));
			}
		}
		return 0;
	}
}
