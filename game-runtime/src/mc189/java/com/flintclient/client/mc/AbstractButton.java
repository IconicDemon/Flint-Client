package com.flintclient.client.mc;

/**
 * Newer Minecraft's button, for Flint's menu controls on Minecraft 1.8.9 (see {@link AbstractWidget}): a click, Enter or Space
 * presses it ({@link #onPress}); its look is the control's own ({@link #extractContents}).
 */
public abstract class AbstractButton extends AbstractWidget {
	protected AbstractButton(int x, int y, int width, int height, Component message) {
		super(x, y, width, height, message);
	}

	public abstract void onPress(InputWithModifiers input);

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		onPress(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!isActive() || !event.isSelection()) {
			return false;
		}
		playDownSound();
		onPress(event);
		return true;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		extractContents(graphics, mouseX, mouseY, partialTick);
	}

	protected abstract void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick);
}
