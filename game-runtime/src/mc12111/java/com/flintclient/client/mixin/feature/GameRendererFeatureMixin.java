package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.McCompat;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.model.GameOptions;
import com.flintclient.client.control.platform.Mc263HurtCam;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hurt Cam (BetterHurtCam's behaviour): bobHurt's turns times the multiplier, skipped when off, one direction for the Old
 * type. Minimal view bobbing (a game option): the world's camera skips its walking bob, while the hand (bobbed in
 * renderItemInHand) keeps it. Minecraft 1.21.11 reads the hurt direction straight from the entity (26.x: from render states).
 */
@Mixin(GameRenderer.class)
abstract class GameRendererFeatureMixin {
	/** Animations' Disable hit direction and Hurt Cam's Old type: the hurt tilt always leans the same way. */
	@ModifyExpressionValue(method = "bobHurt", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getHurtDir()F"))
	private float flint$hitDirection(float direction) {
		return Mc263HurtCam.hurtDir(direction);
	}

	/** Hurt Cam: Hurtcam enabled off skips the hurt camera and the death roll. */
	@Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
	private void flint$hurtCamOff(CallbackInfo callback) {
		if (!Mc263HurtCam.bobHurt()) {
			callback.cancel();
		}
	}

	/** Hurt Cam: each of bobHurt's four turns (Axis.rotationDegrees) times the multiplier. */
	@ModifyArg(method = "bobHurt", at = @At(value = "INVOKE", target = "Lcom/mojang/math/Axis;rotationDegrees(F)Lorg/joml/Quaternionf;"))
	private float flint$hurtCamAxis(float degrees) {
		return Mc263HurtCam.angle(degrees);
	}

	@WrapWithCondition(method = "renderLevel", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/GameRenderer;bobView(Lcom/mojang/blaze3d/vertex/PoseStack;F)V"))
	private boolean flint$cameraBob(GameRenderer renderer, PoseStack pose, float partialTick) {
		return !ControlState.settings().gameBool(GameOptions.MINIMAL_BOBBING);
	}

	/**
	 * Waypoints: the world's projection (hurt tilt and bobbing in it) and view rotation this frame, as renderLevel hands
	 * them to the level renderer, so markers are projected exactly as the world is.
	 */
	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V"))
	private void flint$worldMatrices(LevelRenderer level, GraphicsResourceAllocator allocator, DeltaTracker delta, boolean outline, Camera camera,
		Matrix4f view, Matrix4f projection, Matrix4f culling, GpuBufferSlice fog, Vector4f fogColor, boolean sky, Operation<Void> original) {
		McCompat.worldMatrices(projection, view, camera.position());
		original.call(level, allocator, delta, outline, camera, view, projection, culling, fog, fogColor, sky);
	}
}
