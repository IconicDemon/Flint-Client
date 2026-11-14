package com.flintclient.legacy.cosmetics;

import com.flintclient.client.cosmetics.ModelItem;
import java.util.Arrays;
import java.util.Objects;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.options.KeyBinding;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * The emote wheel on 1.8.9 (26.3's {@code EmoteWheelScreen}, the same look): your 8 emote slots around the cursor. Point at
 * one and click (or let go of the wheel key) to play it; Esc, or letting go in the middle, closes it. The game keeps running
 * behind it (not a pause screen).
 */
public final class EmoteWheelScreen189 extends Screen {
	private static final int RADIUS = 78;
	private static final int TILE_W = 74;
	private static final int TILE_H = 26;
	private static final int ORANGE = 0xFFFF8A1F;
	private final ModelItem[] slots;
	private final KeyBinding key;
	private int hovered = -1;
	private boolean chosen;
	private boolean seenDown;

	EmoteWheelScreen189(KeyBinding key) {
		this.slots = Emotes189.slots();
		this.key = key;
	}

	@Override
	public boolean shouldPauseGame() {
		return false;
	}

	private int slotAt(double mx, double my) {
		double dx = mx - width / 2.0;
		double dy = my - height / 2.0;
		if (dx * dx + dy * dy < 24 * 24) {
			return -1;
		}
		double angle = Math.atan2(dx, -dy);
		int s = (int) Math.floor((angle + Math.PI / 8) / (Math.PI / 4));
		return Math.floorMod(s, Emotes189.SLOTS);
	}

	@Override
	public void render(int mouseX, int mouseY, float tickDelta) {
		hovered = slotAt(mouseX, mouseY);
		int cx = width / 2;
		int cy = height / 2;
		fill(0, 0, width, height, 0x55000000);
		for (int i = 0; i < Emotes189.SLOTS; i++) {
			double a = i * Math.PI / 4;
			int x = cx + (int) Math.round(Math.sin(a) * RADIUS) - TILE_W / 2;
			int y = cy - (int) Math.round(Math.cos(a) * RADIUS) - TILE_H / 2;
			boolean on = i == hovered;
			boolean empty = slots[i] == null;
			fill(x, y, x + TILE_W, y + TILE_H, on && !empty ? 0xE0202020 : 0xC0101010);
			if (on && !empty) {
				fill(x, y + TILE_H - 2, x + TILE_W, y + TILE_H, ORANGE);
			}
			String label = empty ? "Empty" : label(slots[i]);
			textRenderer.draw(label, x + TILE_W / 2 - textRenderer.getWidth(label) / 2, y + (TILE_H - 8) / 2, empty ? 0xFF707070 : 0xFFFFFFFF);
		}
		String title = "EMOTES";
		textRenderer.draw(title, cx - textRenderer.getWidth(title) / 2, cy - 4, 0xFFFFFFFF);
		if (Arrays.stream(slots).allMatch(Objects::isNull)) {
			String hint = "Put emotes on your wheel in Cosmetics (Right Shift)";
			textRenderer.draw(hint, cx - textRenderer.getWidth(hint) / 2, cy + RADIUS + TILE_H, 0xFFB0B0B0);
		}
	}

	private static String label(ModelItem item) {
		String id = Emotes189.name(item);
		return id.length() > 11 ? id.substring(0, 10) + "…" : id;
	}

	private void choose(int slot) {
		if (chosen) {
			return;
		}
		chosen = true;
		ModelItem item = slot >= 0 ? slots[slot] : null;
		minecraft.openScreen(null);
		if (item != null) {
			Emotes189.play(item);
		}
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int button) {
		choose(slotAt(mouseX, mouseY));
	}

	@Override
	public void tick() {
		// Letting go of the wheel key plays the emote pointed at (1.8.9's screens hear no key releases).
		int code = key.getKeyCode();
		boolean down = code > 0 ? Keyboard.isKeyDown(code) : code < 0 && Mouse.isButtonDown(code + 100);
		// Only a key seen held (opened by a real press): a wheel opened another way stays until a click or Esc.
		if (down) {
			seenDown = true;
		} else if (seenDown) {
			choose(hovered);
		}
	}
}
