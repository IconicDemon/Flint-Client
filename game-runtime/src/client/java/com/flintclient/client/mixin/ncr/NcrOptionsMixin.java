package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.platform.Mc263ChatSafety;
import com.google.common.collect.ImmutableList;
import com.mojang.serialization.Codec;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No Chat Reports: "Only Show Secure Chat" reads as off (unsigned chat would all be hidden), with its explanation.
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(Options.class)
abstract class NcrOptionsMixin {
	@Unique
	private @Nullable OptionInstance<Boolean> flint$secureChat;

	@Inject(method = "onlyShowSecureChat", at = @At("RETURN"), cancellable = true)
	private void flint$secureChat(CallbackInfoReturnable<OptionInstance<Boolean>> callback) {
		if (!Mc263ChatSafety.enabled()) {
			return;
		}
		if (flint$secureChat == null) {
			flint$secureChat = new OptionInstance<>("options.onlyShowSecureChat",
				OptionInstance.cachedConstantTooltip(Component.translatable("flintclient.ncr.gui.secure_chat")), OptionInstance.BOOLEAN_TO_STRING,
				new OptionInstance.Enum<>(ImmutableList.of(Boolean.FALSE), Codec.BOOL), false, value -> {
				});
		}
		callback.setReturnValue(flint$secureChat);
	}
}
