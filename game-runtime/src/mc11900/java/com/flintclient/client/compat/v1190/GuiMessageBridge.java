package com.flintclient.client.compat.v1190;

import com.flintclient.client.compat.Bridges;
import net.minecraft.client.GuiMessage;
import net.minecraft.network.chat.Component;

/**
 * GuiMessage on Minecraft 1.19 (1.19.0): one generic class is both a message the chat keeps ({@code GuiMessage<Component>})
 * and a line it draws ({@code GuiMessage<FormattedCharSequence>}), where 1.19.1 made a record of each (GuiMessage and
 * GuiMessage.Line, which the shared sources use; the rules call a line a GuiMessage too). Loom adds this to GuiMessage at
 * compile time ({@code src/mc11900/loom/fabric.mod.json}) and mixin.bridge.ChatLineBridgeMixin at run time: a kept message's
 * text by 1.19.1's name, and the message a line belongs to ({@link Bridges.ChatLine#parent}, which
 * mixin.feature.ChatComponentMixin records as it makes the line). A line's own text is its getMessage().
 */
public interface GuiMessageBridge extends Bridges.ChatLine {
	/** A kept message's text (1.19.1's GuiMessage.content); only kept messages are asked. */
	default Component content() {
		return (Component) ((GuiMessage<?>) (Object) this).getMessage();
	}
}
