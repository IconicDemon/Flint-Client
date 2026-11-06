package com.flintclient.client.mixin.bridge;

import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Minecraft 1.21.1: whether the F3 screen is open, read and set (compat.DebugEntries). */
@Mixin(DebugScreenOverlay.class)
public interface DebugScreenOverlayAccessor {
	@Accessor("renderDebug")
	boolean flint$renderDebug();

	@Accessor("renderDebug")
	void flint$setRenderDebug(boolean value);
}
