package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Visuals;
import net.minecraft.client.gui.Gui;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Overlay on Minecraft 1.19.2's HUD: pumpkin blur, powder snow frost and the portal swirl, each skippable (1.19.2 draws them
 * straight onto the screen, without the GUI pose). Nausea is GameRenderer's, not the HUD's: GameRendererFeatureMixin.
 */
@Mixin(Gui.class)
abstract class HudOverlayMixin {
	@Inject(method = "renderTextureOverlay", at = @At("HEAD"), cancellable = true)
	private void flint$texture(ResourceLocation texture, float alpha, CallbackInfo callback) {
		if (Mc263Visuals.hideTexture(texture)) {
			callback.cancel();
		}
	}

	@Inject(method = "renderPortalOverlay", at = @At("HEAD"), cancellable = true)
	private void flint$portal(float alpha, CallbackInfo callback) {
		if (Mc263Visuals.hidePortal()) {
			callback.cancel();
		}
	}
}
