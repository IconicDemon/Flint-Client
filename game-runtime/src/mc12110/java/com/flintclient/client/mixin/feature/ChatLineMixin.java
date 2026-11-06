package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Chat;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Chat on Minecraft 1.21.10: each line's text as it is drawn (the second pass over the lines), moved right to line up
 * with lines that start with a head (Chat Heads' Offset non-player text), and a new message fading in. 1.21.10 has no
 * line-drawing class of its own (1.21.11's {@code ChatComponent$1}), so this targets the chat itself.
 */
@Mixin(ChatComponent.class)
abstract class ChatLineMixin {
	@WrapOperation(method = "method_71991", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)V"))
	private void flint$line(GuiGraphics graphics, Font font, FormattedCharSequence text, int x, int y, int color, Operation<Void> original,
		@Local(argsOnly = true) GuiMessage.Line line) {
		float fade = Mc263Chat.fade(line);
		int shown = fade >= 1 ? color : ARGB.color(ARGB.alpha(color) / 255F * fade, color);
		original.call(graphics, font, text, x + Mc263Chat.lineOffset(line), y, shown);
	}

	/** A message tag's icon sits after the text, which Offset non-player text moved right (drawn and hovered alike). */
	@ModifyReturnValue(method = "getTagIconLeft", at = @At("RETURN"))
	private int flint$tagIcon(int x, GuiMessage.Line line) {
		return x + Mc263Chat.lineOffset(line);
	}
}
