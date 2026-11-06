package com.flintclient.client.mixin.bridge;

import com.flintclient.client.compat.Bridges;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 1.21.1 makes each frame's cull frustum in prepareCullFrustum (the same object is then moved to the camera as the
 * world is drawn); the Camera bridge (getCullFrustum) answers with it.
 */
@Mixin(LevelRenderer.class)
abstract class CullFrustumMixin {
	@Shadow
	private Frustum cullingFrustum;

	@Inject(method = "prepareCullFrustum", at = @At("TAIL"))
	private void flint$keep(Vec3 camera, Matrix4f view, Matrix4f projection, CallbackInfo callback) {
		Bridges.cullFrustum = cullingFrustum;
	}
}
