package com.flintclient.client.control.model;

/** What the movement keys of the keystrokes module say: the bound key names, or arrows (↑ ← ↓ →). */
public enum KeyLabelStyle implements Labelled {
	BOUND("Your keys"),
	ARROWS("Arrows");

	private final String label;

	KeyLabelStyle(String label) {
		this.label = label;
	}

	@Override
	public String label() {
		return label;
	}
}
