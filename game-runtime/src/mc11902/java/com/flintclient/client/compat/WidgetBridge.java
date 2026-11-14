package com.flintclient.client.compat;

import org.jspecify.annotations.Nullable;

/**
 * AbstractWidget: 1.19.3 and 1.19.4's getters and setters for a widget's place and size, its hover state and its tooltip, on
 * Minecraft 1.19.2, which keeps them in fields (x and y public, height protected). Loom adds this interface to AbstractWidget
 * at compile time ({@code src/mc11902/loom/fabric.mod.json}) and {@code mixin.bridge.WidgetBridgeMixin} at run time.
 */
public interface WidgetBridge {
	default int getX() {
		throw new IllegalStateException("WidgetBridgeMixin isn't applied");
	}

	default int getY() {
		throw new IllegalStateException("WidgetBridgeMixin isn't applied");
	}

	default void setX(int x) {
		throw new IllegalStateException("WidgetBridgeMixin isn't applied");
	}

	default void setY(int y) {
		throw new IllegalStateException("WidgetBridgeMixin isn't applied");
	}

	default void setHeight(int height) {
		throw new IllegalStateException("WidgetBridgeMixin isn't applied");
	}

	/**
	 * 1.19.3's setPosition(x, y), as a static: 1.19.2's ImageButton and recipe buttons have a setPosition of their own, which an
	 * added interface method of that name would clash with when the jar is remapped (the rules call this instead).
	 */
	static void place(net.minecraft.client.gui.components.AbstractWidget widget, int x, int y) {
		((WidgetBridge) widget).setX(x);
		((WidgetBridge) widget).setY(y);
	}

	/** Its size and place (1.19.4's setRectangle: width, height, x, y). */
	default void setRectangle(int width, int height, int x, int y) {
		((net.minecraft.client.gui.components.AbstractWidget) (Object) this).setWidth(width);
		setHeight(height);
		setX(x);
		setY(y);
	}

	default void setSize(int width, int height) {
		((net.minecraft.client.gui.components.AbstractWidget) (Object) this).setWidth(width);
		setHeight(height);
	}

	default ScreenRectangle getRectangle() {
		net.minecraft.client.gui.components.AbstractWidget self = (net.minecraft.client.gui.components.AbstractWidget) (Object) this;
		return new ScreenRectangle(getX(), getY(), self.getWidth(), self.getHeight());
	}

	/** Whether the pointer was over it at its last drawing (1.19.2 keeps that in a field). */
	default boolean isHovered() {
		throw new IllegalStateException("WidgetBridgeMixin isn't applied");
	}

	/** Its tooltip: on 1.19.2 kept for Flint's own drawing and the narrator ({@link Tooltip}). */
	default void setTooltip(@Nullable Tooltip tooltip) {
		throw new IllegalStateException("WidgetBridgeMixin isn't applied");
	}

	default @Nullable Tooltip getTooltip() {
		throw new IllegalStateException("WidgetBridgeMixin isn't applied");
	}
}
