package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.ARGB;
import com.flintclient.client.compat.Bridges;
import com.flintclient.client.compat.ChatHeadLines;
import com.flintclient.client.compat.GuiGraphics;
import com.flintclient.client.control.platform.Mc263Chat;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chat on Minecraft 1.19.2: timestamps, stacking repeats, mention highlights and a longer scrollback on the way into your own
 * chat window; tabs and search over what it keeps, new messages sliding and fading in, heads before players' messages, and
 * the chat moved. What is sent, signed or reported is untouched.
 *
 * <p>1.19.2 adds a message in one private addMessage that wraps it into lines and keeps it (the same call re-wraps a kept
 * message on a resize, a tab or a search, then without keeping it again), where 1.20 and later have two methods: so the
 * message being added is known around that call ({@link #flint$adding}), its lines remember it and it is kept as that same
 * object, and one the tab or search hides is still kept, with no lines. The chat is drawn in one pass over its lines from
 * the newest (bottom) up, under a pose the HUD moved to the chat's bottom-left corner; what's under the mouse is found with
 * screenToChatX/Y, so a moved chat moves both.
 */
@Mixin(ChatComponent.class)
abstract class ChatComponentMixin implements Mc263Chat.Lines {
	@Shadow
	@Final
	private List<GuiMessage> allMessages;

	@Shadow
	@Final
	private List<GuiMessage.Line> trimmedMessages;

	@Shadow
	private int chatScrollbarPos;

	@Shadow
	private void refreshTrimmedMessage() {
	}

	/** The message the private addMessage is adding or re-wrapping now (null: one Flint didn't see coming). */
	@Unique
	private @Nullable GuiMessage flint$adding;
	/** Whether its lines are left out (the tab or search hides it; it is still kept). */
	@Unique
	private boolean flint$hidden;

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
	private void flint$hypixel(Component contents, @Nullable MessageSignature signature, @Nullable GuiMessageTag tag, CallbackInfo callback) {
		if (com.flintclient.client.control.platform.Mc263MoreMods.hideIncoming(contents)) {
			callback.cancel();
		}
	}

	@ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
		at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private Component flint$decorate(Component contents) {
		Component shown = Mc263Chat.incoming(contents, allMessages);
		if (Mc263Chat.removedPrevious()) {
			refreshTrimmedMessage();
		}
		return shown;
	}

	/** A new message is kept either way; it shows when the tab and search let it, and then slides in. */
	@WrapOperation(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
		at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/ChatComponent;addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;ILnet/minecraft/client/GuiMessageTag;Z)V"))
	private void flint$show(ChatComponent chat, Component contents, @Nullable MessageSignature signature, int ticks, @Nullable GuiMessageTag tag,
		boolean refresh, Operation<Void> original) {
		GuiMessage message = new GuiMessage(ticks, contents, signature, tag);
		boolean shown = Mc263Chat.shown(message);
		GuiMessage before = flint$adding;
		boolean hiddenBefore = flint$hidden;
		flint$adding = message;
		flint$hidden = !shown;
		try {
			original.call(chat, contents, signature, ticks, tag, refresh);
		} finally {
			flint$adding = before;
			flint$hidden = hiddenBefore;
		}
		if (shown) {
			Mc263Chat.added(message, trimmedMessages, chatScrollbarPos);
		}
	}

	/** Re-wrapping the kept messages (a resize, a tab, a search) shows the ones the tab and search let through. */
	@WrapOperation(method = "refreshTrimmedMessage",
		at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/ChatComponent;addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;ILnet/minecraft/client/GuiMessageTag;Z)V"))
	private void flint$filter(ChatComponent chat, Component contents, @Nullable MessageSignature signature, int ticks, @Nullable GuiMessageTag tag,
		boolean refresh, Operation<Void> original, @Local GuiMessage message) {
		if (!Mc263Chat.shown(message)) {
			return;
		}
		GuiMessage before = flint$adding;
		boolean hiddenBefore = flint$hidden;
		flint$adding = message;
		flint$hidden = false;
		try {
			original.call(chat, contents, signature, ticks, tag, refresh);
		} finally {
			flint$adding = before;
			flint$hidden = hiddenBefore;
		}
	}

	/** A message the tab or search hides gets no lines (it is still kept below). */
	@ModifyExpressionValue(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;ILnet/minecraft/client/GuiMessageTag;Z)V",
		at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/ComponentRenderUtils;wrapComponents(Lnet/minecraft/network/chat/FormattedText;ILnet/minecraft/client/gui/Font;)Ljava/util/List;"))
	private List<FormattedCharSequence> flint$noLines(List<FormattedCharSequence> lines) {
		return flint$hidden ? List.of() : lines;
	}

	/** Each line made for a message remembers it (26.x's GuiMessage.Line.parent). */
	@ModifyExpressionValue(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;ILnet/minecraft/client/GuiMessageTag;Z)V",
		at = @At(value = "NEW",
		target = "net/minecraft/client/GuiMessage$Line"))
	private GuiMessage.Line flint$lineParent(GuiMessage.Line line) {
		GuiMessage message = flint$adding;
		if (message != null) {
			Bridges.chatLine(line, message);
		}
		return line;
	}

	/** The message kept is the one its lines remember (the same object, not an equal copy). */
	@ModifyExpressionValue(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;ILnet/minecraft/client/GuiMessageTag;Z)V",
		at = @At(value = "NEW",
		target = "net/minecraft/client/GuiMessage"))
	private GuiMessage flint$kept(GuiMessage made) {
		GuiMessage message = flint$adding;
		return message != null ? message : made;
	}

	/** Chat Heads: a message with a head wraps a head's width narrower, so its lines still end inside the chat. */
	@ModifyArg(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;ILnet/minecraft/client/GuiMessageTag;Z)V", index = 1, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/ComponentRenderUtils;wrapComponents(Lnet/minecraft/network/chat/FormattedText;ILnet/minecraft/client/gui/Font;)Ljava/util/List;"))
	private int flint$headRoom(int width) {
		GuiMessage message = flint$adding;
		return message == null ? width : Math.max(40, width - ChatHeadLines.indent(message));
	}

	/** The scrollback's length: the kept messages' and their lines' (1.19.2 trims both to 100 in addMessage). */
	@ModifyExpressionValue(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;ILnet/minecraft/client/GuiMessageTag;Z)V",
		at = @At(value = "CONSTANT", args = "intValue=100"))
	private int flint$history(int limit) {
		return Mc263Chat.historyLimit(limit);
	}

	/** The chat drawn on screen (heads go on it). */
	@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;I)V", at = @At("HEAD"))
	private void flint$drawStart(PoseStack pose, int ticks, CallbackInfo callback) {
		Mc263Chat.drawing(GuiGraphics.of(pose));
	}

	@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;I)V", at = @At("RETURN"))
	private void flint$drawEnd(CallbackInfo callback) {
		Mc263Chat.drawing(null);
	}

	/** Where the chat sits: Minecraft's layout (its scale), moved by the module and by a new message sliding in. */
	@WrapOperation(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;I)V",
		at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V", ordinal = 0))
	private void flint$place(PoseStack stack, float x, float y, float z, Operation<Void> original) {
		Mc263Chat.placed(p -> original.call(stack, x, y, z)).accept(GuiGraphics.of(stack).pose2d());
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
	@WrapOperation(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;I)V", at = @At(value = "INVOKE", ordinal = 0,
		target = "Lnet/minecraft/client/gui/components/ChatComponent;fill(Lcom/mojang/blaze3d/vertex/PoseStack;IIIII)V"))
	private void flint$lineBackground(PoseStack pose, int x0, int y0, int x1, int y1, int color, Operation<Void> original,
		@Local GuiMessage.Line line) {
		float fade = Mc263Chat.fade(line);
		int faded = fade >= 1 ? color : ARGB.color(ARGB.alpha(color) / 255.0F * fade, color);
		original.call(pose, x0, y0, x1, y1, Mc263Chat.background(faded));
	}

	/** ...and the queue row's. */
	@ModifyArg(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;I)V", index = 5, at = @At(value = "INVOKE", ordinal = 2,
		target = "Lnet/minecraft/client/gui/components/ChatComponent;fill(Lcom/mojang/blaze3d/vertex/PoseStack;IIIII)V"))
	private int flint$queueBackground(int color) {
		return Mc263Chat.background(color);
	}

	/** Each line's text, with its player's head (Chat Heads, compat.ChatHeadLines) before an entry's first (top) line and a new message fading in. */
	@WrapOperation(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;I)V", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/Font;drawShadow(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/util/FormattedCharSequence;FFI)I"))
	private int flint$line(Font font, PoseStack pose, FormattedCharSequence text, float x, float y, int color, Operation<Integer> original,
		@Local GuiMessage.Line line) {
		float fade = Mc263Chat.fade(line);
		int shown = fade >= 1 ? color : ARGB.color(ARGB.alpha(color) / 255.0F * fade, color);
		GuiMessage parent = line.parent();
		int indent = parent == null ? 0 : ChatHeadLines.indent(parent);
		if (indent == 0) {
			return original.call(font, pose, text, x, y, shown);
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
			ChatHeadLines.draw(GuiGraphics.of(pose), parent, (int) y, ARGB.alpha(shown) / 255.0F);
		}
		return original.call(font, pose, text, x + indent, y, shown);
	}

	/** A message tag's icon sits after the text, which a head moved right (drawn and hovered alike). */
	@ModifyReturnValue(method = "getTagIconLeft", at = @At("RETURN"))
	private int flint$tagIcon(int x, GuiMessage.Line line) {
		GuiMessage parent = line.parent();
		return parent == null ? x : x + ChatHeadLines.indent(parent);
	}
}
