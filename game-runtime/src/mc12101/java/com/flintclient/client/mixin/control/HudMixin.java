package com.flintclient.client.mixin.control;

import com.flintclient.client.control.ControlCenter;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws Flint's HUD as part of Minecraft's (Minecraft 1.21.1). Its effect icons are hidden inside their own method (HudFeatureMixin).
 *
 * <p>Flint's HUD is drawn just before chat, so it sits above the hotbar, bars and scoreboard and under chat and the tab
 * list. 1.21.1's HUD is a list of layers ({@code LayeredDraw}) built from method references, so there is no call to wrap:
 * the hook is the start of the chat layer itself, which inherits vanilla's rules the same way (it isn't reached while the
 * HUD is hidden with F1, or behind the loading screen).
 */
@Mixin(Gui.class)
abstract class HudMixin {
	@Inject(method = "renderChat", at = @At("HEAD"))
	private void flint$hudBeforeChat(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo callback) {
		ControlCenter.renderHud(graphics, deltaTracker);
	}
}
