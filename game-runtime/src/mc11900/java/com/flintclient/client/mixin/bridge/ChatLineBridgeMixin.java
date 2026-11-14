package com.flintclient.client.mixin.bridge;

import com.flintclient.client.compat.v1190.GuiMessageBridge;
import net.minecraft.client.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Minecraft 1.19 (1.19.0): a chat message or line (one generic GuiMessage; 1.19.1 split it into GuiMessage and GuiMessage.Line)
 * answers 1.19.1's content() and 26.x's parent() ({@link GuiMessageBridge}).
 */
@Mixin(GuiMessage.class)
abstract class ChatLineBridgeMixin implements GuiMessageBridge {
}
