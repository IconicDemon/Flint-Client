package com.flintclient.client.mixin.cosmetics;

import com.flintclient.client.cosmetics.platform.EmoteKeys;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The "Emote wheel" key joins Minecraft's list before options.txt is read (as Flint's other keys: control/OptionsMixin). */
@Mixin(value = Options.class, priority = 1100)
abstract class OptionsEmoteKeyMixin {
	@Shadow
	@Final
	@Mutable
	public KeyMapping[] keyMappings;

	@Inject(method = "load()V", at = @At("HEAD"))
	private void flint$addEmoteKey(CallbackInfo callback) {
		try {
			keyMappings = EmoteKeys.addTo(keyMappings);
		} catch (RuntimeException | LinkageError e) {
			com.flintclient.client.FlintClient.LOGGER.warn("Flint: the emote wheel key couldn't be added", e);
		}
	}
}
