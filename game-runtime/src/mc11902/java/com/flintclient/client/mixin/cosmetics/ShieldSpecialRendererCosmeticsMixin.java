package com.flintclient.client.mixin.cosmetics;

import com.flintclient.client.cosmetics.platform.Mc263Shields;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.ShieldModel;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A shield held by someone wearing a Flint shield is drawn as that shield ({@link Mc263Shields}); 1.19 draws shields here. */
@Mixin(BlockEntityWithoutLevelRenderer.class)
abstract class ShieldSpecialRendererCosmeticsMixin {
	@Shadow
	private ShieldModel shieldModel;

	@Inject(method = "renderByItem", at = @At("HEAD"), cancellable = true)
	private void flint$flintShield(ItemStack stack, ItemTransforms.TransformType context, PoseStack pose, MultiBufferSource buffers, int light,
		int overlay, CallbackInfo callback) {
		if (stack.is(Items.SHIELD) && Mc263Shields.render(shieldModel, pose, buffers, light, overlay)) {
			callback.cancel();
		}
	}
}
