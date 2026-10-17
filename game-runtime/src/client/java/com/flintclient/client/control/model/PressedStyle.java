package com.flintclient.client.control.model;

/**
 * How a held key looks in the keystrokes module: filled with the pressed colour, outlined in it, or inverted
 * (the key takes the text colour and its label the panel's). Each changes the key's shape or contrast, not only
 * its colour.
 */
public enum PressedStyle implements Labelled {
	FILL("Fill"),
	OUTLINE("Outline"),
	INVERT("Invert");

	private final String label;

	PressedStyle(String label) {
		this.label = label;
	}

	@Override
	public String label() {
		return label;
	}
}
