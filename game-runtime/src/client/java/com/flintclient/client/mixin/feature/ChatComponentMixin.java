package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Chat;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
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
 * Chat: timestamps, stacking repeats, mention highlights and a longer scrollback, all on the way into your own chat
 * window; tabs and search over what it keeps, players' heads, new messages sliding in and the chat moved. What is sent,
 * signed or reported is untouched: a message keeps its signature, so the server can still delete it, and nothing here
 * reaches the network.
 */
@Mixin(ChatComponent.class)
abstract class ChatComponentMixin implements Mc263Chat.Lines {
	private static final String DRAW = "extractRenderState(Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;II"
		+ "Lnet/minecraft/client/gui/components/ChatComponent$DisplayMode;)V";

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
	@Inject(method = "addMessage", at = @At("HEAD"), cancellable = true)
	private void flint$hypixel(Component contents, net.minecraft.network.chat.@org.jspecify.annotations.Nullable MessageSignature signature,
		net.minecraft.client.multiplayer.chat.GuiMessageSource source, net.minecraft.client.multiplayer.chat.@org.jspecify.annotations.Nullable GuiMessageTag tag,
		CallbackInfo callback) {
		if (com.flintclient.client.control.platform.Mc263MoreMods.hideIncoming(contents)) {
			callback.cancel();
		}
	}

	@ModifyVariable(method = "addMessage", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private Component flint$decorate(Component contents) {
		Component shown = Mc263Chat.incoming(contents, allMessages);
		// A stacked repeat: only the earlier copy's own lines go (re-wrapping the whole history for every repeat cost
		// milliseconds a message on a server that repeats one).
		GuiMessage removed = Mc263Chat.removedMessage();
		if (removed != null) {
			trimmedMessages.removeIf(line -> line.parent() == removed);
		}
		return shown;
	}

	/** A new message is kept either way; it shows when the tab and search let it, and then slides in. */
	@WrapOperation(method = "addMessage", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/ChatComponent;addMessageToDisplayQueue(Lnet/minecraft/client/multiplayer/chat/GuiMessage;)V"))
	private void flint$show(ChatComponent chat, GuiMessage message, Operation<Void> original) {
		if (Mc263Chat.shown(message)) {
			original.call(chat, message);
			Mc263Chat.added(message, trimmedMessages, chatScrollbarPos);
		}
	}

	/** Re-wrapping the kept messages (a resize, a tab, a search) shows the ones the tab and search let through. */
	@WrapOperation(method = "refreshTrimmedMessages", at = @At(value = "INVOKE", target = "Ljava/util/function/Predicate;test(Ljava/lang/Object;)Z"))
	private boolean flint$filter(Predicate<GuiMessage> vanilla, Object message, Operation<Boolean> original) {
		return original.call(vanilla, message) && Mc263Chat.shown((GuiMessage) message);
	}

	@com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = {"addMessageToDisplayQueue", "addMessageToQueue"}, at = @org.spongepowered.asm.mixin.injection.At(value = "CONSTANT", args = "intValue=100"), require = 0)
	private int flint$history(int limit) {
		return Mc263Chat.historyLimit(limit);
	}

	/** The chat drawn on screen (heads go on it), not only measured for a click. */
	@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V",
		at = @At("HEAD"))
	private void flint$drawStart(GuiGraphicsExtractor graphics, net.minecraft.client.gui.Font font, int guiHeight, int ticks, int mouseX,
		ChatComponent.DisplayMode mode, boolean insertions, CallbackInfo callback) {
		Mc263Chat.drawing(graphics);
	}

	@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V",
		at = @At("RETURN"))
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
	@WrapOperation(method = "lambda$extractRenderState$1", require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ARGB;black(F)I"))
	private static int flint$lineBackground(float alpha, Operation<Integer> original, @Local(argsOnly = true) GuiMessage.Line line) {
		return Mc263Chat.background(original.call(alpha * Mc263Chat.fade(line)));
	}

	/** Chat Heads: a line moved right by Offset non-player text gets that much more background. */
	@ModifyArg(method = "lambda$extractRenderState$1", index = 2, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;fill(IIIII)V"))
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
