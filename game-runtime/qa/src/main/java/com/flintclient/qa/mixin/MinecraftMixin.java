package com.flintclient.qa.mixin;

import com.flintclient.qa.FlintQa;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** After each client tick, the tour's next step (client thread, 20 times a second). */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Inject(method = "tick()V", at = @At("TAIL"), require = 1)
	private void flintqa$tick(CallbackInfo ci) {
		if (FlintQa.ENABLED) {
			FlintQa.tick((Minecraft) (Object) this);
		}
	}
}
