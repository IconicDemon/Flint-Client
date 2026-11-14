package com.flintclient.client.mixin.swap;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Instant Swap (Mc263SwapEngine): once the new game shows itself in the old window's place and mode, its framebuffer size is
 * read again from the window itself, so the GUI scale is worked out from the real size (not the hidden start-up window's).
 */
@Mixin(Window.class)
public interface SwapWindowAccess {
	/** GLFW's framebuffer-size event, as if it had come: the size, the GUI scale and (next frame) the main target follow it. */
	@Invoker("onFramebufferResize")
	void flint$onFramebufferResize(long handle, int width, int height);
}
