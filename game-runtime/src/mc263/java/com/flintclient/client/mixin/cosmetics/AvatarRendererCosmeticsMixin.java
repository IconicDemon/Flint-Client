package com.flintclient.client.mixin.cosmetics;

import com.flintclient.client.cosmetics.platform.CosmeticsLayer;
import com.flintclient.client.cosmetics.platform.Mc263Cosmetics;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The player renderer gets the 3D cosmetics' layer (after Minecraft's own, so wings sit over the cape and armour), and a
 * player's render state what they wear ({@link Mc263Cosmetics#extract}).
 */
@Mixin(AvatarRenderer.class)
abstract class AvatarRendererCosmeticsMixin extends LivingEntityRenderer<Avatar, AvatarRenderState, PlayerModel> {
	private AvatarRendererCosmeticsMixin(EntityRendererProvider.Context context, PlayerModel model, float shadow) {
		super(context, model, shadow);
	}

	@Inject(method = "<init>", at = @At("TAIL"))
	@SuppressWarnings("unchecked")
	private void flint$cosmeticsLayer(EntityRendererProvider.Context context, boolean slim, CallbackInfo callback) {
		addLayer(new CosmeticsLayer((AvatarRenderer<?>) (Object) this));
	}

	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
		at = @At("TAIL"))
	private void flint$cosmetics(Avatar entity, AvatarRenderState state, float partialTicks, CallbackInfo callback) {
		Mc263Cosmetics.extract(entity, state);
	}
}
