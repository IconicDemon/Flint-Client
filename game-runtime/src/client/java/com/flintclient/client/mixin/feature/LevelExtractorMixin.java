package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.McCompat;
import com.flintclient.client.control.platform.Mc263World;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Flint's per-frame world gizmos (Hitbox), right after Minecraft's own debug views emit theirs (26.1 does it in LevelRenderer). */
@Mixin(targets = McCompat.LEVEL_EXTRACTOR_CLASS)
abstract class LevelExtractorMixin {
	@Inject(method = McCompat.EXTRACT_LEVEL, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/debug/DebugRenderer;emitGizmos(Lnet/minecraft/client/renderer/culling/Frustum;DDDF)V", shift = At.Shift.AFTER))
	private void flint$gizmos(DeltaTracker delta, Camera camera, float partialTicks, CallbackInfo callback) {
		Mc263World.frame(camera, delta.getGameTimeDeltaPartialTick(false));
		com.flintclient.client.control.platform.Mc263Team.frame();
	}
}
