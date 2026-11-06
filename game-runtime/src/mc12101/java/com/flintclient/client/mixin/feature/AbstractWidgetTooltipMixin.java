package com.flintclient.client.mixin.feature;

import com.flintclient.client.ui.widget.WidgetTooltips;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.WidgetTooltipHolder;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Minecraft 1.21.1: Flint's buttons draw their own tooltips where vanilla's would be (26.2 and later override a hook for it). */
@Mixin(AbstractWidget.class)
abstract class AbstractWidgetTooltipMixin {
	@WrapWithCondition(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/WidgetTooltipHolder;refreshTooltipForNextRenderPass(ZZLnet/minecraft/client/gui/navigation/ScreenRectangle;)V"))
	private boolean flint$tooltip(WidgetTooltipHolder tooltip, boolean hovered, boolean focused, ScreenRectangle area,
		@Local(argsOnly = true) GuiGraphics graphics, @Local(argsOnly = true, ordinal = 0) int mouseX, @Local(argsOnly = true, ordinal = 1) int mouseY) {
		return !WidgetTooltips.drawOwn(this, graphics, mouseX, mouseY);
	}
}
