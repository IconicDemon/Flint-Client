package com.flintclient.client.compat;

import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * EditBox: 1.19.3's hint (the grey text an empty box shows) on Minecraft 1.19.2, which shows it as the box's suggestion while
 * the box is empty (mixin.bridge.EditBoxBridgeMixin). Loom adds this interface to EditBox at compile time.
 */
public interface EditBoxBridge {
	default void setHint(@Nullable Component hint) {
		throw new IllegalStateException("EditBoxBridgeMixin isn't applied");
	}
}
