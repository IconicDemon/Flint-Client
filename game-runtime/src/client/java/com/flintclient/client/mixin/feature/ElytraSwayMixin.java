package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263MoreMods;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ElytraAnimationState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Elytras: folded wings sway like a cape as their wearer moves: they lift back with forward speed (and a fall) and spread a
 * little, eased so they settle when you stop. The sway is added to the angles as drawn, never to Minecraft's own state.
 * Flying is left to Minecraft.
 */
@Mixin(ElytraAnimationState.class)
abstract class ElytraSwayMixin {
	@Shadow
	@Final
	private LivingEntity entity;
	@Unique
	private float flint$sway;
	@Unique
	private float flint$swayOld;

	@Inject(method = "tick", at = @At("TAIL"))
	private void flint$sway(CallbackInfo callback) {
		flint$swayOld = flint$sway;
		if (!Mc263MoreMods.elytraPhysics() || entity.isFallFlying()) {
			flint$sway = 0;
			return;
		}
		Vec3 move = entity.getDeltaMovement();
		float yaw = entity.yBodyRot * Mth.DEG_TO_RAD;
		double forward = -move.x * Mth.sin(yaw) + move.z * Mth.cos(yaw);
		float target = (float) Mth.clamp(forward * 2.0, 0, 0.5) + (float) Mth.clamp(-move.y * 0.6, 0, 0.2);
		flint$sway += (target - flint$sway) * 0.3F;
	}

	@ModifyReturnValue(method = "getRotX", at = @At("RETURN"))
	private float flint$rotX(float value, float partialTick) {
		return value + Mth.lerp(partialTick, flint$swayOld, flint$sway);
	}

	@ModifyReturnValue(method = "getRotZ", at = @At("RETURN"))
	private float flint$rotZ(float value, float partialTick) {
		return value - 0.35F * Mth.lerp(partialTick, flint$swayOld, flint$sway);
	}
}
