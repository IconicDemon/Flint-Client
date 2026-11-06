package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Overlay;
import com.flintclient.client.control.platform.Mc263StoreCapes;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A player's skin on your screen: the cape they equipped in the Flint Store, if any (control/platform/Mc263StoreCapes), then
 * Nick Hider (a default skin, on your screen only, for you and/or others; a hidden skin shows no Store cape either).
 */
@Mixin(PlayerInfo.class)
abstract class PlayerInfoMixin {
	@ModifyReturnValue(method = "getSkin", at = @At("RETURN"))
	private PlayerSkin flint$skin(PlayerSkin skin) {
		PlayerInfo info = (PlayerInfo) (Object) this;
		return Mc263Overlay.skin(info, Mc263StoreCapes.skin(info, skin));
	}
}
