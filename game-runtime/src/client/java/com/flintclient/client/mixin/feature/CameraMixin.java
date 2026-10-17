package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Features;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Zoom and Freelook: the camera reads the zoom and freelook keys once per frame before it is placed, takes freelook's
 * angles instead of the player's while freelook is on (the third-person distance and mirroring then follow them), and
 * divides its field of view by the zoom. Wrapping and returning values allocates nothing.
 */
@Mixin(Camera.class)
abstract class CameraMixin {
	@Inject(method = "alignWithEntity", at = @At("HEAD"))
	private void flint$beforeCamera(float partialTicks, CallbackInfo callback) {
		Mc263Features.beforeCamera();
	}

	@WrapOperation(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F"))
	private float flint$freelookYaw(Entity entity, float partialTicks, Operation<Float> original) {
		return Mc263Features.cameraYaw(original.call(entity, partialTicks));
	}

	@WrapOperation(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewXRot(F)F"))
	private float flint$freelookPitch(Entity entity, float partialTicks, Operation<Float> original) {
		return Mc263Features.cameraPitch(original.call(entity, partialTicks));
	}

	@ModifyReturnValue(method = "calculateFov", at = @At("RETURN"))
	private float flint$zoom(float fov) {
		return Mc263Features.fov(fov);
	}
}
