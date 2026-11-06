package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Camera;
import com.flintclient.client.control.platform.Mc263Features;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Zoom and Freelook: the camera reads the zoom and freelook keys once per frame before it is placed, takes freelook's
 * angles instead of the player's while freelook is on (the third-person distance and mirroring then follow them), and
 * divides its field of view by the zoom. Wrapping and returning values allocates nothing.
 *
 * <p>The Camera module, in third person: just before Minecraft pulls the camera back from you, it turns the camera around
 * you, moves it sideways and up (stopping short of a wall), and scales the distance; Minecraft's own wall check then
 * shortens the pull as usual. Only a set offset allocates (a ray, as Minecraft's check does).
 */
@Mixin(Camera.class)
abstract class CameraMixin {
	@Shadow
	private Level level;
	@Shadow
	private Entity entity;
	@Shadow
	private Vec3 position;
	@Shadow
	@Final
	private Vector3f up;
	@Shadow
	@Final
	private Vector3f left;
	@Shadow
	private float xRot;
	@Shadow
	private float yRot;

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	protected abstract void setPosition(Vec3 position);

	@WrapOperation(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getMaxZoom(F)F"))
	private float flint$thirdPerson(Camera camera, float distance, Operation<Float> original) {
		Mc263Camera.ThirdPerson third = Mc263Camera.thirdPerson();
		if (third == null) {
			return original.call(camera, distance);
		}
		if (third.pitch != 0 || third.yaw != 0) {
			setRotation(yRot + third.yaw, Mth.clamp(xRot + third.pitch, -90F, 90F));
		}
		if (third.right != 0 || third.up != 0) {
			// The camera's left vector points left; right is its opposite.
			double dx = -left.x() * third.right + up.x() * third.up;
			double dy = -left.y() * third.right + up.y() * third.up;
			double dz = -left.z() * third.right + up.z() * third.up;
			Vec3 to = position.add(dx, dy, dz);
			BlockHitResult hit = level.clip(new ClipContext(position, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, entity));
			if (hit.getType() != HitResult.Type.MISS) {
				double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
				double k = Math.max(0, hit.getLocation().distanceTo(position) - 0.2) / length;
				to = position.add(dx * k, dy * k, dz * k);
			}
			setPosition(to);
		}
		return original.call(camera, distance * third.scale);
	}
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

	/** Zoom's Affect hand FOV: the hand and held item zoom with the world. */
	@ModifyReturnValue(method = "calculateHudFov", at = @At("RETURN"))
	private float flint$zoomHand(float fov) {
		return com.flintclient.client.control.platform.Mc263Zoom.hudFov(fov);
	}
}
