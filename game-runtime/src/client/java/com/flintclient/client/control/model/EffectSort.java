package com.flintclient.client.control.model;

/** The order of the effects module's rows: the soonest to end first, or by name. */
public enum EffectSort implements Labelled {
	TIME_LEFT("Time left"),
	NAME("Name");

	private final String label;

	EffectSort(String label) {
		this.label = label;
	}

	@Override
	public String label() {
		return label;
	}
}
