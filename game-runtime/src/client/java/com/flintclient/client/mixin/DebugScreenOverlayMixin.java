package com.flintclient.client.mixin;

import com.flintclient.client.perf.PerformanceCore;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Feeds Flint's frame statistics from the sample Minecraft takes once per rendered frame for its own
 * frame-time chart: the full interval since the previous frame, in nanoseconds. Modifying the argument
 * (and returning it unchanged) rather than injecting a callback avoids allocating callback info every
 * frame.
 */
@Mixin(DebugScreenOverlay.class)
abstract class DebugScreenOverlayMixin {
	@ModifyVariable(method = "logFrameDuration", at = @At("HEAD"), argsOnly = true)
	private long flint$recordFrame(long frameDuration) {
		PerformanceCore.onFrame(frameDuration);
		return frameDuration;
	}
}
