package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Visuals;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.BlockOutlineRenderState;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Block Outline: the colour and width of the outline around the block you look at, and an optional face tint. Minecraft
 * Minecraft 1.21.11 (as 26.1) draws the outline straight into a buffer ({@code renderBlockOutline} → {@code renderHitOutline}, which places it
 * relative to the camera itself), so the tint goes in the same buffers right after it, at the block.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererOutlineMixin {
	@WrapOperation(method = "renderBlockOutline", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/LevelRenderer;renderHitOutline(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;DDDLnet/minecraft/client/renderer/state/BlockOutlineRenderState;IF)V"))
	private void flint$outline(LevelRenderer self, PoseStack poseStack, VertexConsumer lines, double camX, double camY, double camZ,
		BlockOutlineRenderState state, int color, float width, Operation<Void> original, @Local(argsOnly = true) MultiBufferSource.BufferSource buffers) {
		if (!Mc263Visuals.outlineOn(state)) {
			original.call(self, poseStack, lines, camX, camY, camZ, state, color, width);
			return;
		}
		original.call(self, poseStack, lines, camX, camY, camZ, state, Mc263Visuals.outlineColor(), Mc263Visuals.outlineWidth(width));
		if (Mc263Visuals.fillOn()) {
			// After the outline: asking the buffers for the tint's type ends the outline's batch, which is written by now.
			BlockPos pos = state.pos();
			poseStack.pushPose();
			poseStack.translate(pos.getX() - camX, pos.getY() - camY, pos.getZ() - camZ);
			Mc263Visuals.fill(buffers.getBuffer(RenderTypes.debugFilledBox()), poseStack.last(), state, Mc263Visuals.fillColor());
			poseStack.popPose();
		}
	}
}
