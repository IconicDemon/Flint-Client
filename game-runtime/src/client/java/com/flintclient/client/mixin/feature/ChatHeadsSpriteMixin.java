package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.McCompat;
import com.flintclient.client.control.platform.Mc263ChatHeads;
import net.minecraft.network.chat.contents.objects.PlayerSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Chat Heads: a head Flint put into a message writes nothing into the chat log ("[Name head]" otherwise). */
@Mixin(PlayerSprite.class)
abstract class ChatHeadsSpriteMixin implements Mc263ChatHeads.HiddenInLog {
	@Unique
	private boolean flint$hidden;

	@Override
	public void flint$hideInLog() {
		flint$hidden = true;
	}

	@Override
	public boolean flint$hiddenInLog() {
		return flint$hidden;
	}

	@Inject(method = McCompat.SPRITE_FALLBACK, at = @At("HEAD"), cancellable = true)
	private void flint$logText(CallbackInfoReturnable<String> callback) {
		if (Mc263ChatHeads.hideInLog(this)) {
			callback.setReturnValue("");
		}
	}
}
