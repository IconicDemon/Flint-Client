package com.flintclient.legacy.mixin;

import com.flintclient.legacy.Flint;
import net.minecraft.client.render.world.WorldRenderer;
import net.minecraft.entity.living.player.PlayerEntity;
import net.minecraft.world.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Block Overlay draws the targeted block's outline in place of Minecraft's. */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
	@Inject(method = "renderBlockOutline", at = @At("HEAD"), cancellable = true)
	private void flint$blockOverlay(PlayerEntity camera, HitResult hit, int mode, float tickDelta, CallbackInfo ci) {
		if (mode == 0 && Flint.ready() && Flint.modules().blockOverlay.enabled()) {
			try {
				if (Flint.modules().blockOverlay.render(camera, hit, tickDelta)) {
					ci.cancel();
				}
			} catch (RuntimeException e) {
				Flint.fault(Flint.modules().blockOverlay, e);
			}
		}
	}
}
