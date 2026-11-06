package com.flintclient.client.mixin;

import com.flintclient.client.perf.PerformanceCore;
import com.flintclient.client.ui.screen.FlintTitleScreen;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shows Flint's main menu wherever Minecraft would show its title screen. {@code Gui.setScreen} is the
 * single place every screen passes through, so two swaps cover every route: a title screen passed in
 * (start-up, disconnects, screens returning to a {@code new TitleScreen()} parent) and the one
 * setScreen creates itself when a world is left. Both happen before the screen is stored or
 * initialised, so Minecraft and other mods only ever see Flint's screen. Minecraft's own class is replaced, and a
 * plain stand-in for it another mod shows (FlintTitleScreen.minecraftsTitle); other mods' own title screens pass through
 * untouched. When the player chose Minecraft's own title
 * screen (Vanilla), the swap goes the other way: see {@code FlintTitleScreen.titleFor}.
 *
 * <p>When setScreen finishes, Flint Performance Core hears which screen is now shown and whether a world
 * is loaded. That runs on screen changes only, never per frame.
 */
// After other mods' own swaps (priority above the default 1000): a mod that shows its own stand-in for the title screen
// (Force Close Loading Screen) has done so by the time Flint looks, and the stand-in becomes Flint's menu too.
@Mixin(value = Gui.class, priority = 1500)
abstract class GuiMixin {
	@ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
	private Screen flint$replaceTitleScreen(Screen screen) {
		return FlintTitleScreen.titleFor(screen);
	}

	@ModifyExpressionValue(method = "setScreen", at = @At(value = "NEW", target = "()Lnet/minecraft/client/gui/screens/TitleScreen;"))
	private TitleScreen flint$replaceNewTitleScreen(TitleScreen screen) {
		return (TitleScreen) FlintTitleScreen.titleFor(screen);
	}

	// The stored screen, not the argument: setScreen replaces a null argument with the screen it creates,
	// and a screen's init may itself have switched to another screen by now.
	@Inject(method = "setScreen", at = @At("TAIL"))
	private void flint$screenSet(CallbackInfo callback) {
		PerformanceCore.onScreenSet(((Gui) (Object) this).screen(), Minecraft.getInstance().level != null);
	}
}
