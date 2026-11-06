package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Overlay;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Tab Editor's hidden heads on Minecraft 1.21.11 (as 26.1), which shows heads when the world is local or the connection is encrypted:
 * PlayerTabOverlayMixin answers the connection half (McCompat.TAB_HEADS), this the local half.
 */
@Mixin(PlayerTabOverlay.class)
abstract class PlayerTabOverlayHeadsMixin {
	@ModifyExpressionValue(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;isLocalServer()Z"))
	private boolean flint$localHeads(boolean local) {
		return local && !Mc263Overlay.tabHideHeads();
	}
}
