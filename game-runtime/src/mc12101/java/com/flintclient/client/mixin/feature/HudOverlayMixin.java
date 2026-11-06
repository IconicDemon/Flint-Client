package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Visuals;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Overlay on Minecraft 1.21.1's HUD: pumpkin blur, powder snow frost and the portal swirl, each skippable. (1.21.1's nausea
 * is a warp of the camera, not a HUD overlay: GameRendererFeatureMixin.)
 */
@Mixin(Gui.class)
abstract class HudOverlayMixin {
	@Inject(method = "renderTextureOverlay", at = @At("HEAD"), cancellable = true)
	private void flint$texture(GuiGraphics graphics, ResourceLocation texture, float alpha, CallbackInfo callback) {
		if (Mc263Visuals.hideTexture(texture)) {
			callback.cancel();
		}
	}

	@Inject(method = "renderPortalOverlay", at = @At("HEAD"), cancellable = true)
	private void flint$portal(GuiGraphics graphics, float alpha, CallbackInfo callback) {
		if (Mc263Visuals.hidePortal()) {
			callback.cancel();
		}
	}
}
