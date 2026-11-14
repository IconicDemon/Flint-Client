package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.GuiGraphics;
import com.flintclient.client.compat.gui.HudParts;
import com.flintclient.client.control.platform.Mc263Bars;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Gui;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Armor Bar and Hearts: Flint draws the armor row and the hearts in Minecraft's places when they're on; with Hearts the
 * health takes one row, so the armor row sits right above it. Minecraft 1.19.2 draws the armor row inline in
 * renderPlayerHealth (its own profiler section), so Flint's is drawn as that section starts and Minecraft's icons in it are
 * left out; the heart rows laid out there are kept for the other HUD mixins (compat.gui.HudParts).
 */
@Mixin(Gui.class)
abstract class HudBarsMixin {
	@Shadow
	private int screenWidth;
	@Shadow
	private int screenHeight;

	@Shadow
	private Player getCameraPlayer() {
		throw new AssertionError();
	}

	@ModifyExpressionValue(method = "renderPlayerHealth", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;ceil(F)I", ordinal = 2))
	private int flint$rows(int rows) {
		int shown = Mc263Bars.heartRows(rows);
		HudParts.heartRows = shown;
		return shown;
	}

	/** The rows' spacing Minecraft works out from them (kept, unchanged, for the armor row's place). */
	@ModifyExpressionValue(method = "renderPlayerHealth", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(II)I", ordinal = 1))
	private int flint$rowHeight(int rowHeight) {
		HudParts.rowHeight = rowHeight;
		return rowHeight;
	}

	/** Animations' Disable health flashing and Hurt Cam's Health bar blinking off: the hearts never flash. */
	@ModifyArg(method = "renderPlayerHealth", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/Gui;renderHearts(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/entity/player/Player;IIIIFIIIZ)V"),
		index = 10)
	private boolean flint$blink(boolean blinking) {
		return com.flintclient.client.control.platform.Mc263HurtCam.heartBlink(com.flintclient.client.control.platform.Mc263Tools.healthBlink(blinking));
	}

	/** The armor row, as its section starts (after Autohide HUD placed it); Minecraft's icons.png is bound again after. */
	@Inject(method = "renderPlayerHealth", at = @At(value = "INVOKE_STRING", shift = At.Shift.AFTER,
		target = "Lnet/minecraft/util/profiling/ProfilerFiller;push(Ljava/lang/String;)V", args = "ldc=armor"))
	private void flint$armor(PoseStack pose, CallbackInfo callback) {
		HudParts.flintArmor = false;
		if (HudParts.armor == HudParts.SKIP) {
			return;
		}
		Player player = getCameraPlayer();
		if (player != null && Mc263Bars.drawArmor(GuiGraphics.of(pose), player, screenHeight - 39, HudParts.heartRows, HudParts.rowHeight,
			screenWidth / 2 - 91)) {
			HudParts.flintArmor = true;
		}
		HudParts.icons();
	}

	@WrapWithCondition(method = "renderPlayerHealth", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/Gui;blit(Lcom/mojang/blaze3d/vertex/PoseStack;IIIIII)V"),
		slice = @Slice(from = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;push(Ljava/lang/String;)V",
			args = "ldc=armor"), to = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
			args = "ldc=health")))
	private boolean flint$vanillaArmor(Gui hud, PoseStack pose, int x, int y, int u, int v, int w, int h) {
		return !HudParts.flintArmor;
	}

	@Inject(method = "renderHearts", at = @At("HEAD"), cancellable = true)
	private void flint$hearts(PoseStack pose, Player player, int x, int y, int rowHeight, int regen, float maxHealth, int health,
		int before, int absorption, boolean blinking, CallbackInfo callback) {
		if (Mc263Bars.drawHearts(GuiGraphics.of(pose), player, x, y, rowHeight, regen, maxHealth, health, before, absorption, blinking)) {
			HudParts.icons();
			callback.cancel();
		}
	}
}
