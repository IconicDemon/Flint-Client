package com.flintclient.client.mixin.fx;

import com.flintclient.client.control.fx.MotionBlur;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Flint's Motion Blur ({@link MotionBlur}) in {@code GameRenderer.renderLevel}, once per rendered world frame:
 * <ul>
 * <li>As the world is handed to the level renderer ({@code LevelRenderer.render}): {@link MotionBlur#beginFrame} with the camera
 * and the projection the world is drawn with (renderLevel's own {@code projectionMatrix}: view bobbing, the hurt tilt and
 * nausea included, as Natural Motion Blur captures it).</li>
 * <li>Just before {@code render3dHud}: {@link MotionBlur#late}. The main target then holds the finished world image and depth;
 * the hand, held item and screen overlays (fire, pumpkin, water), glowing outlines, Minecraft's post effects and the GUI are
 * drawn afterwards, so they're never blurred.</li>
 * </ul>
 * The early pass between the terrain and the players and mobs is {@code MotionBlurLevelMixin}'s.
 *
 * <p>Both hooks wrap the call in a condition that is always true rather than injecting a callback, so nothing is allocated per
 * frame (same pattern as {@code HudMixin}). If another mod also wraps a call and its condition says no while being outermost,
 * the blur skips that frame; a mod that {@code @Redirect}s one would stop the hook applying (it is optional, so the game still
 * starts; the blur then never runs). No mod in the tested stack (Sodium 0.9.2 and its bundled Fabric rendering modules, Iris
 * 1.11.7, ImmediatelyFast, Dynamic FPS, Lithium, EntityCulling, FerriteCore, Fabric API) references these calls.
 */
@Mixin(GameRenderer.class)
abstract class MotionBlurMixin {
	// require = 0: if another mod takes over this call, Minecraft still starts and the blur simply never runs.
	@WrapWithCondition(method = "renderLevel", require = 0, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;"
			+ "ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"
			+ "Lorg/joml/Vector4f;ZZ)V"))
	private boolean flint$motionBlurBegin(LevelRenderer level, GraphicsResourceAllocator allocator, boolean outline, CameraRenderState camera,
		GpuBufferSlice fog, Vector4f fogColor, boolean sky, boolean consistentDepth, @Local Matrix4f projectionMatrix) {
		MotionBlur.beginFrame(((GameRenderer) (Object) this).mainRenderTarget(), camera.isPanoramicMode, camera.viewRotationMatrix, projectionMatrix,
			camera.pos);
		return true;
	}

	@WrapWithCondition(method = "renderLevel", require = 0, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
			+ "Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
	private boolean flint$motionBlur(GameRenderer self, CameraRenderState camera, PlayerRenderState player, OptionsRenderState options,
		boolean consistentDepth) {
		MotionBlur.late(self.mainRenderTarget());
		return true;
	}
}
