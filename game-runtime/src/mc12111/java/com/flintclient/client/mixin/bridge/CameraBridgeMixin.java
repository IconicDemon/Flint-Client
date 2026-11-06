package com.flintclient.client.mixin.bridge;

import com.flintclient.client.compat.Bridges;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;

/** Minecraft 1.21.11: Camera answers the 26.x methods in {@link Bridges.Camera}. */
@Mixin(Camera.class)
abstract class CameraBridgeMixin implements Bridges.Camera {
}
