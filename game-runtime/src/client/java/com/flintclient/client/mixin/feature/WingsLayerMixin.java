package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263StoreCapes;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.entity.layers.WingsLayer;
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.player.PlayerSkin;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * An elytra's texture by Minecraft's own rule (the skin's elytra texture, else its cape while shown, else the elytra's), with
 * one exception: a Flint Store cape without elytra art isn't put on the wings (they would be empty); Minecraft decides with the
 * cape it gave the wearer instead ({@link Mc263StoreCapes#wingsCape}).
 */
@Mixin(WingsLayer.class)
abstract class WingsLayerMixin {
	@WrapOperation(method = "getPlayerElytraTexture", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/entity/player/PlayerSkin;cape()Lnet/minecraft/core/ClientAsset$Texture;"))
	private static ClientAsset.@Nullable Texture flint$wingsCape(PlayerSkin skin, Operation<ClientAsset.Texture> original) {
		return Mc263StoreCapes.wingsCape(skin, original.call(skin));
	}
}
