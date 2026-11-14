package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.DefaultTooltipPositioner;
import com.flintclient.client.compat.GuiGraphics;
import com.flintclient.client.compat.Tooltips;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every screen tooltip on Minecraft 1.19.2 drawn by Flint (compat.Tooltips), as 1.20's GuiGraphics draws them: placed up and
 * to the right of the pointer, with Scrollable Tooltips' scroll and scale and Glint's backgrounds built in (on 1.20 and later
 * mixins on GuiGraphics and TooltipRenderUtil add those, GuiGraphicsTooltipMixin and GlintBackgroundMixin). 1.19.2's
 * Screen sends all its tooltips (an item's, a list of lines, a text) through renderTooltipInternal.
 */
@Mixin(Screen.class)
abstract class ScreenTooltipMixin {
	@Shadow
	protected Font font;

	@Inject(method = "renderTooltipInternal", at = @At("HEAD"), cancellable = true)
	private void flint$tooltip(PoseStack pose, List<ClientTooltipComponent> lines, int mouseX, int mouseY, CallbackInfo callback) {
		callback.cancel();
		Tooltips.draw(GuiGraphics.of(pose), font, lines, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE);
	}
}
