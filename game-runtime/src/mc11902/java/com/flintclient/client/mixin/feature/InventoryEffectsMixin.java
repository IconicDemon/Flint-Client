package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.GuiGraphics;
import com.flintclient.client.compat.gui.HudParts;
import com.flintclient.client.control.platform.Mc263EffectBars;
import com.flintclient.client.control.platform.Mc263HudExtras;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.EffectRenderingInventoryScreen;
import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Effects in the list beside the inventory on Minecraft 1.19.2 (EffectRenderingInventoryScreen, which draws all the
 * backgrounds, then all the icons, then the labels): Hide inventory list leaves it out (Flint's HUD list keeps showing
 * instead), and Status Effect Bars draws its bar over each effect's background, under its icon and text. 1.19.2 blits the
 * backgrounds from the inventory texture it binds once, so it is bound again after each bar.
 */
@Mixin(EffectRenderingInventoryScreen.class)
abstract class InventoryEffectsMixin {
	@ModifyReturnValue(method = "canSeeEffects", at = @At("RETURN"))
	private boolean flint$canSee(boolean can) {
		return can && !Mc263HudExtras.hideInventoryEffects();
	}

	@Inject(method = "renderEffects", at = @At("HEAD"), cancellable = true)
	private void flint$hide(PoseStack pose, int mouseX, int mouseY, CallbackInfo callback) {
		if (Mc263HudExtras.hideInventoryEffects()) {
			callback.cancel();
		}
	}

	/** Each effect's background (wide with its label, or the icon's square alone), then its bar. */
	@WrapOperation(method = "renderBackgrounds", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/screens/inventory/EffectRenderingInventoryScreen;blit(Lcom/mojang/blaze3d/vertex/PoseStack;IIIIII)V"))
	private void flint$bar(EffectRenderingInventoryScreen<?> screen, PoseStack pose, int x, int y, int u, int v, int width, int height,
		Operation<Void> original, @Local MobEffectInstance effect) {
		original.call(screen, pose, x, y, u, v, width, height);
		Mc263EffectBars.drawing(effect, false);
		Mc263EffectBars.render(GuiGraphics.of(pose), Mc263EffectBars.drawn(false), x, y, width, height, false);
		HudParts.restore(AbstractContainerScreen.INVENTORY_LOCATION);
	}
}
