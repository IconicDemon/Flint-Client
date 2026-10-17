package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Skins;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 3D Skins: right before a player is drawn, its outer layers get that skin's voxels (or Minecraft's flat boxes back). */
@Mixin(PlayerModel.class)
abstract class PlayerModelMixin {
	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
	private void flint$voxels(AvatarRenderState state, CallbackInfo callback) {
		Mc263Skins.apply((PlayerModel) (Object) this, state);
	}
}
