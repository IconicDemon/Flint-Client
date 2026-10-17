package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Overlay;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Nick Hider on skins: a default skin, on your screen only, for you and/or others. */
@Mixin(PlayerInfo.class)
abstract class PlayerInfoMixin {
	@ModifyReturnValue(method = "getSkin", at = @At("RETURN"))
	private PlayerSkin flint$skin(PlayerSkin skin) {
		return Mc263Overlay.skin((PlayerInfo) (Object) this, skin);
	}
}
