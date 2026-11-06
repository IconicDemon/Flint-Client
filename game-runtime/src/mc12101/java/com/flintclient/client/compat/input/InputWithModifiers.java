package com.flintclient.client.compat.input;

/**
 * Minecraft 1.21.1 hands screens and widgets raw key codes, mouse buttons and characters; 1.21.9 wrapped them in events
 * ({@code net.minecraft.client.input}). The shared sources use the events, so on 1.21.1 they are Flint's own records with
 * the same names and meaning (the line's shared-sources.rules point the imports here and turn the overrides back into
 * 1.21.1's signatures).
 */
public interface InputWithModifiers {
	int NOT_DIGIT = -1;
	/** Ctrl, or Command on macOS: the modifier for edit shortcuts. */
	int EDIT_SHORTCUT_KEY_MODIFIER = net.minecraft.client.Minecraft.ON_OSX ? 8 : 2;

	int input();

	int modifiers();

	default boolean isSelection() {
		return input() == 257 || input() == 32 || input() == 335;
	}

	default boolean isConfirmation() {
		return input() == 257 || input() == 335;
	}

	default boolean isEscape() {
		return input() == 256;
	}

	default boolean isLeft() {
		return input() == 263;
	}

	default boolean isRight() {
		return input() == 262;
	}

	default boolean isUp() {
		return input() == 265;
	}

	default boolean isDown() {
		return input() == 264;
	}

	default boolean isCycleFocus() {
		return input() == 258;
	}

	default int getDigit() {
		int digit = input() - 48;
		return digit >= 0 && digit <= 9 ? digit : NOT_DIGIT;
	}

	default boolean hasAltDown() {
		return (modifiers() & 4) != 0;
	}

	default boolean hasShiftDown() {
		return (modifiers() & 1) != 0;
	}

	default boolean hasControlDown() {
		return (modifiers() & EDIT_SHORTCUT_KEY_MODIFIER) != 0;
	}

	default boolean isSelectAll() {
		return input() == 65 && hasControlDown() && !hasShiftDown() && !hasAltDown();
	}

	default boolean isCopy() {
		return input() == 67 && hasControlDown() && !hasShiftDown() && !hasAltDown();
	}

	default boolean isPaste() {
		return input() == 86 && hasControlDown() && !hasShiftDown() && !hasAltDown();
	}

	default boolean isCut() {
		return input() == 88 && hasControlDown() && !hasShiftDown() && !hasAltDown();
	}
}
