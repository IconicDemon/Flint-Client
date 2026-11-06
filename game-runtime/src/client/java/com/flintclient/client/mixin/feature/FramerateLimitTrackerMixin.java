package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263GameOptions;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** The game options' inactive and idle frame-rate limits, applied on top of Minecraft's own (never raising it). */
@Mixin(FramerateLimitTracker.class)
abstract class FramerateLimitTrackerMixin {
	@Shadow
	private long latestInputTime;

	@ModifyReturnValue(method = "getFramerateLimit", at = @At("RETURN"))
	private int flint$inactiveLimit(int limit) {
		return Mc263GameOptions.framerateLimit(limit, latestInputTime);
	}
}
