package com.flintclient.client.compat.screens;

import com.flintclient.client.compat.DefaultTooltipPositioner;
import com.flintclient.client.compat.DeferredTooltip;
import com.flintclient.client.compat.GuiGraphics;
import com.flintclient.client.compat.Tooltip;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * 1.19.4's {@code StringWidget} on Minecraft 1.19.2: one line of text as a widget, as wide as the text, white with a shadow,
 * not clickable or focusable, with its tooltip (1.19.4's, kept by compat/WidgetBridge) shown while the pointer is over it.
 */
public final class StringWidget extends AbstractWidget {
	/** 1.19.4's widget tooltips wrap at 170 pixels. */
	private static final int TOOLTIP_WIDTH = 170;
	private final Font font;
	private int color = 0xFFFFFF;

	public StringWidget(Component message, Font font) {
		super(0, 0, font.width(message.getVisualOrderText()), 9, message);
		this.font = font;
		this.active = false;
	}

	public StringWidget setColor(int color) {
		this.color = color;
		return this;
	}

	@Override
	public void renderButton(PoseStack pose, int mouseX, int mouseY, float partialTick) {
		Component message = getMessage();
		int x = this.x + Math.round(0.5F * (width - font.width(message)));
		int y = this.y + (height - 9) / 2;
		GuiGraphics.of(pose).drawString(font, message.getVisualOrderText(), x, y, color);
		Tooltip tooltip = getTooltip();
		if (tooltip != null && isHovered) {
			DeferredTooltip.set(font, font.split(tooltip.message(), TOOLTIP_WIDTH), DefaultTooltipPositioner.INSTANCE, mouseX, mouseY);
		}
	}

	@Override
	public void updateNarration(NarrationElementOutput output) {
		Tooltip tooltip = getTooltip();
		if (tooltip != null && tooltip.narration() != null) {
			output.add(NarratedElementType.HINT, tooltip.narration());
		}
	}
}
