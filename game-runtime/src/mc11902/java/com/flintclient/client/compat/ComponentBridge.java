package com.flintclient.client.compat;

import net.minecraft.network.chat.MutableComponent;

/**
 * MutableComponent: 1.20's withColor(int) on Minecraft 1.19.2 (whose styles take an RGB colour already). Loom adds this interface
 * to MutableComponent at compile time ({@code src/mc11902/loom/fabric.mod.json}) and mixin.bridge.ComponentBridgeMixin at run time.
 */
public interface ComponentBridge {
	default MutableComponent withColor(int rgb) {
		return ((MutableComponent) (Object) this).withStyle(style -> style.withColor(rgb));
	}
}
