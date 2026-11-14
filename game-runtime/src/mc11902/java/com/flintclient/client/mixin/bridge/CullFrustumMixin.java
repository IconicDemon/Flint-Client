package com.flintclient.client.mixin.bridge;

import com.flintclient.client.compat.Bridges;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix4f;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Minecraft 1.19.2: the frustum LevelRenderer culls this frame's world with, kept for {@link Bridges.Camera}. */
@Mixin(LevelRenderer.class)
abstract class CullFrustumMixin {
	@Shadow
	private Frustum cullingFrustum;

	@Inject(method = "prepareCullFrustum", at = @At("TAIL"))
	private void flint$keep(PoseStack pose, Vec3 camera, Matrix4f projection, CallbackInfo callback) {
		Bridges.cullFrustum = cullingFrustum;
	}
}
