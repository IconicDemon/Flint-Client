package com.flintclient.bench.mixin;

import com.flintclient.bench.FlintBench;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Times each client tick and, after it, advances the benchmark scenario (client thread, 20 times a second). */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Inject(method = "tick()V", at = @At("HEAD"), require = 1)
	private void flintbench$tickStart(CallbackInfo ci) {
		if (FlintBench.ENABLED) {
			FlintBench.onTickStart();
		}
	}

	@Inject(method = "tick()V", at = @At("TAIL"), require = 1)
	private void flintbench$tickEnd(CallbackInfo ci) {
		if (FlintBench.ENABLED) {
			FlintBench.onTickEnd((Minecraft) (Object) this);
		}
	}
}
