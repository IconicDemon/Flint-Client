package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263GameOptions;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The game options' inactive and idle frame-rate limits, applied on top of Minecraft's own (never raising it). Minecraft 1.21.1
 * has no FramerateLimitTracker: its limit comes from Minecraft.getFramerateLimit, and the last input is noted here
 * (InputTimeMixin) the way 1.21.2's tracker notes it.
 */
@Mixin(Minecraft.class)
abstract class FramerateLimitTrackerMixin {
	@ModifyReturnValue(method = "getFramerateLimit", at = @At("RETURN"))
	private int flint$inactiveLimit(int limit) {
		return Mc263GameOptions.framerateLimit(limit, com.flintclient.client.compat.InputTime.latest());
	}
}
