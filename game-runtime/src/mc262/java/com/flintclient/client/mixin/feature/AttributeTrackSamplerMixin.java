package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Features;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.ClientClockManager;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.ClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.timeline.AttributeTrackSampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Time Changer: the time of day the client's sky, light and fog are sampled at. Only samplers fed by the client's clock
 * and the overworld clock are touched, so the integrated server (and the world's real time) never sees it. Minecraft 26.2
 * reads the time straight from the clock manager.
 */
@Mixin(AttributeTrackSampler.class)
abstract class AttributeTrackSamplerMixin {
	@WrapOperation(method = "applyTimeBased", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/clock/ClockManager;getTotalTicks(Lnet/minecraft/core/Holder;)J"))
	private long flint$timeChanger(ClockManager manager, Holder<WorldClock> clock, Operation<Long> original) {
		long ticks = original.call(manager, clock);
		if (manager instanceof ClientClockManager && clock.is(WorldClocks.OVERWORLD)) {
			return Mc263Features.skyTicks(ticks);
		}
		return ticks;
	}
}
