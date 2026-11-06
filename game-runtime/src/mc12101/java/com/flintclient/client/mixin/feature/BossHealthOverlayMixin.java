package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Overlay;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Boss Bar on Minecraft 1.21.1: hidden, placed and scaled as the module sets it (1.21.1's HUD draws the bars from a lambda,
 * so the hooks are on the overlay itself), the name's shadow and colour as the module sets them, and hidden while Custom F3
 * (BetterF3) asks.
 */
@Mixin(BossHealthOverlay.class)
abstract class BossHealthOverlayMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void flint$begin(GuiGraphics graphics, CallbackInfo callback) {
		if (com.flintclient.client.control.platform.Mc263BetterF3.hideBossbar() || Mc263Overlay.hideBossBars()) {
			callback.cancel();
			return;
		}
		Mc263Overlay.bossBegin(graphics);
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void flint$end(GuiGraphics graphics, CallbackInfo callback) {
		Mc263Overlay.bossEnd(graphics);
	}

	@WrapOperation(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)I"))
	private int flint$name(GuiGraphics graphics, Font font, Component name, int x, int y, int color, Operation<Integer> original) {
		if (!Mc263Overlay.bossTextChanged()) {
			return original.call(graphics, font, name, x, y, color);
		}
		graphics.text(font, name, x, y, Mc263Overlay.bossTextColor(color), Mc263Overlay.bossTextShadow());
		return x + font.width(name);
	}
}
