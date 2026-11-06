package com.flintclient.legacy.mixin;

import com.flintclient.legacy.Flint;
import net.minecraft.client.options.GameOptions;
import net.minecraft.client.options.KeyBinding;
import org.apache.commons.lang3.ArrayUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flint's keys join Minecraft's own before the options are read, so they are saved in options.txt and listed in Controls under
 * "Flint Client". Other mods' keys are kept; running twice adds nothing.
 */
@Mixin(GameOptions.class)
public abstract class GameOptionsMixin {
	@Shadow
	public KeyBinding[] keyBindings;

	@Inject(method = "load", at = @At("HEAD"))
	private void flint$addKeys(CallbackInfo ci) {
		for (KeyBinding key : Flint.keys()) {
			if (!ArrayUtils.contains(keyBindings, key)) {
				keyBindings = ArrayUtils.add(keyBindings, key);
			}
		}
	}
}
