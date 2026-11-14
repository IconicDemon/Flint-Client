package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263EffectBars;
import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Effects' Status Effect Bars: each effect remembers the duration it started with (copies and upgrades carry it over), so its
 * bar shows the share of its time left (after Status Effect Bars, LGPL-3.0-or-later). Minecraft 1.19.2 makes an effect from
 * the effect itself (1.20.5 from its registry holder): every constructor but the copy's ends in the one that also takes the
 * hidden effect and the factor data, and the copy's takes its numbers through setDetailsFrom.
 */
@Mixin(MobEffectInstance.class)
abstract class MobEffectInstanceMixin implements Mc263EffectBars.Timed {
	@Shadow
	private int duration;
	@Unique
	private int flint$maxDuration;

	@Inject(method = "<init>(Lnet/minecraft/world/effect/MobEffect;IIZZZLnet/minecraft/world/effect/MobEffectInstance;Ljava/util/Optional;)V",
		at = @At("RETURN"))
	private void flint$started(CallbackInfo callback) {
		flint$maxDuration = duration;
	}

	@Inject(method = "setDetailsFrom", at = @At("RETURN"))
	private void flint$copied(MobEffectInstance copy, CallbackInfo callback) {
		flint$maxDuration = ((Mc263EffectBars.Timed) copy).flint$maxDuration();
	}

	@Inject(method = "update", at = @At(value = "FIELD", target = "Lnet/minecraft/world/effect/MobEffectInstance;duration:I", opcode = 181))
	private void flint$upgraded(MobEffectInstance takeOver, CallbackInfoReturnable<Boolean> callback) {
		flint$maxDuration = ((Mc263EffectBars.Timed) takeOver).flint$maxDuration();
	}

	@Override
	public int flint$maxDuration() {
		return flint$maxDuration;
	}

	@Override
	public void flint$setMaxDuration(int ticks) {
		flint$maxDuration = ticks;
	}
}
