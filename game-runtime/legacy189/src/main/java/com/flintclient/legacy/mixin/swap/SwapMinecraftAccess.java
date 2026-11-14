package com.flintclient.legacy.mixin.swap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.render.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Instant Swap (legacy/swap/SwapEngine189): the frame Minecraft draws into, and its resize after the window changed. */
@Mixin(Minecraft.class)
public interface SwapMinecraftAccess {
	@Accessor("renderTarget")
	RenderTarget flint$renderTarget();

	@Invoker("resize")
	void flint$resize(int width, int height);
}
