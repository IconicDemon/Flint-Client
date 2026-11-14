package com.flintclient.legacy.mixin.swap;

import java.util.Set;
import net.minecraft.client.render.world.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Instant Swap: the new game shows itself once the chunks around the player are built (none left to build). */
@Mixin(WorldRenderer.class)
public interface SwapWorldRendererAccess {
	@Accessor("dirtyChunks")
	Set<?> flint$dirtyChunks();
}
