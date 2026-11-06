package com.flintclient.client.mixin.bridge;

import com.flintclient.client.compat.Bridges;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Minecraft 1.21.11 builds each frame's cull frustum inside renderLevel; the Camera bridge (getCullFrustum) answers with it. */
@Mixin(LevelRenderer.class)
abstract class CullFrustumMixin {
	@ModifyReturnValue(method = "prepareCullFrustum", at = @At("RETURN"))
	private Frustum flint$keep(Frustum frustum) {
		Bridges.cullFrustum = frustum;
		return frustum;
	}
}
