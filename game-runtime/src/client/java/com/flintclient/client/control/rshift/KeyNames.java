package com.flintclient.client.control.rshift;

import com.flintclient.client.platform.Keys;

/** Display names for stored key names ({@code key.keyboard.c} → "C"), as Minecraft's Controls screen shows them. */
final class KeyNames {
	private KeyNames() {
	}

	/** "None" for no key; Minecraft's own name otherwise (falls back to the stored name if Minecraft doesn't know it). */
	static String display(String keyName) {
		return keyName.isEmpty() ? "None" : Keys.displayName(keyName);
	}
}
