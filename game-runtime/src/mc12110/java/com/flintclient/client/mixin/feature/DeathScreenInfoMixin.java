package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Features;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Death Info: where you died, one line under the score on the death screen (above its buttons). Minecraft 1.21.10 draws
 * the screen's text in render (1.21.11 collects it in visitText), and the screen doesn't keep the player.
 */
@Mixin(DeathScreen.class)
abstract class DeathScreenInfoMixin extends Screen {
	protected DeathScreenInfoMixin(Component title) {
		super(title);
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void flint$where(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo callback) {
		Component line = Mc263Features.deathScreenLine(Minecraft.getInstance().player);
		if (line != null) {
			graphics.drawCenteredString(font, line, width / 2, 115, 0xFFFFFFFF);
		}
	}
}
