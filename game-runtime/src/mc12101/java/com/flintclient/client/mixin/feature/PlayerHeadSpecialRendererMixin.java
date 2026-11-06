package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Skins;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.SkullModelBase;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 3D Skins on Minecraft 1.21.1: a player head item (in a slot or in hand) is always close enough for its 3D hat. 1.21.1 draws
 * head items in BlockEntityWithoutLevelRenderer.renderByItem, through renderSkull.
 */
@Mixin(BlockEntityWithoutLevelRenderer.class)
abstract class PlayerHeadSpecialRendererMixin {
	@WrapOperation(method = "renderByItem", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/blockentity/SkullBlockRenderer;renderSkull(Lnet/minecraft/core/Direction;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/model/SkullModelBase;Lnet/minecraft/client/renderer/RenderType;)V"))
	private void flint$item(Direction direction, float rotation, float animation, PoseStack pose, MultiBufferSource buffers, int light,
		SkullModelBase model, RenderType type, Operation<Void> original) {
		Mc263Skins.headItem(true);
		try {
			original.call(direction, rotation, animation, pose, buffers, light, model, type);
		} finally {
			Mc263Skins.headItem(false);
		}
	}
}
