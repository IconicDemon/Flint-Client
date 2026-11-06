package com.flintclient.client.mixin;

import com.flintclient.client.compat.GraphicsBridge;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;

/** Minecraft 1.21.11: GuiGraphics answers 26.x's GuiGraphicsExtractor method names ({@link GraphicsBridge}). */
@Mixin(GuiGraphics.class)
abstract class GraphicsBridgeMixin implements GraphicsBridge {
}
