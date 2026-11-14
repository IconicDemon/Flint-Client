package com.flintclient.client.cosmetics.platform;

import com.flintclient.client.cosmetics.ModelItem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The emote wheel: your 8 emote slots around the cursor. Point at one and click (or let go of the wheel key) to play it;
 * Esc, or letting go in the middle, closes it. The game keeps running behind it (not a pause screen).
 */
public final class EmoteWheelScreen extends Screen {
	private static final int RADIUS = 78;
	private static final int TILE_W = 74;
	private static final int TILE_H = 26;
	private static final int ORANGE = 0xFFFF8A1F;
	private final ModelItem[] slots;
	private final net.minecraft.client.KeyMapping key;
	private int hovered = -1;

	EmoteWheelScreen(net.minecraft.client.KeyMapping key) {
		super(Component.literal("Emotes"));
		this.slots = Mc263Emotes.slots();
		this.key = key;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** The slot at angle of ({@code mx}, {@code my}) from the centre, or -1 near the centre. */
	private int slotAt(double mx, double my) {
		double dx = mx - width / 2.0;
		double dy = my - height / 2.0;
		if (dx * dx + dy * dy < 24 * 24) {
			return -1;
		}
		// Slot 0 at the top, going clockwise.
		double angle = Math.atan2(dx, -dy);
		int s = (int) Math.floor((angle + Math.PI / 8) / (Math.PI / 4));
		return Math.floorMod(s, Mc263Emotes.SLOTS);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		hovered = slotAt(mouseX, mouseY);
		int cx = width / 2;
		int cy = height / 2;
		graphics.fill(0, 0, width, height, 0x55000000);
		for (int i = 0; i < Mc263Emotes.SLOTS; i++) {
			double a = i * Math.PI / 4;
			int x = cx + (int) Math.round(Math.sin(a) * RADIUS) - TILE_W / 2;
			int y = cy - (int) Math.round(Math.cos(a) * RADIUS) - TILE_H / 2;
			boolean on = i == hovered;
			boolean empty = slots[i] == null;
			graphics.fill(x, y, x + TILE_W, y + TILE_H, on && !empty ? 0xE0202020 : 0xC0101010);
			if (on && !empty) {
				graphics.fill(x, y + TILE_H - 2, x + TILE_W, y + TILE_H, ORANGE);
			}
			String label = empty ? "Empty" : label(slots[i]);
			graphics.drawCenteredString(font, Component.literal(label), x + TILE_W / 2, y + (TILE_H - 8) / 2, empty ? 0xFF707070 : 0xFFFFFFFF);
		}
		graphics.drawCenteredString(font, Component.literal("EMOTES"), cx, cy - 4, 0xFFFFFFFF);
		if (java.util.Arrays.stream(slots).allMatch(java.util.Objects::isNull)) {
			graphics.drawCenteredString(font, Component.literal("Put emotes on your wheel in Cosmetics (Right Shift)"), cx, cy + RADIUS + TILE_H, 0xFFB0B0B0);
		}
	}

	private static String label(ModelItem item) {
		String id = Mc263Emotes.name(item);
		return id.length() > 11 ? id.substring(0, 10) + "…" : id;
	}

	private void choose(int slot) {
		ModelItem item = slot >= 0 ? slots[slot] : null;
		onClose();
		if (item != null) {
			Mc263Emotes.play(item);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		choose(slotAt(event.x(), event.y()));
		return true;
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (key.matches(event)) {
			choose(hovered);
			return true;
		}
		return super.keyReleased(event);
	}

}
