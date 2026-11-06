package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Overlay;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Boss Bar: the name's shadow and colour as the module sets them (the server's name itself is kept). Custom F3 (BetterF3) can
 * hide the bars while the debug screen is open.
 */
@Mixin(BossHealthOverlay.class)
abstract class BossHealthOverlayMixin {
	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void flint$hideForF3(GuiGraphicsExtractor graphics, CallbackInfo callback) {
		if (com.flintclient.client.control.platform.Mc263BetterF3.hideBossbar()) {
			callback.cancel();
		}
	}

	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"))
	private void flint$name(GuiGraphicsExtractor graphics, Font font, Component name, int x, int y, int color, Operation<Void> original) {
		if (!Mc263Overlay.bossTextChanged()) {
			original.call(graphics, font, name, x, y, color);
			return;
		}
		graphics.text(font, name, x, y, Mc263Overlay.bossTextColor(color), Mc263Overlay.bossTextShadow());
	}
}
