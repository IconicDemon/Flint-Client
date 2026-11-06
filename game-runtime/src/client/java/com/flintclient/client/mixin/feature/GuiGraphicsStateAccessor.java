package com.flintclient.client.mixin.feature;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The maps: the GUI's render state, which their textured polygons are added to as GUI elements ({@code compat.MapQuads}). */
@Mixin(GuiGraphicsExtractor.class)
public interface GuiGraphicsStateAccessor {
	@Accessor("guiRenderState")
	GuiRenderState flint$guiRenderState();
}
