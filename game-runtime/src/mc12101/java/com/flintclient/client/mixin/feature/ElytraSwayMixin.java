package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263MoreMods;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
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
 * Elytras on Minecraft 1.21.1: folded wings sway like a cape as their wearer moves: they lift back with forward speed (and a
 * fall) and spread a little, eased so they settle when you stop. 1.21.1 has no elytra animation state, so each wearer's sway
 * is kept here (stepped once a game tick, as later versions step it) and added to the wings' angles as the model is set up.
 * Flying is left to Minecraft.
 */
@Mixin(ElytraModel.class)
abstract class ElytraSwayMixin {
	/** Each wearer's sway now, before, and the tick it was stepped on. */
	@Unique
	private static final Map<LivingEntity, float[]> FLINT$SWAY = new WeakHashMap<>();

	@Shadow
	@Final
	private ModelPart leftWing;
	@Shadow
	@Final
	private ModelPart rightWing;

	@Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
	private void flint$sway(LivingEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float headYaw, float headPitch,
		CallbackInfo callback) {
		if (!Mc263MoreMods.elytraPhysics() || entity.isFallFlying()) {
			FLINT$SWAY.remove(entity);
			return;
		}
		float[] sway = FLINT$SWAY.computeIfAbsent(entity, e -> new float[] {0, 0, e.tickCount});
		if (sway[2] != entity.tickCount) {
			sway[2] = entity.tickCount;
			sway[1] = sway[0];
			Vec3 move = entity.getDeltaMovement();
			float yaw = entity.yBodyRot * Mth.DEG_TO_RAD;
			double forward = -move.x * Mth.sin(yaw) + move.z * Mth.cos(yaw);
			float target = (float) Mth.clamp(forward * 2.0, 0, 0.5) + (float) Mth.clamp(-move.y * 0.6, 0, 0.2);
			sway[0] += (target - sway[0]) * 0.3F;
		}
		float s = Mth.lerp(Mth.clamp(ageInTicks - entity.tickCount, 0, 1), sway[1], sway[0]);
		leftWing.xRot += s;
		leftWing.zRot -= 0.35F * s;
		rightWing.xRot = leftWing.xRot;
		rightWing.zRot = -leftWing.zRot;
	}
}
