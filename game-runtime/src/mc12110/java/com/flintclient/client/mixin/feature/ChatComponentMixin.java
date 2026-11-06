package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.Bridges;
import com.flintclient.client.control.platform.Mc263Chat;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.List;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chat on Minecraft 1.21.10 (1.21.11's hooks, under 1.21.10's chat drawing): timestamps, stacking repeats, mention
 * highlights and a longer scrollback, all on the way into your own chat window; tabs and search over what it keeps, new
 * messages sliding in and the chat moved. 1.21.10 draws the chat straight onto the screen, in two passes over its lines
 * (backgrounds, then text: ChatLineMixin), and finds what's under the mouse with screenToChatX/Y, so a moved chat moves
 * both. What is sent, signed or reported is untouched.
 */
@Mixin(ChatComponent.class)
abstract class ChatComponentMixin implements Mc263Chat.Lines {
	private static final String RENDER = "render(Lnet/minecraft/client/gui/GuiGraphics;IIIZ)V";

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

	/** Re-wrapping the kept messages (a resize, a tab, a search) shows the ones the tab and search let through. */
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

	/** The chat drawn on screen (heads go on it). */
	@Inject(method = RENDER, at = @At("HEAD"))
	private void flint$drawStart(GuiGraphics graphics, int ticks, int mouseX, int mouseY, boolean focused, CallbackInfo callback) {
		Mc263Chat.drawing(graphics);
	}

	@Inject(method = RENDER, at = @At("RETURN"))
	private void flint$drawEnd(CallbackInfo callback) {
		Mc263Chat.drawing(null);
	}

	/** Where the chat sits: Minecraft's layout (its scale), moved by the module and by a new message sliding in. */
	@WrapOperation(method = RENDER, at = @At(value = "INVOKE", target = "Lorg/joml/Matrix3x2fStack;scale(FF)Lorg/joml/Matrix3x2f;", ordinal = 0))
	private Matrix3x2f flint$place(Matrix3x2fStack pose, float x, float y, Operation<Matrix3x2f> original) {
		Mc263Chat.placed(p -> original.call(pose, x, y)).accept(pose);
		return pose;
	}

	/** ...and what's under the mouse (hover, clicks, tags) found where the chat is drawn. */
	@ModifyVariable(method = "screenToChatX", at = @At("HEAD"), argsOnly = true)
	private double flint$mouseX(double x) {
		return x - Mc263Chat.movedRight();
	}

	@ModifyVariable(method = "screenToChatY", at = @At("HEAD"), argsOnly = true)
	private double flint$mouseY(double y) {
		return y + Mc263Chat.movedUp() - Mc263Chat.slide();
	}

	/** Chat: the background in the module's colour (Minecraft's opacity): the queue row... */
	@ModifyArg(method = RENDER, index = 4, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V", ordinal = 0))
	private int flint$background(int color) {
		return Mc263Chat.background(color);
	}

	/** ...and each message line's (drawn from the first pass's lambda), fading in with a new message. */
	@WrapOperation(method = "method_71992", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ARGB;color(FI)I", ordinal = 0))
	private int flint$lineBackground(float alpha, int color, Operation<Integer> original, @Local(argsOnly = true) GuiMessage.Line line) {
		return Mc263Chat.background(original.call(alpha * Mc263Chat.fade(line), color));
	}

	/** Chat Heads: a line moved right by Offset non-player text gets that much more background. */
	@ModifyArg(method = "method_71992", index = 2, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V", ordinal = 0))
	private int flint$headRoom(int right) {
		return right + com.flintclient.client.control.platform.Mc263ChatHeads.widthDifference();
	}

	/** Chat Heads: heads in chat are laid out and drawn with the module's padding, 3Dness and shadow. */
	@Inject(method = RENDER, at = @At("HEAD"))
	private void flint$headsStart(CallbackInfo callback) {
		com.flintclient.client.control.platform.Mc263ChatHeads.rendering(true);
	}

	@Inject(method = RENDER, at = @At("RETURN"))
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
