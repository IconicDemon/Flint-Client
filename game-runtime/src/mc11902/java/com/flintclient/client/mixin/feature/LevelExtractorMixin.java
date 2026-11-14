package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.gizmos.Gizmos;
import com.flintclient.client.control.platform.Mc263Team;
import com.flintclient.client.control.platform.Mc263World;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.debug.DebugRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flint's world drawing (Light Overlay, Chunk Borders, hitboxes, waypoint beams, team markers) each frame. Minecraft 1.19.2
 * has no gizmos: Flint's own ({@link Gizmos}) keep the calls and draw them right after Minecraft's debug renderers, which
 * 1.19.2 runs once per frame in the level's main pass.
 *
 * <p>1.19.2's world vertices carry the camera's turn (the level's pose stack, which the debug renderers are handed) and are
 * drawn with an identity model-view; only for its own debug renderers does it put that turn on the model-view as well. The
 * gizmos are drawn as the rest of the world is: with the level's pose and the model-view set back to identity while they
 * draw, so anything else of the frame's that their batches flush is drawn right too.
 */
@Mixin(DebugRenderer.class)
abstract class LevelExtractorMixin {
	@Inject(method = "render", at = @At("TAIL"))
	private void flint$gizmos(PoseStack pose, MultiBufferSource.BufferSource buffers, double camX, double camY, double camZ, CallbackInfo callback) {
		Minecraft mc = Minecraft.getInstance();
		Camera camera = mc.gameRenderer.getMainCamera();
		float partialTick = mc.getFrameTime();
		PoseStack modelView = RenderSystem.getModelViewStack();
		modelView.pushPose();
		modelView.setIdentity();
		RenderSystem.applyModelViewMatrix();
		try {
			Gizmos.collect(() -> {
				Mc263World.frame(camera, partialTick);
				Mc263Team.frame();
			}, pose, buffers, camera);
		} finally {
			modelView.popPose();
			RenderSystem.applyModelViewMatrix();
		}
	}
}
