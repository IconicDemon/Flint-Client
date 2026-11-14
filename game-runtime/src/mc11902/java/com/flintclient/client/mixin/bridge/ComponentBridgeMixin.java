package com.flintclient.client.mixin.bridge;

import com.flintclient.client.compat.ComponentBridge;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;

/** Minecraft 1.19.2: MutableComponent answers 1.20's withColor ({@link ComponentBridge}). */
@Mixin(MutableComponent.class)
abstract class ComponentBridgeMixin implements ComponentBridge {
}
