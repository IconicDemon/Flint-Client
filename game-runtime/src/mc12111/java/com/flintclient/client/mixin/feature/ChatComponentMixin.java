package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.Bridges;
import com.flintclient.client.control.platform.Mc263Chat;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.joml.Matrix3x2f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chat on Minecraft 1.21.11 (the shared ChatComponentMixin is 26.x's; this is the same set of hooks under 1.21.11's names):
 * timestamps, stacking repeats, mention highlights and a longer scrollback, all on the way into your own chat window;
 * tabs and search over what it keeps, players' heads, new messages sliding in and the chat moved. What is sent, signed
 * or reported is untouched. 1.21.11's chat lines don't know their message, so each line's is recorded as it is made
 * ({@link Bridges#chatLine}).
 */
@Mixin(ChatComponent.class)
abstract class ChatComponentMixin implements Mc263Chat.Lines {
	private static final String DRAW = "render(Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;IIZ)V";

	@Shadow
	@Final
	private List<GuiMessage> allMessages;

	@Shadow
	@Final
	private List<GuiMessage.Line> trimmedMessages;

	@Shadow
	private int chatScrollbarPos;

	@Shadow
	private void refreshTrimmedMessages() {
	}

	@Override
	public List<GuiMessage.Line> flint$lines() {
		return trimmedMessages;
	}

	@Override
	public int flint$scroll() {
		return chatScrollbarPos;
	}

	/** Hypixel: lobby joins, others' GG and friend comings and goings left out of chat; game ends and requests noticed. */
	@Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
		at = @At("HEAD"), cancellable = true)
	private void flint$hypixel(Component contents, net.minecraft.network.chat.@org.jspecify.annotations.Nullable MessageSignature signature,
		net.minecraft.client.@org.jspecify.annotations.Nullable GuiMessageTag tag, CallbackInfo callback) {
		if (com.flintclient.client.control.platform.Mc263MoreMods.hideIncoming(contents)) {
			callback.cancel();
		}
	}

	@ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
		at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private Component flint$decorate(Component contents) {
		Component shown = Mc263Chat.incoming(contents, allMessages);
		if (Mc263Chat.removedPrevious()) {
			refreshTrimmedMessages();
		}
		return shown;
	}

	/** A new message is kept either way; it shows when the tab and search let it, and then slides in. */
	@WrapOperation(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;addMessageToDisplayQueue(Lnet/minecraft/client/GuiMessage;)V"))
	private void flint$show(ChatComponent chat, GuiMessage message, Operation<Void> original) {
		if (Mc263Chat.shown(message)) {
			original.call(chat, message);
			Mc263Chat.added(message, trimmedMessages, chatScrollbarPos);
		}
	}

	/** Each line made for a message remembers it (26.x's GuiMessage.Line.parent). */
	@ModifyExpressionValue(method = "addMessageToDisplayQueue", at = @At(value = "NEW", target = "net/minecraft/client/GuiMessage$Line"))
	private GuiMessage.Line flint$lineParent(GuiMessage.Line line, @Local(argsOnly = true) GuiMessage message) {
		Bridges.chatLine(line, message);
		return line;
	}

	/**
	 * Re-wrapping the kept messages (a resize, a tab, a search) shows the ones the tab and search let through (1.21.11
	 * re-queues every message, with no filter of its own).
	 */
	@WrapOperation(method = "refreshTrimmedMessages",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;addMessageToDisplayQueue(Lnet/minecraft/client/GuiMessage;)V"))
	private void flint$filter(ChatComponent chat, GuiMessage message, Operation<Void> original) {
		if (Mc263Chat.shown(message)) {
			original.call(chat, message);
		}
	}

	@com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = {"addMessageToDisplayQueue", "addMessageToQueue"}, at = @org.spongepowered.asm.mixin.injection.At(value = "CONSTANT", args = "intValue=100"), require = 0)
	private int flint$history(int limit) {
		return Mc263Chat.historyLimit(limit);
	}

	/** The chat drawn on screen (heads go on it), not only measured for a click. */
	@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIZZ)V", at = @At("HEAD"))
	private void flint$drawStart(GuiGraphics graphics, net.minecraft.client.gui.Font font, int guiHeight, int ticks, int mouseX, boolean focused,
		boolean insertions, CallbackInfo callback) {
		Mc263Chat.drawing(graphics);
	}

	@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIZZ)V", at = @At("RETURN"))
	private void flint$drawEnd(CallbackInfo callback) {
		Mc263Chat.drawing(null);
	}

	/** Where the chat sits: Minecraft's layout, moved by the module and by a new message sliding in (drawing and clicks alike). */
	@WrapOperation(method = DRAW, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;updatePose(Ljava/util/function/Consumer;)V"))
	private void flint$place(ChatComponent.ChatGraphicsAccess graphics, Consumer<Matrix3x2f> layout, Operation<Void> original) {
		original.call(graphics, Mc263Chat.placed(layout));
	}

	/** Chat: the background in the module's colour (Minecraft's opacity): the queue and restriction rows... */
	@WrapOperation(method = DRAW, at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ARGB;black(F)I"))
	private int flint$background(float alpha, Operation<Integer> original) {
		return Mc263Chat.background(original.call(alpha));
	}

	/** ...and each message line's (drawn from a lambda), fading in with a new message. */
	@WrapOperation(method = "method_75802", require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ARGB;black(F)I"))
	private static int flint$lineBackground(float alpha, Operation<Integer> original, @Local(argsOnly = true) GuiMessage.Line line) {
		return Mc263Chat.background(original.call(alpha * Mc263Chat.fade(line)));
	}

	/** Chat Heads: a line moved right by Offset non-player text gets that much more background. */
	@ModifyArg(method = "method_75802", index = 2, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;fill(IIIII)V"))
	private static int flint$headRoom(int right) {
		return right + com.flintclient.client.control.platform.Mc263ChatHeads.widthDifference();
	}

	/** Chat Heads: heads in chat are laid out and drawn with the module's padding, 3Dness and shadow. */
	@Inject(method = DRAW, at = @At("HEAD"))
	private void flint$headsStart(CallbackInfo callback) {
		com.flintclient.client.control.platform.Mc263ChatHeads.rendering(true);
	}

	@Inject(method = DRAW, at = @At("RETURN"))
	private void flint$headsEnd(CallbackInfo callback) {
		com.flintclient.client.control.platform.Mc263ChatHeads.rendering(false);
	}

	/** Chat Heads: the chat log leaves out the heads it put into messages. */
	@Inject(method = "logChatMessage", at = @At("HEAD"))
	private void flint$logStart(CallbackInfo callback) {
		com.flintclient.client.control.platform.Mc263ChatHeads.insideLog(true);
	}

	@Inject(method = "logChatMessage", at = @At("RETURN"))
	private void flint$logEnd(CallbackInfo callback) {
		com.flintclient.client.control.platform.Mc263ChatHeads.insideLog(false);
	}
}
