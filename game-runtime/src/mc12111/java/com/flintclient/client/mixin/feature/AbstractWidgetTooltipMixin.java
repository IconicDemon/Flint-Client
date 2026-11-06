package com.flintclient.client.mixin.feature;

import com.flintclient.client.ui.widget.WidgetTooltips;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.WidgetTooltipHolder;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Minecraft 1.21.11 (as 26.1): Flint's buttons draw their own tooltips where vanilla's would be (26.2 and later override a hook for it). */
@Mixin(AbstractWidget.class)
abstract class AbstractWidgetTooltipMixin {
	@WrapWithCondition(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/WidgetTooltipHolder;refreshTooltipForNextRenderPass(Lnet/minecraft/client/gui/GuiGraphics;IIZZLnet/minecraft/client/gui/navigation/ScreenRectangle;)V"))
	private boolean flint$tooltip(WidgetTooltipHolder tooltip, GuiGraphics graphics, int mouseX, int mouseY, boolean hovered, boolean focused,
		ScreenRectangle area) {
		return !WidgetTooltips.drawOwn(this, graphics, mouseX, mouseY);
	}
}
