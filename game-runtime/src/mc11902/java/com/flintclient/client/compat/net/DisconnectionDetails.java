package com.flintclient.client.compat.net;

import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * 1.21's disconnection details ({@code net.minecraft.network.DisconnectionDetails}) on Minecraft 1.19.2, whose disconnects carry
 * only their reason: what the network hooks read of one. The 1.19.2 network mixins make one from the reason Minecraft hands them.
 */
public record DisconnectionDetails(@Nullable Component reason) {
}
