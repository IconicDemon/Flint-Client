package com.flintclient.client.mixin.bridge;

import com.flintclient.client.compat.Bridges;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;

/** Minecraft 1.21.11: ClientLevel answers the 26.x methods in {@link Bridges.Level}. */
@Mixin(ClientLevel.class)
abstract class LevelBridgeMixin implements Bridges.Level {
}
