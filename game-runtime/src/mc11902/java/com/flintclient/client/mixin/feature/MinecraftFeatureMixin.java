package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Features;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GUI Scale: the scale Minecraft fits to the window, the module's while on (your own setting is never changed). Titles' Clear
 * on disconnect: leaving a world or server wipes a title that would otherwise stay on screen. Minecraft 1.19.2 fits the scale
 * in resizeDisplay and leaves a world in clearLevel(Screen) (later versions' disconnect): the pause menu, the death screen,
 * a lost connection and quitting all go through it, as does loading another world.
 */
@Mixin(Minecraft.class)
abstract class MinecraftFeatureMixin {
	@ModifyArg(method = "resizeDisplay", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;calculateScale(IZ)I"), index = 0)
	private int flint$guiScale(int scale) {
		return Mc263Features.guiScale(scale);
	}

	@Inject(method = "clearLevel(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("HEAD"))
	private void flint$clearTitles(Screen screen, CallbackInfo callback) {
		Mc263Features.titlesDisconnect();
	}
}
