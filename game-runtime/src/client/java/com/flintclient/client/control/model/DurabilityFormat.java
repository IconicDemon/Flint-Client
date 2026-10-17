package com.flintclient.client.control.model;

/**
 * How the armour module shows an item's remaining durability: a percentage, the uses left, a thin bar, or nothing.
 * Saved by name, so the order here is only the order of the options.
 */
public enum DurabilityFormat implements Labelled {
	PERCENT("Percent"),
	NUMBER("Number"),
	BAR("Bar"),
	NONE("Hidden");

	private final String label;

	DurabilityFormat(String label) {
		this.label = label;
	}

	@Override
	public String label() {
		return label;
	}
}
