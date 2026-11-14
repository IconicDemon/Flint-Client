package com.flintclient.client.compat;

import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * 1.19.3's widget tooltip ({@code net.minecraft.client.gui.components.Tooltip}) on Minecraft 1.19.2, whose widgets have none:
 * the text a widget keeps for its tooltip (Bridges.Widget#setTooltip) and the narrator reads.
 */
public final class Tooltip {
	private final Component message;
	private final @Nullable Component narration;

	private Tooltip(Component message, @Nullable Component narration) {
		this.message = message;
		this.narration = narration;
	}

	public static Tooltip create(Component message) {
		return new Tooltip(message, message);
	}

	public static Tooltip create(Component message, @Nullable Component narration) {
		return new Tooltip(message, narration);
	}

	public Component message() {
		return message;
	}

	public @Nullable Component narration() {
		return narration;
	}
}
