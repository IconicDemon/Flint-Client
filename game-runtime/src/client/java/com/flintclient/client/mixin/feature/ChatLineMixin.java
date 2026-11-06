package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Chat;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Chat: each message line as Minecraft draws it (and measures it for clicks), from the top of the chat down: a line
 * without a head moved right to line up with those that have one (Chat Heads' Offset non-player text), and the newest
 * message fading in.
 */
@Mixin(targets = "net.minecraft.client.gui.components.ChatComponent$1")
abstract class ChatLineMixin {
	@Unique
	private int flint$indent;

	@WrapOperation(method = "accept", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;handleMessage(IFLnet/minecraft/util/FormattedCharSequence;)Z"))
	private boolean flint$line(ChatComponent.ChatGraphicsAccess graphics, int y, float opacity, FormattedCharSequence text, Operation<Boolean> original,
		@Local(argsOnly = true) GuiMessage.Line line) {
		float alpha = opacity * Mc263Chat.fade(line);
		int indent = Mc263Chat.lineOffset(line);
		flint$indent = indent;
		if (indent == 0) {
			return original.call(graphics, y, alpha, text);
		}
		graphics.updatePose(Mc263Chat.SHIFT);
		boolean hovered = original.call(graphics, y, alpha, text);
		graphics.updatePose(Mc263Chat.UNSHIFT);
		return hovered;
	}

	/** A message tag's icon sits after the text, which a head moved right. */
	@ModifyArg(method = "accept", index = 0, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;handleTagIcon(IIZLnet/minecraft/client/multiplayer/chat/GuiMessageTag;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag$Icon;)V"))
	private int flint$tagIcon(int x) {
		return x + flint$indent;
	}
}
