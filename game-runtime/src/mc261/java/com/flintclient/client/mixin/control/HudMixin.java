package com.flintclient.client.mixin.control;

import com.flintclient.client.control.ControlCenter;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Draws Flint's HUD as part of Minecraft's. (Minecraft's effect icons are hidden inside their own method, in HudFeatureMixin, so
 * the HUD layers other mods attach around that call, Simple Voice Chat's group icons among them, still draw.)
 *
 * <p>Flint's HUD is drawn just before chat, so it sits above the hotbar, bars and scoreboard and under chat
 * (which starts a new layer of its own) and the tab list. Hooking the chat call inherits vanilla's rules for
 * free: it isn't reached while the HUD is hidden (F1) or behind the loading screen.
 *
 * <p>The hook wraps a call in a condition rather than injecting a callback: the wrapper stores the call's
 * arguments in locals and calls the handler directly, so nothing is allocated per frame. Drawing from a
 * condition handler is a side effect MixinExtras doesn't promise: if another mod also wraps the chat call
 * and its condition (checked first when its wrapper is outermost) says no, chat and Flint's HUD are both
 * skipped. And a mod that {@code @Redirect}s the call would stop this hook applying, which fails at
 * start-up (the mixins are required); no mod in the tested stack touches them.
 */
@Mixin(Gui.class)
abstract class HudMixin {
	@WrapWithCondition(method = "extractRenderState", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/Gui;extractChat(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"))
	private boolean flint$hudBeforeChat(Gui hud, GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		ControlCenter.renderHud(graphics, deltaTracker);
		return true;
	}
}
