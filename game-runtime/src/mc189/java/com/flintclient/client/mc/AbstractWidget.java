package com.flintclient.client.mc;

import org.jspecify.annotations.Nullable;

/**
 * Newer Minecraft's widget as Flint's menu controls are written against, on Minecraft 1.8.9 (see {@link Component}): a
 * rectangle in its screen's units with a message, shown or not, active or not, hovered (from the last drawing) and focused.
 * Clicks with the left button reach {@link #onClick}; it plays Minecraft's button click. Render thread.
 */
public abstract class AbstractWidget {
	public boolean active = true;
	public boolean visible = true;
	protected boolean isHovered;
	private int x;
	private int y;
	private int width;
	private int height;
	private Component message;
	private boolean focused;
	private @Nullable Tooltip tooltip;

	protected AbstractWidget(int x, int y, int width, int height, Component message) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		this.message = message;
	}

	public int getX() {
		return x;
	}

	public int getY() {
		return y;
	}

	public int getWidth() {
		return width;
	}

	public int getHeight() {
		return height;
	}

	public void setX(int x) {
		this.x = x;
	}

	public void setY(int y) {
		this.y = y;
	}

	public void setWidth(int width) {
		this.width = width;
	}

	public void setHeight(int height) {
		this.height = height;
	}

	public void setPosition(int x, int y) {
		this.x = x;
		this.y = y;
	}

	public void setSize(int width, int height) {
		this.width = width;
		this.height = height;
	}

	public void setRectangle(int width, int height, int x, int y) {
		this.width = width;
		this.height = height;
		this.x = x;
		this.y = y;
	}

	public ScreenRectangle getRectangle() {
		return new ScreenRectangle(x, y, width, height);
	}

	public Component getMessage() {
		return message;
	}

	public void setMessage(Component message) {
		this.message = message;
	}

	public void setTooltip(@Nullable Tooltip tooltip) {
		this.tooltip = tooltip;
	}

	public @Nullable Tooltip getTooltip() {
		return tooltip;
	}

	public boolean isHovered() {
		return isHovered;
	}

	public boolean isHoveredOrFocused() {
		return isHovered || focused;
	}

	public boolean isFocused() {
		return focused;
	}

	public void setFocused(boolean focused) {
		this.focused = focused;
	}

	/** Shown and usable. */
	public boolean isActive() {
		return visible && active;
	}

	public boolean isMouseOver(double mouseX, double mouseY) {
		return isActive() && mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
	}

	/** Draws the widget (when shown) with the pointer at ({@code mouseX}, {@code mouseY}) in its screen's units. */
	public final void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (!visible) {
			return;
		}
		isHovered = mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
		extractWidgetRenderState(graphics, mouseX, mouseY, partialTick);
		extractTooltipForNextRenderPass(graphics, mouseX, mouseY);
	}

	protected abstract void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick);

	/** After the widget's drawing: a tooltip of its own (newer Minecraft's hook; Flint's controls ask for theirs here). */
	protected void extractTooltipForNextRenderPass(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
	}

	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (isActive() && event.button() == 0 && isMouseOver(event.x(), event.y())) {
			playDownSound();
			onClick(event, doubleClick);
			return true;
		}
		return false;
	}

	public void onClick(MouseButtonEvent event, boolean doubleClick) {
	}

	public boolean mouseReleased(MouseButtonEvent event) {
		return event.button() == 0;
	}

	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		return false;
	}

	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		return false;
	}

	public void mouseMoved(double mouseX, double mouseY) {
	}

	public boolean keyPressed(KeyEvent event) {
		return false;
	}

	/** Minecraft's button click. */
	public void playDownSound() {
		Minecraft.game().getSoundManager().play(net.minecraft.client.sound.instance.SimpleSoundInstance.of(
			new net.minecraft.resource.Identifier("gui.button.press"), 1.0F));
	}

	protected abstract void updateWidgetNarration(NarrationElementOutput output);

	protected void defaultButtonNarrationText(NarrationElementOutput output) {
	}
}
