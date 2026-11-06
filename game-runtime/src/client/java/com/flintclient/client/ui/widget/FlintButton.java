package com.flintclient.client.ui.widget;

import com.flintclient.client.platform.Frame;
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
	/** How long hover and focus take to fade in or out. */
	protected static final float HOVER_MILLIS = 140.0F;
	/** How long a press takes to settle. */
	protected static final float PRESS_MILLIS = 220.0F;

	private final Runnable action;
	/** 0..1 eased states, read by {@link #paint}. */
	protected float hover;
	protected float focus;
	protected float press;
	/** Their linear progress (constant rate), which the eased values are drawn from. */
	private float hoverLinear;
	private float focusLinear;
	private float pressLinear;
	private List<TextLine> tooltipLines = List.of();
	private long hoverSince;
	/** Where the pointer was at the last drawing, in this control's units (a part of it under the pointer, say). */
	protected int pointerX = Integer.MIN_VALUE;
	protected int pointerY = Integer.MIN_VALUE;

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

	/** In place of vanilla's tooltip: AbstractWidget's hook from 26.2 on; 26.1 has none, and its overlay's mixin calls this. */
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
		activate();
	}

	/** Where the pointer is now, in this control's units (a click, before the next drawing). */
	public void pointAt(double x, double y) {
		pointerX = (int) Math.floor(x);
		pointerY = (int) Math.floor(y);
	}

	/** Does what a press does (shows the press and runs the action): a click, Enter or Space, or a control standing in for it. */
	public void activate() {
		pressLinear = 1.0F;
		press = 1.0F;
		action.run();
	}

	/** How lit it is now (0..1, eased): under the pointer or focused from the keyboard. */
	public float lit() {
		return Math.max(hover, focus);
	}

	/** True while focus came from the keyboard (Tab or arrows), so mouse users never see a focus ring. */
	protected boolean keyboardFocused() {
		return isFocused() && Minecraft.getInstance().getLastInputType().isKeyboard();
	}

	@Override
	protected final void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		pointerX = mouseX;
		pointerY = mouseY;
		// Real frame time, the same at any frame rate: hover and focus fade over 140 ms, eased in and out; a press shows at once
		// and settles over 220 ms.
		hoverLinear = Motion.toward(hoverLinear, active && isHovered() ? 1.0F : 0.0F, HOVER_MILLIS);
		focusLinear = Motion.toward(focusLinear, active && keyboardFocused() ? 1.0F : 0.0F, HOVER_MILLIS);
		pressLinear = Motion.toward(pressLinear, 0.0F, PRESS_MILLIS);
		hover = Motion.smooth(hoverLinear);
		focus = Motion.smooth(focusLinear);
		press = Motion.smooth(pressLinear);
		Canvas canvas = Canvas.begin(Frame.of(graphics));
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
