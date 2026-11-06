package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263World;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flint's per-frame world gizmos (Hitbox), right after Minecraft's own debug views emit theirs. Minecraft 1.21.11 does it in
 * LevelRenderer.renderLevel (26.x: in the level's extraction).
 */
@Mixin(LevelRenderer.class)
abstract class LevelExtractorMixin {
	@Inject(method = "renderLevel", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/debug/DebugRenderer;emitGizmos(Lnet/minecraft/client/renderer/culling/Frustum;DDDF)V", shift = At.Shift.AFTER))
	private void flint$gizmos(GraphicsResourceAllocator allocator, DeltaTracker delta, boolean blockOutline, Camera camera, Matrix4f view,
		Matrix4f projection, Matrix4f cullProjection, GpuBufferSlice fog, Vector4f fogColor, boolean sky, CallbackInfo callback) {
		Mc263World.frame(camera, delta.getGameTimeDeltaPartialTick(false));
		com.flintclient.client.control.platform.Mc263Team.frame();
	}
}
