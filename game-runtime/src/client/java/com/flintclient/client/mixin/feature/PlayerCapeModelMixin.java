package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Capes;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerCapeModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Wavy Capes: right before a cape is drawn (Minecraft sets its model up just before), the cloth takes its box's place. */
@Mixin(PlayerCapeModel.class)
abstract class PlayerCapeModelMixin {
	@Shadow
	@Final
	private ModelPart cape;

	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
	private void flint$wavyCape(AvatarRenderState state, CallbackInfo callback) {
		Mc263Capes.setup(cape, state);
	}
}
