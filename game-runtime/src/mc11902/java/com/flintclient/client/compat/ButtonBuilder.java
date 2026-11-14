package com.flintclient.client.compat;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * 1.19.3's {@code Button.builder} on Minecraft 1.19.2, whose buttons are made with a constructor: the same calls, the same
 * button. A tooltip given to it shows as 1.19.3's does: over the button while it is hovered (drawn after the screen,
 * {@link DeferredTooltip}).
 */
public final class ButtonBuilder {
	private final Component message;
	private final Button.OnPress onPress;
	private int x;
	private int y;
	private int width = 150;
	private int height = 20;
	private @Nullable Tooltip tooltip;

	private ButtonBuilder(Component message, Button.OnPress onPress) {
		this.message = message;
		this.onPress = onPress;
	}

	public static ButtonBuilder of(Component message, Button.OnPress onPress) {
		return new ButtonBuilder(message, onPress);
	}

	public ButtonBuilder pos(int x, int y) {
		this.x = x;
		this.y = y;
		return this;
	}

	public ButtonBuilder width(int width) {
		this.width = width;
		return this;
	}

	public ButtonBuilder size(int width, int height) {
		this.width = width;
		this.height = height;
		return this;
	}

	public ButtonBuilder bounds(int x, int y, int width, int height) {
		return pos(x, y).size(width, height);
	}

	public ButtonBuilder tooltip(@Nullable Tooltip tooltip) {
		this.tooltip = tooltip;
		return this;
	}

	public Button build() {
		Tooltip shown = tooltip;
		Button button = shown == null ? new Button(x, y, width, height, message, onPress)
			: new Button(x, y, width, height, message, onPress, (b, pose, mouseX, mouseY) -> {
				Minecraft mc = Minecraft.getInstance();
				List<FormattedCharSequence> lines = mc.font.split(shown.message(), 200);
				DeferredTooltip.set(mc.font, lines, DefaultTooltipPositioner.INSTANCE, mouseX, mouseY);
			});
		((WidgetBridge) button).setTooltip(shown);
		return button;
	}
}
