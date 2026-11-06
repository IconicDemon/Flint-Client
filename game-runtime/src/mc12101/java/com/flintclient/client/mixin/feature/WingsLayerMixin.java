package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263StoreCapes;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.entity.layers.ElytraLayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Minecraft 1.21.1 (its elytra layer picks the texture as it draws): an elytra's texture by Minecraft's own rule (the skin's
 * elytra texture, else its cape while shown, else the elytra's), except that a Flint Store cape without elytra art isn't put
 * on the wings (they would be empty); Minecraft decides with the cape it gave the wearer instead
 * ({@link Mc263StoreCapes#wingsCape}).
 */
@Mixin(ElytraLayer.class)
abstract class WingsLayerMixin {
	@WrapOperation(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/PlayerSkin;capeTexture()Lnet/minecraft/resources/ResourceLocation;"))
	private @Nullable ResourceLocation flint$wingsCape(PlayerSkin skin, Operation<ResourceLocation> original) {
		return Mc263StoreCapes.wingsCape(skin, original.call(skin));
	}
}
