package com.flintclient.client.compat.v1190;

import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;

/**
 * 1.19.1's {@code ChatType.Bound} on Minecraft 1.19 (1.19.0), which hands a chat message's type and its sender's name over
 * separately (the type from the connection's registry, the name in the message's ChatSender): the two together, as the shared
 * sources take them.
 */
public record ChatBound(ChatType chatType, Component name) {
}
