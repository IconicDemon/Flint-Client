package com.flintclient.client.mixin.cosmetics;

import com.flintclient.client.cosmetics.platform.CosmeticsLayer;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.21.1's player renderer draws the 3D cosmetics layer after Minecraft's own layers ({@link CosmeticsLayer}); what the player
 * wears is read when their model is posed ({@code PlayerModelEmoteMixin}).
 */
@Mixin(PlayerRenderer.class)
abstract class AvatarRendererCosmeticsMixin extends LivingEntityRenderer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
	private AvatarRendererCosmeticsMixin(EntityRendererProvider.Context context, PlayerModel<AbstractClientPlayer> model, float shadow) {
		super(context, model, shadow);
	}

	@Inject(method = "<init>", at = @At("TAIL"))
	private void flint$cosmeticsLayer(EntityRendererProvider.Context context, boolean slim, CallbackInfo callback) {
		addLayer(new CosmeticsLayer((PlayerRenderer) (Object) this));
	}
}
