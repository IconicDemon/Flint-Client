package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.GuiGraphics;
import com.flintclient.client.control.platform.Mc263Features;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Death Info: where you died, one line under the score on the death screen (above its buttons). Minecraft 1.19.2 draws the
 * screen's text in render, on the PoseStack it is handed, and the screen doesn't keep the player.
 */
@Mixin(DeathScreen.class)
abstract class DeathScreenInfoMixin extends Screen {
	protected DeathScreenInfoMixin(Component title) {
		super(title);
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void flint$where(PoseStack pose, int mouseX, int mouseY, float partialTick, CallbackInfo callback) {
		Component line = Mc263Features.deathScreenLine(Minecraft.getInstance().player);
		if (line != null) {
			GuiGraphics.of(pose).drawCenteredString(font, line, width / 2, 115, 0xFFFFFFFF);
		}
	}
}
