package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.gizmos.Gizmos;
import com.flintclient.client.compat.state.BlockOutlineRenderState;
import com.flintclient.client.control.platform.Mc263Visuals;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Block Outline on Minecraft 1.21.1: the hit outline in the module's colour and thickness, then its tint over the block's
 * faces. 1.21.1 draws the outline as lines of one width, so a thicker or thinner one is drawn as Flint's own camera-facing
 * strips; the module's default thickness keeps Minecraft's lines, in the module's colour.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererOutlineMixin {
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
			PoseStack.Pose pose = poseStack.last();
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
				lines.addVertex(pose, (float) (x0 + ox), (float) (y0 + oy), (float) (z0 + oz)).setColor(r, g, b, a).setNormal(pose, dx, dy, dz);
				lines.addVertex(pose, (float) (x1 + ox), (float) (y1 + oy), (float) (z1 + oz)).setColor(r, g, b, a).setNormal(pose, dx, dy, dz);
			});
		} else {
			VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
			Matrix4f matrix = poseStack.last().pose();
			shape.forAllEdges((x0, y0, z0, x1, y1, z1) ->
				Gizmos.drawLine(quads, matrix, new Vec3(ox + x0, oy + y0, oz + z0), new Vec3(ox + x1, oy + y1, oz + z1), outline, width));
		}
		if (Mc263Visuals.fillOn()) {
			poseStack.pushPose();
			poseStack.translate(ox, oy, oz);
			Mc263Visuals.fill(buffers.getBuffer(RenderType.debugQuads()), poseStack.last(), state, Mc263Visuals.fillColor());
			poseStack.popPose();
		}
	}
}
