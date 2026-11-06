package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Features;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import net.minecraft.world.timeline.AttributeTrackSampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Time Changer: the time of day the client's sky, light and fog are sampled at. Minecraft 1.21.11 samples the day time
 * through a supplier (the level's day time), so only samples taken on the client's own thread are moved: the integrated
 * server (and the world's real time) never sees it.
 */
@Mixin(AttributeTrackSampler.class)
abstract class AttributeTrackSamplerMixin {
	@ModifyExpressionValue(method = "applyTimeBased", at = @At(value = "INVOKE", target = "Ljava/util/function/LongSupplier;getAsLong()J"))
	private long flint$timeChanger(long ticks) {
		return Minecraft.getInstance().isSameThread() ? Mc263Features.skyTicks(ticks) : ticks;
	}
}
