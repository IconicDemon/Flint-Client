package com.flintclient.client.mixin.bridge;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Minecraft 1.19.2: a screen's own drawing of a text style's hover tooltip (protected there; compat/GuiGraphics calls it). */
@Mixin(Screen.class)
public interface ScreenInvoker {
	@Invoker("renderComponentHoverEffect")
	void flint$renderComponentHoverEffect(PoseStack pose, Style style, int x, int y);
}
