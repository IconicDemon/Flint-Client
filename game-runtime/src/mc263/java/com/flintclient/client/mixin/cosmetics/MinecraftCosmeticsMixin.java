package com.flintclient.client.mixin.cosmetics;

import com.flintclient.client.cosmetics.platform.Mc263Cosmetics;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The 3D cosmetics' tick, at the end of every client tick. */
@Mixin(Minecraft.class)
abstract class MinecraftCosmeticsMixin {
	@Inject(method = "tick()V", at = @At("TAIL"))
	private void flint$cosmeticsTick(CallbackInfo callback) {
		Mc263Cosmetics.tick();
	}
}
