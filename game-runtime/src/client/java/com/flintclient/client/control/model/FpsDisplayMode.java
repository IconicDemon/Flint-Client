package com.flintclient.client.control.model;

/** How the FPS counter is framed: in a box ("80 FPS" on a panel), between brackets ("[80 FPS]"), or as plain text. */
public enum FpsDisplayMode implements Labelled {
	BACKGROUND("Background"),
	BRACKETS("Brackets"),
	TEXT("Just Text");

	private final String label;

	FpsDisplayMode(String label) {
		this.label = label;
	}

	@Override
	public String label() {
		return label;
	}
}
