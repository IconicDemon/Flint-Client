package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Visuals;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Block Outline: the colour and width of the outline around the block you look at, and an optional face tint. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererOutlineMixin {
	@WrapOperation(method = "submitBlockOutline", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/LevelRenderer;submitHitOutline(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/rendertype/RenderType;Lnet/minecraft/client/renderer/state/level/BlockOutlineRenderState;IFZ)V"))
	private void flint$outline(LevelRenderer self, PoseStack poseStack, SubmitNodeCollector collector, RenderType type, BlockOutlineRenderState state,
		int color, float width, boolean translucent, Operation<Void> original) {
		if (!Mc263Visuals.outlineOn(state)) {
			original.call(self, poseStack, collector, type, state, color, width, translucent);
			return;
		}
		Mc263Visuals.fill(poseStack, collector, state);
		original.call(self, poseStack, collector, type, state, Mc263Visuals.outlineColor(), Mc263Visuals.outlineWidth(width), translucent);
	}
}
