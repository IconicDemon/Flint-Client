package com.flintclient.client.mixin.bridge;

import com.flintclient.client.compat.Bridges;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;

/** Minecraft 1.21.1: the window's GUI scale as a whole number ({@link Bridges.WindowScale}). */
@Mixin(Window.class)
abstract class WindowScaleBridgeMixin implements Bridges.WindowScale {
}
