package com.flintclient.client.mixin.cosmetics;

import com.flintclient.client.compat.state.SubmitNodeCollector;
import com.flintclient.client.cosmetics.platform.Mc263Shields;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.ShieldModel;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A shield held by someone wearing a Flint shield is drawn as that shield ({@link Mc263Shields}): 1.21.1 draws a shield in
 * BlockEntityWithoutLevelRenderer (1.21.4 moved it to ShieldSpecialRenderer), at the same point of the item's pose.
 */
@Mixin(BlockEntityWithoutLevelRenderer.class)
abstract class ShieldSpecialRendererCosmeticsMixin {
	@Shadow
	private ShieldModel shieldModel;

	@Inject(method = "renderByItem", at = @At("HEAD"), cancellable = true)
	private void flint$flintShield(ItemStack stack, ItemDisplayContext context, PoseStack pose, MultiBufferSource buffers, int light, int overlay,
		CallbackInfo callback) {
		if (stack.getItem() instanceof ShieldItem && shieldModel != null
			&& Mc263Shields.submit(shieldModel, pose, new SubmitNodeCollector(buffers), light, overlay, 0)) {
			callback.cancel();
		}
	}
}
