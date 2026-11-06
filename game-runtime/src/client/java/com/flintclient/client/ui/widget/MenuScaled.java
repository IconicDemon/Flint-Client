package com.flintclient.client.ui.widget;

import com.flintclient.client.platform.Frame;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.ui.FlintFrame;
import com.flintclient.client.ui.MenuScale;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * One of Flint's main-menu controls on a screen laid out in Minecraft's GUI units (Minecraft's own title screen): the control
 * keeps its place and size in menu units ({@link MenuScale}), so it comes out exactly as on Flint's menu, pixel for pixel,
 * and this button stands around it in GUI units to take the clicks, the keyboard focus and the narration for it. The pointer
 * is handed on in menu units, so the control lights up as it would on Flint's menu.
 */
public final class MenuScaled extends FlintButton {
	private final FlintButton inner;
	private final BooleanSupplier drawn;

	/** {@code inner} placed in menu units; {@code drawn} false while something else draws it (a blend between the menus). */
	public MenuScaled(FlintButton inner, BooleanSupplier drawn) {
		super(inner.getMessage(), inner::activate);
		this.inner = inner;
		this.drawn = drawn;
	}

	public FlintButton inner() {
		return inner;
	}

	/** Places the control at ({@code x}, {@code y}) in menu units, and this button over it in GUI units (rounded outwards). */
	public void place(int x, int y, int w, int h) {
		inner.setRectangle(w, h, x, y);
		float toGui = MenuScale.pixels() / Frame.windowGuiScale();
		int gx = (int) Math.floor(x * toGui);
		int gy = (int) Math.floor(y * toGui);
		int gr = (int) Math.ceil((x + w) * toGui);
		int gb = (int) Math.ceil((y + h) * toGui);
		setRectangle(Math.max(1, gr - gx), Math.max(1, gb - gy), gx, gy);
	}

	/**
	 * Draws the control at the menu's scale, with the pointer ({@code guiMouseX}, {@code guiMouseY} in GUI units; negative: none)
	 * in menu units.
	 */
	public void paintInner(GuiGraphicsExtractor graphics, int guiMouseX, int guiMouseY, float partialTick) {
		FlintFrame.begin(graphics);
		Canvas m = Canvas.beginAtGuiScale(Frame.of(graphics), MenuScale.pixels());
		try {
			int mx = guiMouseX < 0 ? -1 : (int) MenuScale.toMenu(guiMouseX);
			int my = guiMouseY < 0 ? -1 : (int) MenuScale.toMenu(guiMouseY);
			inner.active = active;
			if (isFocused() != inner.isFocused()) {
				inner.setFocused(isFocused());
			}
			inner.extractRenderState(graphics, mx, my, partialTick);
		} finally {
			m.end();
			FlintFrame.end();
		}
	}

	/** A click: the control learns where it landed, in menu units, before it acts on it. */
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		inner.pointAt(MenuScale.toMenu(event.x()), MenuScale.toMenu(event.y()));
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	protected void paint(Canvas c) {
		if (drawn.getAsBoolean()) {
			paintInner(c.frame().graphics(), pointerX, pointerY, 0.0F);
		}
	}
}
