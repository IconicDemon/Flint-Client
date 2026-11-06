package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263LookMods;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Shield Status on Minecraft 1.21.1, for a shield with a banner on it: its plate (drawn with the banner's patterns) takes
 * the tint too, as the plain plate does (ShieldTintMixin). The tint is only set while your held shield is drawn, so banners
 * keep their own look.
 */
@Mixin(BannerRenderer.class)
abstract class ShieldPatternTintMixin {
	@WrapOperation(method = "renderPatterns(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/client/resources/model/Material;ZLnet/minecraft/world/item/DyeColor;Lnet/minecraft/world/level/block/entity/BannerPatternLayers;Z)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/model/geom/ModelPart;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V"))
	private static void flint$tint(ModelPart part, PoseStack pose, VertexConsumer consumer, int light, int overlay, Operation<Void> original) {
		int color = Mc263LookMods.shieldColor(-1);
		if (color == -1) {
			original.call(part, pose, consumer, light, overlay);
		} else {
			part.render(pose, consumer, light, overlay, color);
		}
	}
}
