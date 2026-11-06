package com.flintclient.client.platform;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;

/**
 * A key press as Flint's screens read it: the key, its modifiers, and the questions the kit asks of them (Escape, an arrow,
 * a shortcut). Each engine has its own (26.x numbers keys as SDL does, 1.21.x as GLFW, 1.8.9 as LWJGL 2); the kit only
 * compares {@link #key()} with {@link Keys}' constants of the same engine and asks these questions, so it never depends on
 * the numbering. This one wraps Minecraft's {@link KeyEvent} and answers exactly as it does. Platform class (see
 * {@link Clock}).
 */
public final class KeyInput {
	private final KeyEvent event;

	private KeyInput(KeyEvent event) {
		this.event = event;
	}

	public static KeyInput of(KeyEvent event) {
		return new KeyInput(event);
	}

	/** Minecraft's own event, for code on this engine's side of the platform classes. */
	public KeyEvent event() {
		return event;
	}

	/** The key (compare with {@link Keys}). */
	public int key() {
		return event.key();
	}

	/** The key's stored name ({@code key.keyboard.a}), as Flint's key settings keep it. */
	public String keyName() {
		return InputConstants.getKey(event).getName();
	}

	/** Enter, Space or the keypad's Enter: activates a focused control. */
	public boolean isSelection() {
		return event.isSelection();
	}

	/** Enter or the keypad's Enter. */
	public boolean isConfirmation() {
		return event.isConfirmation();
	}

	public boolean isEscape() {
		return event.isEscape();
	}

	public boolean isLeft() {
		return event.isLeft();
	}

	public boolean isRight() {
		return event.isRight();
	}

	public boolean isUp() {
		return event.isUp();
	}

	public boolean isDown() {
		return event.isDown();
	}

	/** The character a shortcut with this key types on the player's keyboard layout ('z' for Ctrl+Z on any layout), or 0. */
	public int shortcutKey() {
		return com.flintclient.client.compat.McCompat.shortcutKey(event);
	}

	public boolean hasShiftDown() {
		return event.hasShiftDown();
	}

	public boolean hasAltDown() {
		return event.hasAltDown();
	}

	/** The edit shortcut modifier: Control, or Command on macOS. */
	public boolean hasControlDownWithQuirk() {
		return event.hasControlDownWithQuirk();
	}

	public boolean isSelectAll() {
		return event.isSelectAll();
	}

	public boolean isCopy() {
		return event.isCopy();
	}

	public boolean isPaste() {
		return event.isPaste();
	}

	public boolean isCut() {
		return event.isCut();
	}
}
