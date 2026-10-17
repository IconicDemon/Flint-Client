package com.flintclient.client.control.model;

/**
 * Flint's own HUD text colours. None of them carries meaning on its own (no red/green pairs), so a player
 * can pick any of them without losing information. {@link #DEFAULT} means "whatever the theme uses".
 */
public enum HudColor implements Labelled {
	DEFAULT("Default", 0),
	WHITE("White", 0xFFF3ECE2),
	EMBER("Ember", 0xFFE7962F),
	SAND("Sand", 0xFFE3C58F),
	SKY("Sky", 0xFF8EC3E6),
	MINT("Mint", 0xFF8FD3B6),
	ROSE("Rose", 0xFFE8A0B4),
	LAVENDER("Lavender", 0xFFB8A6E8);

	private final String label;
	private final int argb;

	HudColor(String label, int argb) {
		this.label = label;
		this.argb = argb;
	}

	@Override
	public String label() {
		return label;
	}

	/** Opaque ARGB, or 0 for {@link #DEFAULT} (the theme decides). */
	public int argb() {
		return argb;
	}

	public boolean isDefault() {
		return this == DEFAULT;
	}
}
