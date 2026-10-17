package com.flintclient.bench.mixin;

import com.flintclient.bench.FlintBench;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Records every rendered frame. Minecraft.renderFrame calls logFrameDuration once per frame with the
 * full interval since the previous one (ticks, render, present and the frame limiter). A ModifyVariable
 * that returns the argument unchanged is used instead of an Inject because it needs no CallbackInfo, so
 * the hook allocates nothing per frame even before the JIT optimises it.
 */
@Mixin(DebugScreenOverlay.class)
abstract class DebugScreenOverlayMixin {
	@ModifyVariable(method = "logFrameDuration(J)V", at = @At("HEAD"), argsOnly = true, require = 1)
	private long flintbench$onFrame(long frameDuration) {
		if (FlintBench.ENABLED) {
			FlintBench.onFrame(frameDuration);
		}
		return frameDuration;
	}
}
