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
 * Shield Status on Minecraft 1.19.2: the shield drawn in its tint while your held shield is drawn; Minecraft's white
 * otherwise. 1.19.2 draws the shield's handle and plain plate as model parts in renderByItem, in white given as four floats
 * (the only parts it draws there that way), so those parts are drawn with the tint instead.
 */
@Mixin(BlockEntityWithoutLevelRenderer.class)
abstract class ShieldTintMixin {
	@WrapOperation(method = "renderByItem", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/model/geom/ModelPart;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V"))
	private void flint$tint(ModelPart part, PoseStack pose, VertexConsumer consumer, int light, int overlay, float red, float green, float blue,
		float alpha, Operation<Void> original) {
		int color = Mc263LookMods.shieldColor(-1);
		if (color == -1) {
			original.call(part, pose, consumer, light, overlay, red, green, blue, alpha);
		} else {
			original.call(part, pose, consumer, light, overlay, red * (color >> 16 & 0xFF) / 255.0F, green * (color >> 8 & 0xFF) / 255.0F,
				blue * (color & 0xFF) / 255.0F, alpha * (color >>> 24) / 255.0F);
		}
	}
}
