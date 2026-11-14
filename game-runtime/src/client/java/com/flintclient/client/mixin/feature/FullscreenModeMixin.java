package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.FullscreenSync;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 26.x: Flint's Borderless fullscreen sets Minecraft's Exclusive Fullscreen as the options load (FullscreenSync). */
@Mixin(value = Options.class, priority = 1500)
abstract class FullscreenModeMixin {
	@Inject(method = "load()V", at = @At("TAIL"))
	private void flint$borderless(CallbackInfo ci) {
		FullscreenSync.optionsLoaded((Options) (Object) this);
	}
}
