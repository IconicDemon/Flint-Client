package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Features;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Combat feedback from what the server tells the client: damage events (a hit of yours landing names you as the cause;
 * a hit on you starts Jump Reset's count) and deaths (entity event 3); and your jumps, for Jump Reset. Client-side entities only; the integrated server's copies never reach these methods'
 * client paths because only {@code ClientPacketListener} calls them on the client.
 */
@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
	@org.spongepowered.asm.mixin.Shadow
	@org.spongepowered.asm.mixin.Final
	private java.util.Map<net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>, net.minecraft.world.effect.MobEffectInstance> activeEffects;
	@org.spongepowered.asm.mixin.Unique
	private net.minecraft.world.effect.@org.jspecify.annotations.Nullable MobEffectInstance flint$previousEffect;

	/** Status Effect Bars: an effect sent again at the same level keeps the longer full duration it started with. */
	@Inject(method = "forceAddEffect", at = @At("HEAD"))
	private void flint$effectBefore(net.minecraft.world.effect.MobEffectInstance effect, net.minecraft.world.entity.Entity source, CallbackInfo callback) {
		flint$previousEffect = activeEffects.get(effect.getEffect());
	}

	@Inject(method = "forceAddEffect", at = @At("TAIL"))
	private void flint$effectAfter(net.minecraft.world.effect.MobEffectInstance effect, net.minecraft.world.entity.Entity source, CallbackInfo callback) {
		net.minecraft.world.effect.MobEffectInstance previous = flint$previousEffect;
		flint$previousEffect = null;
		if (previous != null && previous != effect && previous.getAmplifier() == effect.getAmplifier()) {
			com.flintclient.client.control.platform.Mc263EffectBars.Timed now = (com.flintclient.client.control.platform.Mc263EffectBars.Timed) effect;
			com.flintclient.client.control.platform.Mc263EffectBars.Timed before = (com.flintclient.client.control.platform.Mc263EffectBars.Timed) previous;
			if (now.flint$maxDuration() < before.flint$maxDuration()) {
				now.flint$setMaxDuration(before.flint$maxDuration());
			}
		}
	}

	/** Animations: while the body is snapped to a ladder, the head turn skips a tick (Not Enough Animations). */
	@Inject(method = "tickHeadTurn", at = @At("HEAD"), cancellable = true)
	private void flint$ladderBody(float yBodyRot, CallbackInfo callback) {
		if ((Object) this instanceof com.flintclient.client.control.platform.Mc263Animations.Holder holder && holder.flint$animations().consumeBodyLock()) {
			callback.cancel();
		}
	}

	@Inject(method = "handleDamageEvent", at = @At("HEAD"))
	private void flint$damaged(DamageSource source, CallbackInfo callback) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (self.level().isClientSide()) {
			Mc263Features.damaged(self, source);
			com.flintclient.client.control.platform.Mc263HudExtras.hurt(self);
		}
	}

	/** Animations' swing duration: how long your own arm swing takes (the method's name depends on the Minecraft version). */
	@com.llamalad7.mixinextras.injector.ModifyReturnValue(method = com.flintclient.client.compat.McCompat.SWING_DURATION, at = @At("RETURN"))
	private int flint$swing(int ticks) {
		return com.flintclient.client.control.platform.Mc263Tools.swing((LivingEntity) (Object) this, ticks);
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
		if (self.level().isClientSide()) {
			com.flintclient.client.control.platform.Mc263HudExtras.jumped(self);
		}
	}

	@Inject(method = "handleEntityEvent", at = @At("HEAD"))
	private void flint$event(byte id, CallbackInfo callback) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (self.level().isClientSide()) {
			Mc263Features.entityEvent(self, id);
		}
	}
}
