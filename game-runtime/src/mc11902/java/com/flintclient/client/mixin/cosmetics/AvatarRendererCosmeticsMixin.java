package com.flintclient.client.mixin.cosmetics;

import com.flintclient.client.cosmetics.platform.CosmeticsLayer;
import com.flintclient.client.cosmetics.platform.Mc263Cosmetics;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The player renderer gets the 3D cosmetics' layer (after Minecraft's own, so wings sit over the cape and armour), and a
 * player what they wear as they start to be drawn ({@link Mc263Cosmetics#extract}: 1.19's render, before its setupAnim).
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

	@Inject(method = "render(Lnet/minecraft/client/player/AbstractClientPlayer;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At("HEAD"))
	private void flint$cosmetics(AbstractClientPlayer player, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light,
		CallbackInfo callback) {
		Mc263Cosmetics.extract(player, partialTick);
	}
}
