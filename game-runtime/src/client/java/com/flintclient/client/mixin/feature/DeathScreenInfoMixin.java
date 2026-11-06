package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Features;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.TextAlignment;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Death Info: where you died, one line under the score on the death screen (above its buttons). */
@Mixin(DeathScreen.class)
abstract class DeathScreenInfoMixin extends Screen {
	@Shadow
	@Final
	private LocalPlayer player;

	protected DeathScreenInfoMixin(Component title) {
		super(title);
	}

	@Inject(method = "visitText", at = @At("TAIL"))
	private void flint$where(ActiveTextCollector text, CallbackInfo callback) {
		Component line = Mc263Features.deathScreenLine(player);
		if (line != null) {
			text.accept(TextAlignment.CENTER, width / 2, 115, line);
		}
	}
}
