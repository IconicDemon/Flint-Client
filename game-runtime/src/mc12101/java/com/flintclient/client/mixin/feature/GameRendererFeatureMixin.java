package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.McCompat;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.model.GameOptions;
import com.flintclient.client.control.platform.Mc263HurtCam;
import com.flintclient.client.control.platform.Mc263Visuals;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.effect.MobEffects;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 1.21.1's camera: Hurt Cam (BetterHurtCam's behaviour: bobHurt's turns times the multiplier, skipped when off,
 * one direction for the Old type) and Animations' hit direction, Minimal view bobbing (the world skips its
 * walking bob, the hand keeps it), Overlay's nausea (1.21.1 warps the camera for it), and the world's projection and view this
 * frame, as renderLevel hands them to the level renderer (Waypoints and TNT countdowns project with them).
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

	/** Overlay's Hide nausea: no warp while nausea is the cause (a portal's swirl keeps its own). */
	@ModifyExpressionValue(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;lerp(FFF)F"))
	private float flint$nausea(float intensity) {
		Minecraft mc = Minecraft.getInstance();
		return intensity > 0 && mc.player != null && mc.player.hasEffect(MobEffects.CONFUSION) && Mc263Visuals.hideNausea() ? 0 : intensity;
	}

	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"))
	private void flint$worldMatrices(LevelRenderer level, DeltaTracker delta, boolean outline, Camera camera, GameRenderer renderer, LightTexture light,
		Matrix4f view, Matrix4f projection, Operation<Void> original) {
		McCompat.worldMatrices(projection, view, camera.getPosition());
		original.call(level, delta, outline, camera, renderer, light, view, projection);
	}
}
