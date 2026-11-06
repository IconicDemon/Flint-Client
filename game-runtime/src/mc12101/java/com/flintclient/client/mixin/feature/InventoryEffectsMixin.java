package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263EffectBars;
import com.flintclient.client.control.platform.Mc263HudExtras;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.EffectRenderingInventoryScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Effects in the list beside the inventory on Minecraft 1.21.1 (EffectRenderingInventoryScreen, which draws all the
 * backgrounds, then all the icons, then the labels): Hide inventory list leaves it out (Flint's HUD list keeps showing
 * instead), and Status Effect Bars draws its bar over each effect's background, under its icon and text.
 */
@Mixin(EffectRenderingInventoryScreen.class)
abstract class InventoryEffectsMixin {
	@ModifyReturnValue(method = "canSeeEffects", at = @At("RETURN"))
	private boolean flint$canSee(boolean can) {
		return can && !Mc263HudExtras.hideInventoryEffects();
	}

	@Inject(method = "renderEffects", at = @At("HEAD"), cancellable = true)
	private void flint$hide(GuiGraphics graphics, int mouseX, int mouseY, CallbackInfo callback) {
		if (Mc263HudExtras.hideInventoryEffects()) {
			callback.cancel();
		}
	}

	@WrapOperation(method = "renderBackgrounds", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lnet/minecraft/resources/ResourceLocation;IIII)V"))
	private void flint$bar(GuiGraphics graphics, ResourceLocation sprite, int x, int y, int width, int height, Operation<Void> original,
		@Local MobEffectInstance effect) {
		original.call(graphics, sprite, x, y, width, height);
		Mc263EffectBars.drawing(effect, false);
		Mc263EffectBars.render(graphics, Mc263EffectBars.drawn(false), x, y, width, height, false);
	}
}
