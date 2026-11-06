package com.flintclient.qa.mixin;

import com.flintclient.qa.QaSkin;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The QA player's Minecraft cape stand-in ({@link QaSkin#minecraftCape}), as if Mojang had given it: applied first (priority
 * 500), so Flint's own hook on the same method (Store capes, priority 1000) sees it as Minecraft's cape and decides over it.
 */
@Mixin(value = PlayerInfo.class, priority = 500)
abstract class PlayerInfoSkinMixin {
	@Unique
	private static boolean flintqa$broken;

	@ModifyReturnValue(method = "getSkin", at = @At("RETURN"))
	private PlayerSkin flintqa$minecraftCape(PlayerSkin skin) {
		if (flintqa$broken) {
			return skin;
		}
		try {
			return QaSkin.minecraft((PlayerInfo) (Object) this, skin);
		} catch (LinkageError e) {
			// A newer Minecraft than this QA build (a forward-compatibility run): as Minecraft gave it.
			flintqa$broken = true;
			return skin;
		}
	}
}
