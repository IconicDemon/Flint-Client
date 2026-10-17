package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Visuals;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Overlay on the HUD: pumpkin blur, powder snow frost, portal swirl and nausea, each skippable. */
@Mixin(Hud.class)
abstract class HudOverlayMixin {
	@Inject(method = "extractTextureOverlay", at = @At("HEAD"), cancellable = true)
	private void flint$texture(GuiGraphicsExtractor graphics, Identifier texture, float alpha, CallbackInfo callback) {
		if (Mc263Visuals.hideTexture(texture)) {
			callback.cancel();
		}
	}

	@Inject(method = "extractPortalOverlay", at = @At("HEAD"), cancellable = true)
	private void flint$portal(GuiGraphicsExtractor graphics, float alpha, CallbackInfo callback) {
		if (Mc263Visuals.hidePortal()) {
			callback.cancel();
		}
	}

	@Inject(method = "extractConfusionOverlay", at = @At("HEAD"), cancellable = true)
	private void flint$nausea(GuiGraphicsExtractor graphics, float strength, CallbackInfo callback) {
		if (Mc263Visuals.hideNausea()) {
			callback.cancel();
		}
	}
}
