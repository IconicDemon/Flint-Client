package com.flintclient.qa;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;

/**
 * Keys and mouse buttons as Minecraft 1.21.10 receives them: GLFW key codes, buttons 0 (left) and 1 (right). GLFW's key state
 * can't be set, so held keys are kept here and Minecraft's isKeyDown answers from them too (mixin/InputConstantsMixin). The
 * handlers' private methods are found by their Mojang names through QaNames (the game runs under intermediary names).
 */
public final class QaInput {
	static final int LEFT = 0;
	static final int RIGHT = 1;
	private static final Set<Integer> HELD = new HashSet<>();
	private static Method keyPress;
	private static Method onButton;

	private QaInput() {
	}

	/** Whether the tour holds this key (GLFW code) down. */
	public static boolean held(int code) {
		return HELD.contains(code);
	}

	/** A key event for a tour key name (tab, escape, enter, space, backspace, arrows, end, rshift, lshift, a letter). */
	static KeyEvent key(String name, int modifiers) {
		return new KeyEvent(keycode(name), 0, modifiers);
	}

	/** A typed character, as a screen receives it (1.21.10's carries modifiers too). */
	static CharacterEvent typed(char c) {
		return new CharacterEvent(c, 0);
	}

	/** A keyboard key (Minecraft's code for it) held or let go where Flint reads mod keys (isKeyDown). */
	static void hold(int code, boolean down) {
		if (down) {
			HELD.add(code);
		} else {
			HELD.remove(code);
		}
	}

	/** A key press or release through Minecraft's keyboard handler (its key mappings). */
	static void keyPress(Minecraft mc, int code, char typed, boolean down) throws ReflectiveOperationException {
		if (keyPress == null) {
			keyPress = QaNames.method(KeyboardHandler.class, "keyPress", long.class, int.class, KeyEvent.class);
		}
		keyPress.invoke(mc.keyboardHandler, mc.getWindow().handle(), down ? 1 : 0, new KeyEvent(code, 0, 0));
	}

	/** A mouse button through Minecraft's own handler (clicks count for CPS). */
	static void mouseButton(Minecraft mc, int button, boolean down) throws ReflectiveOperationException {
		if (onButton == null) {
			onButton = QaNames.method(MouseHandler.class, "onButton", long.class, MouseButtonInfo.class, int.class);
		}
		onButton.invoke(mc.mouseHandler, mc.getWindow().handle(), new MouseButtonInfo(button, 0), down ? 1 : 0);
	}

	/** GLFW key codes, as 1.21.10's key events carry them. */
	private static int keycode(String k) {
		return switch (k) {
			case "tab" -> 258;
			case "escape" -> 256;
			case "enter" -> 257;
			case "space" -> 32;
			case "backspace" -> 259;
			case "up" -> 265;
			case "down" -> 264;
			case "left" -> 263;
			case "right" -> 262;
			case "end" -> 269;
			case "rshift" -> 344;
			case "lshift" -> 340;
			default -> {
				if (k.length() == 1 && k.charAt(0) >= 'a' && k.charAt(0) <= 'z') {
					yield 'A' + (k.charAt(0) - 'a');
				}
				throw new IllegalArgumentException("key " + k);
			}
		};
	}
}
