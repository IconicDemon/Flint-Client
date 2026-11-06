package com.flintclient.qa;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;

/**
 * Keys and mouse buttons as Minecraft 1.21.1 receives them: GLFW key codes, scan codes and modifiers as plain numbers,
 * buttons 0 (left) and 1 (right). GLFW's key state can't be set, so held keys are kept here and Minecraft's isKeyDown answers
 * from them too (mixin/InputConstantsMixin). The handlers' private methods are found by their Mojang names through QaNames.
 */
public final class QaInput {
	static final int LEFT = 0;
	static final int RIGHT = 1;
	private static final Set<Integer> HELD = new HashSet<>();
	private static Method keyPress;
	private static Method onPress;

	private QaInput() {
	}

	/** Whether the tour holds this key (GLFW code) down. */
	public static boolean held(int code) {
		return HELD.contains(code);
	}

	/** A key for a tour key name (tab, escape, enter, space, backspace, arrows, end, rshift, lshift, a letter): {key, scancode, modifiers}. */
	static int[] key(String name, int modifiers) {
		return new int[] {keycode(name), 0, modifiers};
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
			keyPress = QaNames.method(KeyboardHandler.class, "keyPress", long.class, int.class, int.class, int.class, int.class);
		}
		keyPress.invoke(mc.keyboardHandler, mc.getWindow().getWindow(), code, 0, down ? 1 : 0, 0);
	}

	/** A mouse button through Minecraft's own handler (clicks count for CPS). */
	static void mouseButton(Minecraft mc, int button, boolean down) throws ReflectiveOperationException {
		if (onPress == null) {
			onPress = QaNames.method(MouseHandler.class, "onPress", long.class, int.class, int.class, int.class);
		}
		onPress.invoke(mc.mouseHandler, mc.getWindow().getWindow(), button, down ? 1 : 0, 0);
	}

	/** GLFW key codes. */
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
