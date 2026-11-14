package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.GuiGraphics;
import com.flintclient.client.control.platform.Mc263Overlay;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Boss Bar on Minecraft 1.19.2: hidden, placed and scaled as the module sets it (the hooks are on the overlay itself), the
 * name's shadow and colour as the module sets them, and hidden while Custom F3 (BetterF3) asks. 1.19.2 draws each name with
 * the font's shadowed draw.
 */
@Mixin(BossHealthOverlay.class)
abstract class BossHealthOverlayMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void flint$begin(PoseStack pose, CallbackInfo callback) {
		if (com.flintclient.client.control.platform.Mc263BetterF3.hideBossbar() || Mc263Overlay.hideBossBars()) {
			callback.cancel();
			return;
		}
		Mc263Overlay.bossBegin(GuiGraphics.of(pose));
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void flint$end(PoseStack pose, CallbackInfo callback) {
		Mc263Overlay.bossEnd(GuiGraphics.of(pose));
	}

	@WrapOperation(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/Font;drawShadow(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;FFI)I"))
	private int flint$name(Font font, PoseStack pose, Component name, float x, float y, int color, Operation<Integer> original) {
		if (!Mc263Overlay.bossTextChanged()) {
			return original.call(font, pose, name, x, y, color);
		}
		GuiGraphics.of(pose).text(font, name, (int) x, (int) y, Mc263Overlay.bossTextColor(color), Mc263Overlay.bossTextShadow());
		return (int) x + font.width(name);
	}
}
