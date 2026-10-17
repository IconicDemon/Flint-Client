package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Chat;
import java.util.List;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Chat: timestamps, stacking repeats, mention highlights and a longer scrollback, all on the way into your own chat
 * window. What is sent, signed or reported is untouched: a message keeps its signature, so the server can still delete
 * it, and nothing here reaches the network.
 */
@Mixin(ChatComponent.class)
abstract class ChatComponentMixin {
	@Shadow
	@Final
	private List<GuiMessage> allMessages;

	@Shadow
	private void refreshTrimmedMessages() {
	}

	@ModifyVariable(method = "addMessage", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private Component flint$decorate(Component contents) {
		Component shown = Mc263Chat.incoming(contents, allMessages);
		if (Mc263Chat.removedPrevious()) {
			refreshTrimmedMessages();
		}
		return shown;
	}

	@ModifyConstant(method = {"addMessageToDisplayQueue", "addMessageToQueue"}, constant = @Constant(intValue = 100))
	private int flint$history(int limit) {
		return Mc263Chat.historyLimit(limit);
	}

	/** Chat: the background in the module's colour (Minecraft's opacity): the queue and restriction rows... */
	@com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
		method = "extractRenderState(Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;IILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ARGB;black(F)I"))
	private int flint$background(float alpha, com.llamalad7.mixinextras.injector.wrapoperation.Operation<Integer> original) {
		return com.flintclient.client.control.platform.Mc263Chat.background(original.call(alpha));
	}

	/** ...and each message line's (drawn from a lambda). */
	@com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "lambda$extractRenderState$1", require = 0,
		at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ARGB;black(F)I"))
	private static int flint$lineBackground(float alpha, com.llamalad7.mixinextras.injector.wrapoperation.Operation<Integer> original) {
		return com.flintclient.client.control.platform.Mc263Chat.background(original.call(alpha));
	}
}
