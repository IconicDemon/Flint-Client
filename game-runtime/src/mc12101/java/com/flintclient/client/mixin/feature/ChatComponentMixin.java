package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.ARGB;
import com.flintclient.client.compat.Bridges;
import com.flintclient.client.compat.ChatHeadLines;
import com.flintclient.client.control.platform.Mc263Chat;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chat on Minecraft 1.21.1: timestamps, stacking repeats, mention highlights and a longer scrollback on the way into your own
 * chat window; tabs and search over what it keeps, new messages sliding and fading in, heads before players' messages, and
 * the chat moved. 1.21.1 draws the chat in one pass over its lines from the newest (bottom) up, straight onto the screen,
 * and finds what's under the mouse with screenToChatX/Y, so a moved chat moves both. What is sent, signed or reported is
 * untouched.
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

	/** Chat Heads: a message with a head wraps a head's width narrower, so its lines still end inside the chat. */
	@ModifyArg(method = "addMessageToDisplayQueue", index = 1, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/ComponentRenderUtils;wrapComponents(Lnet/minecraft/network/chat/FormattedText;ILnet/minecraft/client/gui/Font;)Ljava/util/List;"))
	private int flint$headRoom(int width, @Local(argsOnly = true) GuiMessage message) {
		return Math.max(40, width - ChatHeadLines.indent(message));
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
	@WrapOperation(method = RENDER, at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V", ordinal = 0))
	private void flint$place(PoseStack stack, float x, float y, float z, Operation<Void> original, @Local(argsOnly = true) GuiGraphics graphics) {
		Mc263Chat.placed(p -> original.call(stack, x, y, z)).accept(graphics.pose2d());
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

	/** Chat: each message line's background in the module's colour (Minecraft's opacity), fading in with a new message. */
	@WrapOperation(method = RENDER, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V", ordinal = 0))
	private void flint$lineBackground(GuiGraphics graphics, int x0, int y0, int x1, int y1, int color, Operation<Void> original,
		@Local GuiMessage.Line line) {
		float fade = Mc263Chat.fade(line);
		int faded = fade >= 1 ? color : ARGB.color(ARGB.alpha(color) / 255.0F * fade, color);
		original.call(graphics, x0, y0, x1, y1, Mc263Chat.background(faded));
	}

	/** ...and the queue row's. */
	@ModifyArg(method = RENDER, index = 4, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V", ordinal = 2))
	private int flint$queueBackground(int color) {
		return Mc263Chat.background(color);
	}

	/** Each line's text, with its player's head (Chat Heads, compat.ChatHeadLines) before an entry's first (top) line and a new message fading in. */
	@WrapOperation(method = RENDER, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)I"))
	private int flint$line(GuiGraphics graphics, Font font, FormattedCharSequence text, int x, int y, int color, Operation<Integer> original,
		@Local GuiMessage.Line line) {
		float fade = Mc263Chat.fade(line);
		int shown = fade >= 1 ? color : ARGB.color(ARGB.alpha(color) / 255.0F * fade, color);
		GuiMessage parent = line.parent();
		int indent = parent == null ? 0 : ChatHeadLines.indent(parent);
		if (indent == 0) {
			return original.call(graphics, font, text, x, y, shown);
		}
		// Lines go newest first (index 0 at the bottom): a line is its entry's first when the one above it ended another entry.
		int at = -1;
		for (int i = chatScrollbarPos; i < trimmedMessages.size(); i++) {
			if (trimmedMessages.get(i) == line) {
				at = i;
				break;
			}
		}
		boolean first = at < 0 || at + 1 >= trimmedMessages.size() || trimmedMessages.get(at + 1).endOfEntry();
		if (first) {
			ChatHeadLines.draw(graphics, parent, y, ARGB.alpha(shown) / 255.0F);
		}
		return original.call(graphics, font, text, x + indent, y, shown);
	}

	/** A message tag's icon sits after the text, which a head moved right (drawn and hovered alike). */
	@ModifyReturnValue(method = "getTagIconLeft", at = @At("RETURN"))
	private int flint$tagIcon(int x, GuiMessage.Line line) {
		GuiMessage parent = line.parent();
		return parent == null ? x : x + ChatHeadLines.indent(parent);
	}
}
