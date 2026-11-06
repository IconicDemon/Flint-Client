package com.flintclient.legacy.mixin;

import com.flintclient.legacy.Flint;
import com.flintclient.legacy.Marks;
import net.minecraft.client.gui.overlay.PlayerTabOverlay;
import net.minecraft.client.network.PlayerInfo;
import net.minecraft.client.render.TextRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The player list on 1.8.9: the Flint mark before a Flint player's name, as on the other lines (Marks). */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {
	/** The name as the list measures and draws it: with the mark's room for a Flint player. */
	@Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
	private void flint$mark(PlayerInfo player, CallbackInfoReturnable<String> callback) {
		if (Flint.ready() && player.getProfile() != null) {
			callback.setReturnValue(Marks.mark(player.getProfile().getId(), callback.getReturnValue()));
		}
	}

	/** Every line the list draws: a marked name gets its mark; anything else is drawn as it was. */
	@Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/TextRenderer;drawWithShadow(Ljava/lang/String;FFI)I"))
	private int flint$draw(TextRenderer font, String text, float x, float y, int color) {
		return Marks.draw(font, text, x, y, color, true);
	}
}
