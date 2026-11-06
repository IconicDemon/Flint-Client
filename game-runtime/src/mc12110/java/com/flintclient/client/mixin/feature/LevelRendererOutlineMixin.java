package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.gizmos.Gizmos;
import com.flintclient.client.control.platform.Mc263Visuals;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.state.BlockOutlineRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Block Outline on Minecraft 1.21.10: the hit outline in the module's colour and thickness, then its tint over the
 * block's faces. 1.21.10 draws the outline as plain lines of one width (no width per outline), so a thicker or thinner
 * one is drawn as Flint's own camera-facing strips; the module's default thickness keeps Minecraft's lines.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererOutlineMixin {
	@WrapOperation(method = "renderBlockOutline", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/LevelRenderer;renderHitOutline(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;DDDLnet/minecraft/client/renderer/state/BlockOutlineRenderState;I)V"))
	private void flint$outline(LevelRenderer self, PoseStack poseStack, VertexConsumer lines, double camX, double camY, double camZ,
		BlockOutlineRenderState state, int color, Operation<Void> original, @Local(argsOnly = true) MultiBufferSource.BufferSource buffers) {
		if (!Mc263Visuals.outlineOn(state)) {
			original.call(self, poseStack, lines, camX, camY, camZ, state, color);
			return;
		}
		// Minecraft's own outline width on this window (its lines' render state).
		float vanilla = Math.max(2.5F, Minecraft.getInstance().getWindow().getWidth() / 1920.0F * 2.5F);
		float width = Mc263Visuals.outlineWidth(vanilla);
		BlockPos pos = state.pos();
		if (Math.abs(width - vanilla) < 0.01F) {
			original.call(self, poseStack, lines, camX, camY, camZ, state, Mc263Visuals.outlineColor());
		} else {
			// After the outline's lines: asking the buffers for the strips' type ends their batch, which is written by now.
			VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
			Matrix4f matrix = poseStack.last().pose();
			int outline = Mc263Visuals.outlineColor();
			double ox = pos.getX() - camX;
			double oy = pos.getY() - camY;
			double oz = pos.getZ() - camZ;
			state.shape().forAllEdges((x0, y0, z0, x1, y1, z1) ->
				Gizmos.drawLine(quads, matrix, new Vec3(ox + x0, oy + y0, oz + z0), new Vec3(ox + x1, oy + y1, oz + z1), outline, width));
		}
		if (Mc263Visuals.fillOn()) {
			poseStack.pushPose();
			poseStack.translate(pos.getX() - camX, pos.getY() - camY, pos.getZ() - camZ);
			Mc263Visuals.fill(buffers.getBuffer(RenderType.debugQuads()), poseStack.last(), state, Mc263Visuals.fillColor());
			poseStack.popPose();
		}
	}
}
