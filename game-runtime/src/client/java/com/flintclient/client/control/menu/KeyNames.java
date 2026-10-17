package com.flintclient.client.control.menu;

import com.mojang.blaze3d.platform.InputConstants;

/** Display names for stored key names ({@code key.keyboard.c} → "C"), as Minecraft's Controls screen shows them. */
final class KeyNames {
	private KeyNames() {
	}

	/** "None" for no key; Minecraft's own name otherwise (falls back to the stored name if Minecraft doesn't know it). */
	static String display(String keyName) {
		if (keyName.isEmpty()) {
			return "None";
		}
		try {
			return InputConstants.getKey(keyName).getDisplayName().getString();
		} catch (RuntimeException e) {
			return keyName;
		}
	}
}
