package com.flintclient.client.mixin.cosmetics;

import com.flintclient.client.compat.state.RenderStates;
import com.flintclient.client.cosmetics.platform.Mc263Shields;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/** While a player's held item is drawn (1.21.1's held-item layer), their Flint shield is the one drawn ({@link Mc263Shields}). */
@Mixin(ItemInHandLayer.class)
abstract class ItemInHandLayerCosmeticsMixin {
	@WrapMethod(method = "renderArmWithItem")
	private void flint$shieldOf(LivingEntity entity, ItemStack stack, ItemDisplayContext context, HumanoidArm arm, PoseStack pose,
		MultiBufferSource buffers, int light, Operation<Void> original) {
		Mc263Shields.begin(RenderStates.peek(entity));
		try {
			original.call(entity, stack, context, arm, pose, buffers, light);
		} finally {
			Mc263Shields.end();
		}
	}
}
