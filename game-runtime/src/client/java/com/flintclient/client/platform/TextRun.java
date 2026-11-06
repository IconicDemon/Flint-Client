package com.flintclient.client.platform;

import net.minecraft.network.chat.Component;

/** A piece of text in one font, prepared by a {@link TextEngine} for measuring and drawing ({@link Frame#text}). Platform class (see {@link Clock}). */
public final class TextRun {
	private final Component component;

	TextRun(Component component) {
		this.component = component;
	}

	/** Minecraft's own text, for code on this engine's side of the platform classes. */
	public Component component() {
		return component;
	}
}
