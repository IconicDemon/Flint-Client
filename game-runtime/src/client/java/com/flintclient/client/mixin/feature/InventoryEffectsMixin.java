package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263EffectBars;
import com.flintclient.client.control.platform.Mc263HudExtras;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.EffectsInInventory;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Effects in the list beside the inventory: Hide inventory list leaves it out (Flint's HUD list keeps showing instead), and
 * Status Effect Bars draws its bar over each effect's background, under its text and icon.
 */
@Mixin(EffectsInInventory.class)
abstract class InventoryEffectsMixin {
	@ModifyReturnValue(method = "canSeeEffects", at = @At("RETURN"))
	private boolean flint$canSee(boolean can) {
		return can && !Mc263HudExtras.hideInventoryEffects();
	}

	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void flint$hide(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo callback) {
		if (Mc263HudExtras.hideInventoryEffects()) {
			callback.cancel();
		}
	}

	/** The effect whose entry comes next (its first question is whether it is a beacon's). */
	@WrapOperation(method = "extractEffects", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/effect/MobEffectInstance;isAmbient()Z"))
	private boolean flint$barEffect(MobEffectInstance effect, Operation<Boolean> original) {
		Mc263EffectBars.drawing(effect, false);
		return original.call(effect);
	}

	@WrapOperation(method = "extractEffects", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/inventory/EffectsInInventory;"
		+ "extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;"
		+ "Lnet/minecraft/network/chat/Component;IIZI)I"))
	private int flint$bar(EffectsInInventory self, GuiGraphicsExtractor graphics, Font font, Component name, Component duration, int x0, int y0,
		boolean ambient, int maxWidth, Operation<Integer> original) {
		int width = original.call(self, graphics, font, name, duration, x0, y0, ambient, maxWidth);
		Mc263EffectBars.render(graphics, Mc263EffectBars.drawn(false), x0, y0, width, 32, false);
		return width;
	}
}
