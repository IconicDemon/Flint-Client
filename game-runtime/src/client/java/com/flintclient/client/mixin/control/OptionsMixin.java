package com.flintclient.client.mixin.control;

import com.flintclient.client.control.ControlCenter;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds Flint's key bindings to Minecraft's list before options.txt is read, so they show in Controls and a
 * player's saved keys apply to them. The list is a final array built by Options' field initialisers;
 * {@code load} runs at the end of the constructor, after them. Fabric API's key binding module replaces the
 * same array at the same point; each side keeps the other's entries (see {@code MappingList}), so the two
 * coexist in either order. Runs once per options load, never while playing.
 */
@Mixin(Options.class)
abstract class OptionsMixin {
	@Shadow
	@Final
	@Mutable
	public KeyMapping[] keyMappings;

	@Inject(method = "load()V", at = @At("HEAD"))
	private void flint$addControlKeys(CallbackInfo callback) {
		keyMappings = ControlCenter.withControlKeys(keyMappings);
	}
}
