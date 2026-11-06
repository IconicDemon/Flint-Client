package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263LookMods;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Shield Status on Minecraft 1.21.1: the shield drawn in its tint while your held shield is drawn; Minecraft's white
 * otherwise. 1.21.1 draws the shield's handle and plain plate as model parts in renderByItem with no colour (white), so
 * those parts are drawn with the tint instead.
 */
@Mixin(BlockEntityWithoutLevelRenderer.class)
abstract class ShieldTintMixin {
	@WrapOperation(method = "renderByItem", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/model/geom/ModelPart;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V"))
	private void flint$tint(ModelPart part, PoseStack pose, VertexConsumer consumer, int light, int overlay, Operation<Void> original) {
		int color = Mc263LookMods.shieldColor(-1);
		if (color == -1) {
			original.call(part, pose, consumer, light, overlay);
		} else {
			part.render(pose, consumer, light, overlay, color);
		}
	}
}
