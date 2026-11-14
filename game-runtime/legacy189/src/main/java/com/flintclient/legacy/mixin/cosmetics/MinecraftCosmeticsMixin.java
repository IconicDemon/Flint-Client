package com.flintclient.legacy.mixin.cosmetics;

import com.flintclient.legacy.cosmetics.Cosmetics189;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** After each frame: the 3D cosmetics' per-frame budgets start again. */
@Mixin(Minecraft.class)
abstract class MinecraftCosmeticsMixin {
	@Inject(method = "runGame", at = @At("TAIL"))
	private void flint$frame(CallbackInfo ci) {
		Cosmetics189.frame();
		com.flintclient.legacy.ui.MenuBlur189.endFrame();
	}
}
