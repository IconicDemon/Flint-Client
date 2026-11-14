package com.flintclient.client.mc;

/** What pressed a control (a click or a key), as newer Minecraft hands it on, for Flint's main menu on Minecraft 1.8.9 (see {@link Component}). */
public interface InputWithModifiers {
	/** A press with no modifier keys. */
	InputWithModifiers NONE = new InputWithModifiers() {
	};
}
