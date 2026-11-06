package com.flintclient.client.mixin.feature;

import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Saturation: how close the next hunger point is (exhaustion, 0-4), which Minecraft keeps but never shows or sends. */
@Mixin(FoodData.class)
public interface FoodDataAccessor {
	@Accessor("exhaustionLevel")
	float flint$exhaustion();
}
