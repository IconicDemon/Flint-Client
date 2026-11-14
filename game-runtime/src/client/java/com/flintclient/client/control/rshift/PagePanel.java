package com.flintclient.client.control.rshift;

import com.flintclient.client.ui.kit.Node;
import org.jspecify.annotations.Nullable;

/**
 * A mod page's own part, shared by every Minecraft line: Item Customizer's whole page, and Crosshair's live preview and drawing
 * pad. {@link #of} picks it for a module; the platform's own extra panels (Pack Organizer's packs) come from {@code MenuHost}.
 */
interface PagePanel {
	/** Whether it goes before the page's sections (its own page), rather than after them. */
	default boolean leads() {
		return true;
	}

	/** Reset Settings: what the panel shows back to its defaults. */
	default void resetAll() {
	}

	/** Whether there is anything for Reset Settings to put back. */
	default boolean resettable() {
		return true;
	}

	/** The shared panel for module {@code moduleId}'s page, or null. */
	static @Nullable Node of(Host host, String moduleId) {
		if (moduleId.equals(com.flintclient.client.control.model.mods.VisualMods.ViewModel.ID)) {
			return new ItemCustomizerPanel(host);
		}
		return null;
	}
}
