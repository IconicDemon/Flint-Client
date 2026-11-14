package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.misc.HitConfirm;
import com.flintclient.client.control.platform.Mc263Features;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Combat feedback from what the server tells the client: hurt flashes (a hit of yours landing; a hit on you starts Jump
 * Reset's count and is your hurt animation) and deaths (entity event 3); and your jumps, for Jump Reset. Minecraft 1.19.2
 * has no damage events (1.19.4 added them): its server only makes a hurt entity flash (entity events 2, 33, 36, 37, 44 and
 * 57), naming neither the attacker nor a direction, so the flash of the entity you have just attacked is your hit landing
 * ({@link HitConfirm}) and a flash of your own is your hurt animation, without a direction. Client-side entities only; the
 * integrated server's copies never reach these methods' client paths because only {@code ClientPacketListener} calls them
 * on the client.
 */
@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
	// 1.19.2 keys the effects by the effect itself (1.20.5 by its registry holder).
	@Shadow
	@Final
	private Map<MobEffect, MobEffectInstance> activeEffects;
	@Unique
	private @Nullable MobEffectInstance flint$previousEffect;

	/** Status Effect Bars: an effect sent again at the same level keeps the longer full duration it started with. */
	@Inject(method = "forceAddEffect", at = @At("HEAD"))
	private void flint$effectBefore(MobEffectInstance effect, net.minecraft.world.entity.Entity source, CallbackInfo callback) {
		flint$previousEffect = activeEffects.get(effect.getEffect());
	}

	@Inject(method = "forceAddEffect", at = @At("TAIL"))
	private void flint$effectAfter(MobEffectInstance effect, net.minecraft.world.entity.Entity source, CallbackInfo callback) {
		MobEffectInstance previous = flint$previousEffect;
		flint$previousEffect = null;
		if (previous != null && previous != effect && previous.getAmplifier() == effect.getAmplifier()) {
			com.flintclient.client.control.platform.Mc263EffectBars.Timed now = (com.flintclient.client.control.platform.Mc263EffectBars.Timed) effect;
			com.flintclient.client.control.platform.Mc263EffectBars.Timed before = (com.flintclient.client.control.platform.Mc263EffectBars.Timed) previous;
			if (now.flint$maxDuration() < before.flint$maxDuration()) {
				now.flint$setMaxDuration(before.flint$maxDuration());
			}
		}
	}

	/**
	 * Animations: while the body is snapped to a ladder, the head turn skips a tick (Not Enough Animations). 1.19.2's
	 * tickHeadTurn takes the walk's turn too and answers the body's running turn: skipped, that turn comes back unchanged.
	 */
	@Inject(method = "tickHeadTurn", at = @At("HEAD"), cancellable = true)
	private void flint$ladderBody(float yBodyRot, float turn, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Float> callback) {
		if ((Object) this instanceof com.flintclient.client.control.platform.Mc263Animations.Holder holder && holder.flint$animations().consumeBodyLock()) {
			callback.setReturnValue(turn);
		}
	}

	/**
	 * Animations: how far a player's head turns before the body follows. Minecraft 1.19.2 holds the head within 75° of the body
	 * in tickHeadTurn itself (1.20 asks getMaxHeadRotationRelativeToBody), so the module's angle replaces those two bounds.
	 */
	@ModifyConstant(method = "tickHeadTurn", constant = @Constant(floatValue = 75.0F))
	private float flint$headTurnRight(float bound) {
		Float max = flint$maxHeadRotation();
		return max == null ? bound : max;
	}

	@ModifyConstant(method = "tickHeadTurn", constant = @Constant(floatValue = -75.0F))
	private float flint$headTurnLeft(float bound) {
		Float max = flint$maxHeadRotation();
		return max == null ? bound : -max;
	}

	@Unique
	private @Nullable Float flint$maxHeadRotation() {
		return (Object) this instanceof Player player ? com.flintclient.client.control.platform.Mc263Animations.maxHeadRotation(player) : null;
	}

	/** Item Customizer's swing speed, Haste and Mining Fatigue: how long your own arm swing takes (1.19.2's getCurrentSwingDuration). */
	@com.llamalad7.mixinextras.injector.ModifyReturnValue(method = "getCurrentSwingDuration", at = @At("RETURN"))
	private int flint$swing(int ticks) {
		return com.flintclient.client.control.platform.Mc263ViewModel.swingTicks((LivingEntity) (Object) this, ticks);
	}

	/** Animations' Hide own potion particles: your own effect swirls aren't made while you're in first person. */
	@com.llamalad7.mixinextras.injector.v2.WrapWithCondition(method = "tickEffects", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
	private boolean flint$potionParticles(net.minecraft.world.level.Level level, net.minecraft.core.particles.ParticleOptions particle, double x,
		double y, double z, double dx, double dy, double dz) {
		return com.flintclient.client.control.platform.Mc263Tools.potionParticles((LivingEntity) (Object) this);
	}

	/** Jump Reset: the tick you jumped. */
	@Inject(method = "jumpFromGround", at = @At("HEAD"))
	private void flint$jumped(CallbackInfo callback) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (self.level.isClientSide) {
			com.flintclient.client.control.platform.Mc263HudExtras.jumped(self);
		}
	}

	/**
	 * The server's entity events: a hurt flash (yours, or of the entity you just attacked: your hit landing) and a death (3).
	 * Your own flash is your hurt animation for Hit Color and Damage Tint, without the direction 1.19.2 never sends.
	 */
	@Inject(method = "handleEntityEvent", at = @At("HEAD"))
	private void flint$event(byte id, CallbackInfo callback) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (!self.level.isClientSide) {
			return;
		}
		if (HitConfirm.hurtFlash(id)) {
			LocalPlayer player = Minecraft.getInstance().player;
			if (self == player) {
				Mc263Features.localHurt(player, Float.NaN);
			} else {
				DamageSource source = HitConfirm.landed(self, player);
				if (source != null) {
					Mc263Features.damaged(self, source);
				}
			}
			com.flintclient.client.control.platform.Mc263HudExtras.hurt(self);
		}
		Mc263Features.entityEvent(self, id);
	}
}
