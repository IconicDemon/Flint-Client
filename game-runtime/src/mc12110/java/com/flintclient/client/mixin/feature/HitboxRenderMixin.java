package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Hitbox;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hitbox (combat-hitbox's look) on Minecraft 1.21.10, which has no hitbox gizmos: each entity F3+B outlines (Minecraft
 * already checked it's on, visible and on screen) is handed to Flint, which draws it with the frame's gizmos; Minecraft's
 * own box is left out.
 */
@Mixin(EntityRenderer.class)
abstract class HitboxRenderMixin {
	@Inject(method = "extractHitboxes(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V",
		at = @At("HEAD"), cancellable = true)
	private void flint$hitbox(Entity entity, EntityRenderState state, float partialTicks, CallbackInfo callback) {
		if (Mc263Hitbox.queue(entity, partialTicks)) {
			state.hitboxesRenderState = null;
			callback.cancel();
		}
	}
}
