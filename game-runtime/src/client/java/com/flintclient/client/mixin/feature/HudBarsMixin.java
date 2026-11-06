package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.McCompat;
import com.flintclient.client.control.platform.Mc263Bars;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Armor Bar and Hearts: Flint draws the armor row and the hearts in Minecraft's places when they're on; with Hearts the
 * health takes one row, so the armor row sits right above it.
 */
@Mixin(targets = McCompat.HUD_CLASS)
abstract class HudBarsMixin {
	@ModifyExpressionValue(method = "extractPlayerHealth", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;ceil(F)I", ordinal = 2))
	private int flint$rows(int rows) {
		return Mc263Bars.heartRows(rows);
	}

	/** Animations' Disable health flashing and Hurt Cam's Health bar blinking off: the hearts never flash. */
	@org.spongepowered.asm.mixin.injection.ModifyArg(method = "extractPlayerHealth", at = @At(value = "INVOKE",
		target = "L" + McCompat.HUD + ";extractHearts(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/entity/player/Player;IIIIFIIIZ)V"),
		index = 10)
	private boolean flint$blink(boolean blinking) {
		return com.flintclient.client.control.platform.Mc263HurtCam.heartBlink(com.flintclient.client.control.platform.Mc263Tools.healthBlink(blinking));
	}

	@Inject(method = "extractArmor", at = @At("HEAD"), cancellable = true)
	private static void flint$armor(GuiGraphicsExtractor graphics, Player player, int y, int rows, int rowHeight, int x, CallbackInfo callback) {
		if (Mc263Bars.drawArmor(graphics, player, y, rows, rowHeight, x)) {
			callback.cancel();
		}
	}

	@Inject(method = "extractHearts", at = @At("HEAD"), cancellable = true)
	private void flint$hearts(GuiGraphicsExtractor graphics, Player player, int x, int y, int rowHeight, int regen, float maxHealth, int health,
		int before, int absorption, boolean blinking, CallbackInfo callback) {
		if (Mc263Bars.drawHearts(graphics, player, x, y, rowHeight, regen, maxHealth, health, before, absorption, blinking)) {
			callback.cancel();
		}
	}
}
