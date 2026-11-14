package com.flintclient.legacy.mixin.cosmetics;

import com.flintclient.legacy.cosmetics.EmoteModel189;
import net.minecraft.client.render.model.entity.PlayerModel;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Emotes on 1.8.9: laid over the player model's pose once Minecraft has posed it (EmoteModel189). */
@Mixin(PlayerModel.class)
abstract class PlayerModelEmoteMixin {
	@Inject(method = "setupAnimation", at = @At("TAIL"))
	private void flint$emote(float walkProgress, float walkSpeed, float bob, float yaw, float pitch, float scale, Entity entity, CallbackInfo ci) {
		EmoteModel189.afterSetup((PlayerModel) (Object) this, entity, walkSpeed);
	}
}
