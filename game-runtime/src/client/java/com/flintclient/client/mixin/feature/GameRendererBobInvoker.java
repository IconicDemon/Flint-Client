package com.flintclient.client.mixin.feature;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Waypoints: Minecraft's own hurt tilt and view bobbing, so markers drawn over the world are projected exactly as the
 * world is (the camera's projection alone leaves them bobbing against it while you walk).
 */
@Mixin(GameRenderer.class)
public interface GameRendererBobInvoker {
	@Invoker("bobHurt")
	void flint$bobHurt(CameraRenderState cameraState, PoseStack poseStack);

	@Invoker("bobView")
	void flint$bobView(CameraRenderState cameraState, PoseStack poseStack);
}
