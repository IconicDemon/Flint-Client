package com.flintclient.qa;

import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;

/** Keys and mouse buttons as Minecraft 26.3 receives them: SDL scancodes, SDL's keyboard state, buttons 1 (left) and 3 (right). */
final class QaInput {
	static final int LEFT = 1;
	static final int RIGHT = 3;

	private QaInput() {
	}

	/** A key event for a tour key name (tab, escape, enter, space, backspace, arrows, end, rshift, lshift, a letter). */
	static KeyEvent key(String name, int modifiers) {
		return new KeyEvent(scancode(name), keycode(name), modifiers);
	}

	/** A typed character, as a screen receives it. */
	static CharacterEvent typed(char c) {
		return new CharacterEvent(c);
	}

	/** A keyboard key (Minecraft's code for it) held or let go in SDL's key state, which is what Flint reads mod keys from. */
	static void hold(int code, boolean down) {
		org.lwjgl.sdl.SDLKeyboard.SDL_GetKeyboardState().put(code, (byte) (down ? 1 : 0));
	}

	/** A key press or release through Minecraft's keyboard handler (its key mappings). */
	static void keyPress(Minecraft mc, int code, char typed, boolean down) {
		mc.keyboardHandler.keyPress(mc.getWindow().handle(), down ? 1 : 0, new KeyEvent(code, typed, 0));
	}

	/** A mouse button through Minecraft's own handler (clicks count for CPS). */
	static void mouseButton(Minecraft mc, int button, boolean down) {
		mc.mouseHandler.onButton(mc.getWindow().handle(), new MouseButtonInfo(button, 0), down ? 1 : 0);
	}

	/** SDL scancodes, as 26.3's key events carry them. */
	private static int scancode(String k) {
		return switch (k) {
			case "tab" -> 43;
			case "escape" -> 41;
			case "enter" -> 40;
			case "space" -> 44;
			case "backspace" -> 42;
			case "up" -> 82;
			case "down" -> 81;
			case "left" -> 80;
			case "right" -> 79;
			case "end" -> 77;
			case "rshift" -> 229;
			case "lshift" -> 225;
			default -> {
				if (k.length() == 1 && k.charAt(0) >= 'a' && k.charAt(0) <= 'z') {
					yield 4 + (k.charAt(0) - 'a');
				}
				throw new IllegalArgumentException("key " + k);
			}
		};
	}

	private static int keycode(String k) {
		return switch (k) {
			case "escape" -> 27;
			case "enter" -> 13;
			case "backspace" -> 8;
			case "tab" -> 9;
			case "space" -> 32;
			default -> k.length() == 1 ? k.charAt(0) : 0;
		};
	}
}
