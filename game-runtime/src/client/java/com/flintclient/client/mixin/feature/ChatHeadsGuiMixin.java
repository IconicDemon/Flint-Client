package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263ChatHeads;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Chat Heads: the GUI's text (chat's included) is turned into quads here, so the heads' padding, 3Dness and shadow apply. */
@Mixin(GuiRenderer.class)
abstract class ChatHeadsGuiMixin {
	@Inject(method = "prepare", at = @At("HEAD"))
	private void flint$headsStart(CallbackInfo callback) {
		Mc263ChatHeads.rendering(true);
	}

	@Inject(method = "prepare", at = @At("RETURN"))
	private void flint$headsEnd(CallbackInfo callback) {
		Mc263ChatHeads.rendering(false);
	}
}
