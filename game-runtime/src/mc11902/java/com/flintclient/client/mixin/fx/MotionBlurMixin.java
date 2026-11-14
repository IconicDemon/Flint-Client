package com.flintclient.client.mixin.fx;

import com.flintclient.client.compat.MatrixBridge;
import com.flintclient.client.control.fx.MotionBlur;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix4f;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Flint's Motion Blur ({@link MotionBlur}) in {@code GameRenderer.renderLevel} (Minecraft 1.19.2), once per rendered world
 * frame: {@link MotionBlur#beginFrame} as the world is handed to the level renderer (its view rotation, which 1.19.2 keeps in
 * the pose stack it hands over, its projection with view bobbing, the hurt tilt and nausea in it, as Natural Motion Blur
 * captures it, and the camera; 1.19.2's own matrices are copied as JOML's), and {@link MotionBlur#late} at the {@code "hand"}
 * profiler step, before the depth buffer is cleared for the hand: the main target then holds the finished world image and the
 * world's depth; the hand, overlays, Minecraft's post effects and the GUI are drawn afterwards, so they're never blurred. The
 * early pass between the terrain and the entities is {@code MotionBlurLevelMixin}'s.
 *
 * <p>Both hooks wrap the call in a condition that is always true rather than injecting a callback, so nothing is allocated per
 * frame. require = 0: if another mod takes over a call, the game still starts and the blur simply never runs.
 */
@Mixin(GameRenderer.class)
abstract class MotionBlurMixin {
	@Unique
	private static final org.joml.Matrix4f FLINT$VIEW = new org.joml.Matrix4f();
	@Unique
	private static final org.joml.Matrix4f FLINT$PROJECTION = new org.joml.Matrix4f();

	@WrapWithCondition(method = "renderLevel", require = 0, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;"
			+ "Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lcom/mojang/math/Matrix4f;)V"))
	private boolean flint$motionBlurBegin(LevelRenderer level, PoseStack pose, float partialTick, long nanos, boolean outline, Camera camera,
		GameRenderer renderer, LightTexture light, Matrix4f projection) {
		MotionBlur.beginFrame(Minecraft.getInstance().getMainRenderTarget(), renderer.isPanoramicMode(),
			((MatrixBridge) (Object) pose.last().pose()).flint$get(FLINT$VIEW),
			((MatrixBridge) (Object) projection).flint$get(FLINT$PROJECTION), camera.getPosition());
		return true;
	}

	@WrapWithCondition(method = "renderLevel", require = 0, at = @At(value = "INVOKE_STRING",
		target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=hand"))
	private boolean flint$motionBlur(ProfilerFiller profiler, String name) {
		MotionBlur.late(Minecraft.getInstance().getMainRenderTarget());
		return true;
	}
}
