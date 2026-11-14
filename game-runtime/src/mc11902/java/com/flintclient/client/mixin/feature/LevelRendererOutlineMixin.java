package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.MatrixBridge;
import com.flintclient.client.compat.gizmos.Gizmos;
import com.flintclient.client.compat.render.DebugRenderTypes;
import com.flintclient.client.compat.state.BlockOutlineRenderState;
import com.flintclient.client.control.platform.Mc263Visuals;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Matrix3f;
import com.mojang.math.Matrix4f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Block Outline on Minecraft 1.19.2: the hit outline in the module's colour and thickness, then its tint over the block's
 * faces. 1.19.2 draws the outline as lines of one width, so a thicker or thinner one is drawn as Flint's own camera-facing
 * strips (into 1.20's debug quads, compat.render.DebugRenderTypes); the module's default thickness keeps Minecraft's lines, in
 * the module's colour.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererOutlineMixin {
	/** The pose as JOML's, for the strips (render thread). */
	@Unique
	private static final org.joml.Matrix4f FLINT$MATRIX = new org.joml.Matrix4f();

	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/LevelRenderer;renderHitOutline(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V"))
	private void flint$outline(LevelRenderer self, PoseStack poseStack, VertexConsumer lines, Entity entity, double camX, double camY, double camZ,
		BlockPos pos, BlockState block, Operation<Void> original) {
		Minecraft mc = Minecraft.getInstance();
		VoxelShape shape = block.getShape(mc.level, pos, CollisionContext.of(entity));
		BlockOutlineRenderState state = new BlockOutlineRenderState(pos, false, false, shape);
		if (!Mc263Visuals.outlineOn(state)) {
			original.call(self, poseStack, lines, entity, camX, camY, camZ, pos, block);
			return;
		}
		// Minecraft's own outline width on this window (its lines' render state).
		float vanilla = Math.max(2.5F, mc.getWindow().getWidth() / 1920.0F * 2.5F);
		float width = Mc263Visuals.outlineWidth(vanilla);
		int outline = Mc263Visuals.outlineColor();
		double ox = pos.getX() - camX;
		double oy = pos.getY() - camY;
		double oz = pos.getZ() - camZ;
		MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
		if (Math.abs(width - vanilla) < 0.01F) {
			Matrix4f matrix = poseStack.last().pose();
			Matrix3f normal = poseStack.last().normal();
			float r = (outline >> 16 & 0xFF) / 255.0F;
			float g = (outline >> 8 & 0xFF) / 255.0F;
			float b = (outline & 0xFF) / 255.0F;
			float a = (outline >>> 24) / 255.0F;
			shape.forAllEdges((x0, y0, z0, x1, y1, z1) -> {
				float dx = (float) (x1 - x0);
				float dy = (float) (y1 - y0);
				float dz = (float) (z1 - z0);
				float length = Mth.sqrt(dx * dx + dy * dy + dz * dz);
				dx /= length;
				dy /= length;
				dz /= length;
				lines.vertex(matrix, (float) (x0 + ox), (float) (y0 + oy), (float) (z0 + oz)).color(r, g, b, a).normal(normal, dx, dy, dz).endVertex();
				lines.vertex(matrix, (float) (x1 + ox), (float) (y1 + oy), (float) (z1 + oz)).color(r, g, b, a).normal(normal, dx, dy, dz).endVertex();
			});
		} else {
			VertexConsumer quads = buffers.getBuffer(DebugRenderTypes.debugQuads());
			org.joml.Matrix4f matrix = ((MatrixBridge) (Object) poseStack.last().pose()).flint$get(FLINT$MATRIX);
			shape.forAllEdges((x0, y0, z0, x1, y1, z1) ->
				Gizmos.drawLine(quads, matrix, new Vec3(ox + x0, oy + y0, oz + z0), new Vec3(ox + x1, oy + y1, oz + z1), outline, width));
		}
		if (Mc263Visuals.fillOn()) {
			poseStack.pushPose();
			poseStack.translate(ox, oy, oz);
			Mc263Visuals.fill(buffers.getBuffer(DebugRenderTypes.debugQuads()), poseStack.last(), state, Mc263Visuals.fillColor());
			poseStack.popPose();
		}
		// Drawn now, while the model-view is the world's: 1.19.2's vertices carry the camera's turn, and for the debug renderers
		// that come next it puts that turn on the model-view too (a batch flushed there would be turned twice).
		buffers.endBatch(DebugRenderTypes.debugQuads());
	}
}
