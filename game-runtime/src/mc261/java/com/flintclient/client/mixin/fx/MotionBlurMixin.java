package com.flintclient.client.mixin.fx;

import com.flintclient.client.control.fx.MotionBlur;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Flint's Motion Blur ({@link MotionBlur}) in {@code GameRenderer.renderLevel} (Minecraft 26.1, 26.1.1 and 26.1.2), once per rendered world frame:
 * {@link MotionBlur#beginFrame} as the world is handed to the level renderer (with renderLevel's own {@code projectionMatrix}:
 * view bobbing, the hurt tilt and nausea included, as Natural Motion Blur captures it), and {@link MotionBlur#late} just before
 * it clears the depth buffer for the hand: the main target then holds the finished world image and the world's depth; the hand,
 * held item and screen overlays, glowing outlines, Minecraft's post effects and the GUI are drawn afterwards, so they're never
 * blurred. The early pass between the terrain and the players and mobs is {@code MotionBlurLevelMixin}'s.
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
			+ "Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;"
			+ "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZLnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;)V"))
	private boolean flint$motionBlurBegin(LevelRenderer level, GraphicsResourceAllocator allocator, DeltaTracker delta, boolean outline,
		CameraRenderState camera, Matrix4fc view, GpuBufferSlice fog, Vector4f fogColor, boolean sky, ChunkSectionsToRender sections,
		@Local Matrix4f projectionMatrix) {
		MotionBlur.beginFrame(Minecraft.getInstance().getMainRenderTarget(), camera.isPanoramicMode, view, projectionMatrix, camera.pos);
		return true;
	}

	@WrapWithCondition(method = "renderLevel", require = 0, at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"))
	private boolean flint$motionBlur(CommandEncoder encoder, GpuTexture depth, double clearDepth) {
		MotionBlur.late(Minecraft.getInstance().getMainRenderTarget());
		return true;
	}
}
