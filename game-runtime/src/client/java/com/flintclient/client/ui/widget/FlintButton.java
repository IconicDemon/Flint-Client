package com.flintclient.client.ui.widget;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.FlintTooltip;
import com.flintclient.client.ui.Motion;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/**
 * Base for Flint's controls. It is a real Minecraft button, so Tab and arrow-key focus, Enter/Space,
 * narration, tooltips and click sounds all behave like vanilla; only the drawing is Flint's. Hover,
 * focus and press states are eased with real frame time so they never snap or restart.
 */
public abstract class FlintButton extends AbstractButton {
	private static final long TOOLTIP_DELAY_MILLIS = 350;

	private final Runnable action;
	/** 0..1 eased states, read by {@link #paint}. */
	protected float hover;
	protected float focus;
	protected float press;
	private List<TextLine> tooltipLines = List.of();
	private long hoverSince;

	protected FlintButton(Component message, Runnable action) {
		super(0, 0, 0, 0, message);
		this.action = action;
	}

	/**
	 * Sets the tooltip (lines split on newlines; the first is the title). Flint draws it itself; the
	 * vanilla tooltip is kept only so the narrator still reads it.
	 */
	public FlintButton tooltip(Component text) {
		setTooltip(text == null ? null : Tooltip.create(text));
		List<TextLine> lines = new ArrayList<>();
		if (text != null) {
			for (String line : text.getString().split("\n")) {
				lines.add(new TextLine(FlintFonts.SMALL, line));
			}
		}
		tooltipLines = List.copyOf(lines);
		return this;
	}

	@Override
	protected void extractTooltipForNextRenderPass(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		long now = Util.getMillis();
		if (!isHovered()) {
			hoverSince = 0;
		} else if (hoverSince == 0) {
			hoverSince = now;
		}
		boolean hovered = hoverSince != 0 && now - hoverSince >= TOOLTIP_DELAY_MILLIS;
		if (!tooltipLines.isEmpty() && (hovered || keyboardFocused())) {
			FlintTooltip.request(tooltipLines, getRectangle());
		}
	}

	@Override
	public void onPress(InputWithModifiers input) {
		press = 1.0F;
		action.run();
	}

	/** True while focus came from the keyboard (Tab or arrows), so mouse users never see a focus ring. */
	protected boolean keyboardFocused() {
		return isFocused() && Minecraft.getInstance().getLastInputType().isKeyboard();
	}

	@Override
	protected final void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		hover = Motion.approach(hover, active && isHovered() ? 1.0F : 0.0F, Motion.HOVER);
		focus = Motion.approach(focus, active && keyboardFocused() ? 1.0F : 0.0F, Motion.HOVER);
		press = Motion.approach(press, 0.0F, Motion.PRESS);
		Canvas canvas = Canvas.begin(graphics);
		try {
			paint(canvas);
		} finally {
			canvas.end();
		}
	}

	/** Draws the control; bounds are {@link #getX()} etc. in GUI units (use {@link Canvas#px}). */
	protected abstract void paint(Canvas canvas);

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
