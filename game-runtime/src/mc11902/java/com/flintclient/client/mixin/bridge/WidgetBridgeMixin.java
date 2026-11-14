package com.flintclient.client.mixin.bridge;

import com.flintclient.client.compat.Tooltip;
import com.flintclient.client.compat.WidgetBridge;
import net.minecraft.client.gui.components.AbstractWidget;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** Minecraft 1.19.2: AbstractWidget's place, size, hover state and tooltip through 1.19.4's methods ({@link WidgetBridge}). */
@Mixin(AbstractWidget.class)
abstract class WidgetBridgeMixin implements WidgetBridge {
	@Shadow
	public int x;
	@Shadow
	public int y;
	@Shadow
	protected int height;
	@Shadow
	protected boolean isHovered;
	@Unique
	private @Nullable Tooltip flint$tooltip;

	@Override
	public int getX() {
		return x;
	}

	@Override
	public int getY() {
		return y;
	}

	@Override
	public void setX(int value) {
		x = value;
	}

	@Override
	public void setY(int value) {
		y = value;
	}

	@Override
	public void setHeight(int value) {
		height = value;
	}

	@Override
	public boolean isHovered() {
		return isHovered;
	}

	@Override
	public void setTooltip(@Nullable Tooltip tooltip) {
		flint$tooltip = tooltip;
	}

	@Override
	public @Nullable Tooltip getTooltip() {
		return flint$tooltip;
	}
}
