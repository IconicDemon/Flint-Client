package com.flintclient.client.mixin.bridge;

import com.flintclient.client.compat.Bridges;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;

/** Minecraft 1.21.11: Minecraft answers the 26.x methods in {@link Bridges.Game}. */
@Mixin(Minecraft.class)
abstract class GameBridgeMixin implements Bridges.Game {
}
