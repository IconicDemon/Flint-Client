package com.flintclient.client.mixin.bridge;

import com.flintclient.client.compat.Bridges;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;

/** Minecraft 1.21.11: ChatComponent answers the 26.x methods in {@link Bridges.Chat}. */
@Mixin(ChatComponent.class)
abstract class ChatBridgeMixin implements Bridges.Chat {
}
