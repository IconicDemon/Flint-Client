package com.flintclient.client.mixin.feature;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Minecraft's own hurt tilt and view bobbing, as 1.21.11 has them (from the pose and the partial tick, not a camera render
 * state). Waypoints don't need them there: 1.21.11 hands the world's finished projection to the level renderer, and
 * GameRendererFeatureMixin keeps a copy ({@code McCompat.worldProjection}). Listed in the shared mixin config, so it exists
 * here too.
 */
@Mixin(GameRenderer.class)
public interface GameRendererBobInvoker {
	@Invoker("bobHurt")
	void flint$bobHurt(PoseStack poseStack, float partialTick);

	@Invoker("bobView")
	void flint$bobView(PoseStack poseStack, float partialTick);
}
