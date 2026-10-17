package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Chat;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Chat's "Keep chat history": leaving a world doesn't clear the chat while it's on. */
@Mixin(Hud.class)
abstract class HudChatKeepMixin {
	@WrapWithCondition(method = "onDisconnected", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;clearMessages(Z)V"))
	private boolean flint$keepChat(ChatComponent chat, boolean history) {
		return !Mc263Chat.keepHistory();
	}
}
