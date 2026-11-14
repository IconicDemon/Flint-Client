package com.flintclient.legacy.mixin;

import com.flintclient.legacy.Flint;
import com.flintclient.legacy.mods.Clicks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.living.player.PlayerInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The client tick (Flint's mods tick before input is handled; the Flint menu key is read after), the mouse presses that reach
 * gameplay (CPS: Minecraft passes a mouse button as its number minus 100), the wheel while zoomed, saving on quit, and Flint's
 * main menu in place of Minecraft's title screen.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@org.spongepowered.asm.mixin.Shadow
	public ClientWorld world;

	/**
	 * Every way a screen is shown: Minecraft's title screen (asked for, or shown because nothing is open without a world) becomes
	 * the one the player chose, Flint's main menu or Minecraft's own, as {@code Gui.setScreen} does on every line
	 * ({@code FlintTitleScreen.titleFor}); Flint's menu asked for after the player chose Minecraft's becomes Minecraft's.
	 */
	@org.spongepowered.asm.mixin.injection.ModifyVariable(method = "openScreen", at = @At("HEAD"), argsOnly = true)
	private net.minecraft.client.gui.screen.Screen flint$chosenTitle(net.minecraft.client.gui.screen.Screen screen) {
		try {
			return com.flintclient.legacy.ui.TitleChoice.screenFor(screen, world == null);
		} catch (RuntimeException | LinkageError e) {
			return screen;
		}
	}

	@Inject(method = "tick", at = @At("HEAD"))
	private void flint$tick(CallbackInfo ci) {
		Flint.tick((Minecraft) (Object) this);
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void flint$afterInput(CallbackInfo ci) {
		Flint.afterInput((Minecraft) (Object) this);
	}

	@Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/options/KeyBinding;click(I)V"))
	private void flint$countClick(int code) {
		if (code == -100) {
			Clicks.press(0);
		} else if (code == -99) {
			Clicks.press(1);
		}
		KeyBinding.click(code);
	}

	@Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/living/player/PlayerInventory;scrollInHotbar(I)V"))
	private void flint$scroll(PlayerInventory inventory, int amount) {
		if (!Flint.ready() || !Flint.modules().zoom.scrolled(amount)) {
			inventory.scrollInHotbar(amount);
		}
	}

	@Inject(method = "setWorld(Lnet/minecraft/client/world/ClientWorld;Ljava/lang/String;)V", at = @At("HEAD"))
	private void flint$worldChanged(ClientWorld world, String message, CallbackInfo ci) {
		if (Flint.ready()) {
			Flint.modules().motionBlur.reset();
			// The shared trackers (combat, toggles, the world's clock) start over with each world, as on every line.
			com.flintclient.client.control.feature.Features.worldChanged(world != null);
			if (world == null) {
				Flint.save();
			}
		}
	}

	@Inject(method = "shutdown", at = @At("HEAD"))
	private void flint$save(CallbackInfo ci) {
		Flint.save();
	}
}
