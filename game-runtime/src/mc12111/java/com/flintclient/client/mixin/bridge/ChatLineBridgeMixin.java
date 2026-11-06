package com.flintclient.client.mixin.bridge;

import com.flintclient.client.compat.Bridges;
import net.minecraft.client.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;

/** Minecraft 1.21.11: a chat line answers 26.x's parent() ({@link Bridges.ChatLine}). */
@Mixin(GuiMessage.Line.class)
abstract class ChatLineBridgeMixin implements Bridges.ChatLine {
}
