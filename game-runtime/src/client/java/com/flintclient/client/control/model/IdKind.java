package com.flintclient.client.control.model;

/** What an id setting names: a sound event or an item. The version layer lists what exists for each. */
public enum IdKind {
	SOUND,
	ITEM,
	/** A slot of your own inventory, as {@code slot:<index>} (0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand). */
	SLOT
}
