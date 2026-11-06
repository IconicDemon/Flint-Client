package com.flintclient.qa.legacy.mixin;

import com.flintclient.qa.legacy.FlintQa;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** One QA step per client tick, before Minecraft handles input. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void flintqa$tick(CallbackInfo ci) {
		if (FlintQa.ENABLED) {
			FlintQa.tick((Minecraft) (Object) this);
		}
	}
}
