package com.flintclient.client.thirdparty.mixin;

import com.flintclient.client.ui.screen.FlintTitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Walksy Lib, when installed: on Flint's main menu its toasts (drawn at the top right, below Minecraft's own) start below the
 * menu's top bar instead of over the account and the icons. Elsewhere they stay where Walksy Lib puts them. Walksy Lib 2.x
 * draws them from {@code ToastRenderer.render(WLGraphics2D, float topInset)}; if a later release changes that, this does
 * nothing.
 */
@Pseudo
@Mixin(targets = "main.walksy.lib.core.toast.ToastRenderer", remap = false)
abstract class WalksyToastPlacementMixin {
	@ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0, remap = false)
	private static float flint$belowFlintTopBar(float topInset) {
		try {
			return topInset + FlintTitleScreen.toastInset();
		} catch (RuntimeException | LinkageError e) {
			return topInset;
		}
	}
}
