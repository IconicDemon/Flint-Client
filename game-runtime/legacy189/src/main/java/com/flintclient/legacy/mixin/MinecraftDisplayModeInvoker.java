package com.flintclient.legacy.mixin;

import net.minecraft.client.Minecraft;
import org.lwjgl.LWJGLException;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Minecraft's own fullscreen display-mode pick, for FullscreenMixin when Borderless fullscreen is off. */
@Mixin(Minecraft.class)
public interface MinecraftDisplayModeInvoker {
	@Invoker("updateDisplayMode")
	void flint$updateDisplayMode() throws LWJGLException;
}
