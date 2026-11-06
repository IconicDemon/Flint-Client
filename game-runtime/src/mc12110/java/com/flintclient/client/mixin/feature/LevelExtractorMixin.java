package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.gizmos.Gizmos;
import com.flintclient.client.control.platform.Mc263Team;
import com.flintclient.client.control.platform.Mc263World;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.DebugRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flint's world drawing (Light Overlay, Chunk Borders, hitboxes, waypoint beams, team markers) each frame. Minecraft
 * 1.21.10 has no gizmos: Flint's own ({@link Gizmos}) keep the calls and draw them right after Minecraft's debug
 * renderers, in the level's main pass (the opaque call; the translucent one comes later).
 */
@Mixin(DebugRenderer.class)
abstract class LevelExtractorMixin {
	@Inject(method = "render", at = @At("TAIL"))
	private void flint$gizmos(PoseStack pose, Frustum frustum, MultiBufferSource.BufferSource buffers, double camX, double camY, double camZ,
		boolean translucent, CallbackInfo callback) {
		if (translucent) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Camera camera = mc.gameRenderer.getMainCamera();
		float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Gizmos.collect(() -> {
			Mc263World.frame(camera, partialTick);
			Mc263Team.frame();
		}, pose, buffers, camera);
	}
}
