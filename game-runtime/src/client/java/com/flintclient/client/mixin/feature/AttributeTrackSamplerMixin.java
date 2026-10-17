package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Features;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.ClientClockManager;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.ClockInstance;
import net.minecraft.world.clock.ClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.timeline.AttributeTrackSampler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Time Changer: the time of day the client's sky, light and fog are sampled at. Only samplers fed by the client's clock
 * and the overworld clock are touched, so the integrated server (and the world's real time) never sees it.
 */
@Mixin(AttributeTrackSampler.class)
abstract class AttributeTrackSamplerMixin {
	@Shadow
	@Final
	private Holder<WorldClock> clock;

	@Shadow
	@Final
	private ClockManager clockManager;

	@WrapOperation(method = "applyTimeBased", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/clock/ClockInstance;totalTicks()J"))
	private long flint$timeChanger(ClockInstance instance, Operation<Long> original) {
		long ticks = original.call(instance);
		if (clockManager instanceof ClientClockManager && clock.is(WorldClocks.OVERWORLD)) {
			return Mc263Features.skyTicks(ticks);
		}
		return ticks;
	}
}
