package com.flintclient.client.mixin.cosmetics;

import com.flintclient.client.cosmetics.platform.EmoteModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** An emote playing on you takes over the parts it animates, after Minecraft (and Flint's animations) posed the model. */
@Mixin(value = PlayerModel.class, priority = 1100)
abstract class PlayerModelEmoteMixin {
	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
	private void flint$emote(AvatarRenderState state, CallbackInfo callback) {
		EmoteModel.apply((PlayerModel) (Object) this, state);
	}
}
