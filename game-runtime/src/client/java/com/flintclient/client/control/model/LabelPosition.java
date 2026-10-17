package com.flintclient.client.control.model;

/**
 * Where a module's name or unit sits next to its number: after it ("144 FPS", "32 ms"), before it ("FPS 144",
 * "Ping 32 ms"), or left out ("144"). Modules with this setting don't use the shared "Show label" switch.
 */
public enum LabelPosition implements Labelled {
	AFTER("After"),
	BEFORE("Before"),
	HIDDEN("Hidden");

	private final String label;

	LabelPosition(String label) {
		this.label = label;
	}

	@Override
	public String label() {
		return label;
	}
}
