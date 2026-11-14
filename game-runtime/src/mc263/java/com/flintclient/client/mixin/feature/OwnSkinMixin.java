package com.flintclient.client.mixin.feature;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The skin just applied in Flint's wardrobe on the player's own entry at once ({@code compat/OwnSkin}). After Minecraft's skin
 * and before Flint's Store cape and Nick Hider (PlayerInfoMixin wraps the whole method, so they apply on top). 26.3 only.
 */
@Mixin(value = PlayerInfo.class, priority = 950)
abstract class OwnSkinMixin {
	@ModifyReturnValue(method = "getSkin", at = @At("RETURN"))
	private PlayerSkin flint$ownSkin(PlayerSkin skin) {
		try {
			return com.flintclient.client.compat.OwnSkin.apply((PlayerInfo) (Object) this, skin);
		} catch (RuntimeException | LinkageError e) {
			return skin;
		}
	}
}
