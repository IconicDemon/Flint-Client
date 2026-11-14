package com.flintclient.qa.legacy.mixin;

import com.flintclient.qa.legacy.LegacyStamp;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** LegacyStamp: the frame number painted just before Display.update shows the frame, and the call timed. */
@Mixin(Minecraft.class)
public abstract class UpdateDisplayMixin {
	@Inject(method = "updateDisplay", at = @At("HEAD"))
	private void flintqa$beforePresent(CallbackInfo ci) {
		LegacyStamp.beforePresent();
	}

	@Inject(method = "updateDisplay", at = @At("TAIL"))
	private void flintqa$afterPresent(CallbackInfo ci) {
		LegacyStamp.afterPresent();
	}
}
