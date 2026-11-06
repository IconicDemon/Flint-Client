/*
 * Adapted from Custom Crosshair Mod by wjbaker (CC0 1.0; META-INF/licenses/CC0-1.0-CustomCrosshairMod.txt): its Draw
 * Crosshair screen.
 */
package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.CrosshairDrawing;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.mods.PlayerMods.Crosshair;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Crosshair's Draw crosshair editor, as Custom Crosshair Mod's: the drawn crosshair's grid at up to 5 GUI units a pixel
 * (fewer when the window is too small for the whole grid with its title and buttons) on a checkerboard (the middle pixel or 2x2 darker), drawn pixels in the crosshair's color; a click toggles the pixel under it
 * and dragging toggles each new pixel passed over; Reset clears it. Saved when the screen closes.
 */
public final class CrosshairDrawScreen extends Screen {
	/** GUI units a pixel of the grid takes at most (Custom Crosshair Mod's), and the room kept above and below it. */
	private static final int MAX_CELL = 5;
	private static final int ABOVE = 30;
	private static final int BELOW = 6 + 20 + 6;
	private static final int WHITE_140 = 0x8CFFFFFF;
	private static final int GREY_140 = 0x8CEBEEF4;
	private static final int GREY_220 = 0xDCEBEEF4;
	private final @Nullable Screen parent;
	private boolean drawing;
	private int lastX = -1;
	private int lastY = -1;

	public CrosshairDrawScreen(@Nullable Screen parent) {
		super(Component.literal("Draw Crosshair"));
		this.parent = parent;
	}

	private CrosshairDrawing grid() {
		return Mc263Crosshair.drawing();
	}

	/** GUI units a grid pixel takes: as big as fits the window (title above, buttons below), at most {@link #MAX_CELL}. */
	private int cell() {
		int size = grid().width();
		int fit = Math.min((height - ABOVE - BELOW) / size, (width - 2 * 10) / size);
		return Math.max(1, Math.min(MAX_CELL, fit));
	}

	private int left() {
		return width / 2 - grid().width() * cell() / 2;
	}

	private int top() {
		return Math.max(ABOVE, height / 2 - grid().width() * cell() / 2 - 10);
	}

	@Override
	protected void init() {
		int y = top() + grid().width() * cell() + 6;
		addRenderableWidget(Button.builder(Component.literal("Reset"), b -> grid().clear()).bounds(width / 2 - 102, y, 100, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 + 2, y, 100, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.centeredText(font, title, width / 2, top() - 14, 0xFFFFFFFF);
		CrosshairDrawing grid = grid();
		int size = grid.width();
		boolean odd = size % 2 != 0;
		int middle = size / 2;
		int color = 0xFF000000 | Mods.state(Mods.index(Crosshair.ID)).rgb(Crosshair.COLOR);
		int left = left();
		int top = top();
		int cellSize = cell();
		for (int x = 0; x < size; x++) {
			for (int y = 0; y < size; y++) {
				boolean centre = odd ? x == middle && y == middle : (x == middle - 1 || x == middle) && (y == middle - 1 || y == middle);
				int cell = grid.at(x, y) ? color : centre ? GREY_220 : (x % 2 != 0 || y % 2 != 0) && (x % 2 == 0 || y % 2 == 0) ? GREY_140 : WHITE_140;
				graphics.fill(left + x * cellSize, top + y * cellSize, left + x * cellSize + cellSize, top + y * cellSize + cellSize, cell);
			}
		}
	}

	private boolean toggleAt(double mouseX, double mouseY, boolean dragging) {
		int x = (int) Math.floor((mouseX - left()) / cell());
		int y = (int) Math.floor((mouseY - top()) / cell());
		int size = grid().width();
		if (x < 0 || y < 0 || x >= size || y >= size) {
			return false;
		}
		if (!dragging || x != lastX || y != lastY) {
			grid().toggle(x, y);
		}
		lastX = x;
		lastY = y;
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0 && toggleAt(event.x(), event.y(), false)) {
			drawing = true;
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (drawing && event.button() == 0) {
			toggleAt(event.x(), event.y(), true);
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		drawing = false;
		return super.mouseReleased(event);
	}

	@Override
	public void onClose() {
		try {
			grid().save(Mc263Crosshair.drawingFile());
		} catch (IOException | RuntimeException e) {
			FlintClient.LOGGER.warn("Flint couldn't save the drawn crosshair", e);
		}
		Minecraft.getInstance().gui.setScreen(parent);
	}
}
