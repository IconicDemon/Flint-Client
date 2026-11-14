package com.flintclient.client.mixin;

import com.flintclient.client.perf.PerformanceCore;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Feeds Flint's frame statistics from the sample Minecraft takes once per rendered frame for its own frame-time chart: the
 * full interval since the previous frame, in nanoseconds; and, at that point between two frames, advances animated Store
 * capes (control/platform/Mc263StoreCapes). Minecraft 1.19.2 keeps that sample in a FrameTimer that runTick feeds (the
 * integrated server has a FrameTimer of its own, for its ticks), so the hook is on runTick's call. Modifying the argument
 * (and returning it unchanged) rather than injecting a callback avoids allocating callback info every frame.
 */
@Mixin(Minecraft.class)
abstract class DebugScreenOverlayMixin {
	@ModifyArg(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/FrameTimer;logFrameDuration(J)V"))
	private long flint$recordFrame(long frameDuration) {
		PerformanceCore.onFrame(frameDuration);
		// Between frames (the last one presented, no render pass open): animated Store capes write their next frame here.
		com.flintclient.client.control.platform.Mc263StoreCapes.frame();
		return frameDuration;
	}
}
