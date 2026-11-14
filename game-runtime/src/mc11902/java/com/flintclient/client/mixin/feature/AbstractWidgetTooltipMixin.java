package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.GuiGraphics;
import com.flintclient.client.ui.widget.WidgetTooltips;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 1.19.2: Flint's buttons draw their own tooltips (26.2 and later override a hook for it). 1.19.2's widgets have
 * no tooltip of their own to replace (a vanilla button draws its tooltip callback while it draws itself), so a Flint button
 * asks for its tooltip right after it is drawn, where 1.20 and later refresh a widget's tooltip.
 */
@Mixin(AbstractWidget.class)
abstract class AbstractWidgetTooltipMixin {
	@Inject(method = "render", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
		target = "Lnet/minecraft/client/gui/components/AbstractWidget;renderButton(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V"))
	private void flint$tooltip(PoseStack pose, int mouseX, int mouseY, float partialTick, CallbackInfo callback) {
		WidgetTooltips.drawOwn(this, GuiGraphics.of(pose), mouseX, mouseY);
	}
}
