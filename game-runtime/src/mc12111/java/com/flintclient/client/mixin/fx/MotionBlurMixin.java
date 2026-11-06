package com.flintclient.client.mixin.fx;

import com.flintclient.client.control.fx.MotionBlur;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Flint's Motion Blur ({@link MotionBlur}) in {@code GameRenderer.renderLevel} (Minecraft 1.21.11 and 1.21.10), once per
 * rendered world frame: {@link MotionBlur#beginFrame} as the world is handed to the level renderer (its view rotation, its
 * projection with view bobbing, the hurt tilt and nausea in it, as Natural Motion Blur captures it, and the camera), and
 * {@link MotionBlur#late} just before it clears the depth buffer for the hand: the main target then holds the finished world
 * image and the world's depth; the hand, held item and screen overlays, glowing outlines, Minecraft's post effects and the GUI
 * are drawn afterwards, so they're never blurred. (Hooked at {@code renderItemInHand}, as it once was, the depth was already
 * cleared: velocity blur saw no motion and drew nothing.) The early pass between the terrain and the players and mobs is
 * {@code MotionBlurLevelMixin}'s.
 *
 * <p>Both hooks wrap the call in a condition that is always true rather than injecting a callback, so nothing is allocated per
 * frame (same pattern as {@code HudMixin}). A mod that {@code @Redirect}s one of the calls would stop that hook applying (it is
 * optional, so the game still starts; the blur then never runs).
 */
@Mixin(GameRenderer.class)
abstract class MotionBlurMixin {
	// require = 0: if another mod takes over this call, Minecraft still starts and the blur simply never runs.
	@WrapWithCondition(method = "renderLevel", require = 0, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;"
			+ "Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;"
			+ "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V"))
	private boolean flint$motionBlurBegin(LevelRenderer level, GraphicsResourceAllocator allocator, DeltaTracker delta, boolean outline, Camera camera,
		Matrix4f view, Matrix4f projection, Matrix4f culling, GpuBufferSlice fog, Vector4f fogColor, boolean sky) {
		MotionBlur.beginFrame(Minecraft.getInstance().getMainRenderTarget(), ((GameRenderer) (Object) this).isPanoramicMode(), view, projection,
			camera.position());
		return true;
	}

	@WrapWithCondition(method = "renderLevel", require = 0, at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"))
	private boolean flint$motionBlur(CommandEncoder encoder, GpuTexture depth, double clearDepth) {
		MotionBlur.late(Minecraft.getInstance().getMainRenderTarget());
		return true;
	}
}
