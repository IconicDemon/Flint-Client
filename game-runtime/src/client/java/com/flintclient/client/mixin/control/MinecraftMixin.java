package com.flintclient.client.mixin.control;

import com.flintclient.client.control.ControlCenter;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets Flint's key bindings act where vanilla's do: Minecraft's tick calls {@code handleKeybinds} only while
 * no screen or overlay is open, so the Control Center key can't fire over another menu or while typing in
 * chat. The start of every tick, whatever is on screen, is where a held key is watched until it's let go
 * (see {@code ReleaseLatch}); it comes before {@code handleKeybinds} in the same tick. Both run once per
 * client tick (20 a second), so the callback objects they allocate don't matter.
 */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Inject(method = "tick()V", at = @At("HEAD"))
	private void flint$watchHeldKeys(CallbackInfo callback) {
		ControlCenter.onClientTick();
	}

	@Inject(method = "handleKeybinds", at = @At("HEAD"))
	private void flint$controlKeys(CallbackInfo callback) {
		ControlCenter.onKeybindTick();
	}
}
