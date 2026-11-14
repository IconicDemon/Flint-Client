package com.flintclient.client.mixin.cosmetics;

import com.flintclient.client.cosmetics.platform.Mc263Shields;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/** While a player's held item is drawn, a shield in it may be their Flint shield ({@link Mc263Shields}). */
@Mixin(ItemInHandLayer.class)
abstract class ItemInHandLayerCosmeticsMixin {
	@WrapMethod(method = "submitArmWithItem")
	private void flint$shieldOf(ArmedEntityRenderState state, ItemStackRenderState item, ItemStack stack, HumanoidArm arm, PoseStack pose,
		SubmitNodeCollector collector, int light, Operation<Void> original) {
		Mc263Shields.begin(state);
		try {
			original.call(state, item, stack, arm, pose, collector, light);
		} finally {
			Mc263Shields.end();
		}
	}
}
