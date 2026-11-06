package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Hitbox;
import net.minecraft.client.renderer.debug.EntityHitboxDebugRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hitbox (combat-hitbox's look): each hitbox F3+B shows is drawn by Flint instead of Minecraft, for the entities Minecraft
 * picked (visible, on screen, not you in first person). The local server's own boxes (a debug-only switch) stay Minecraft's.
 */
@Mixin(EntityHitboxDebugRenderer.class)
abstract class HitboxRenderMixin {
	@Inject(method = "showHitboxes", at = @At("HEAD"), cancellable = true)
	private void flint$hitbox(Entity entity, float partialTicks, boolean serverEntity, CallbackInfo callback) {
		if (!serverEntity && Mc263Hitbox.draw(entity, partialTicks)) {
			callback.cancel();
		}
	}
}
