package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * Flint's key bindings, as ordinary Minecraft key mappings: they appear under "Flint Client" in Options →
 * Controls → Key Binds, can be rebound there, and are saved in options.txt like vanilla's, where a player's
 * saved choice always wins over these defaults.
 * <ul>
 * <li>Open Flint Control Center: Right Shift by default. Vanilla binds nothing to it (sneak is Left
 * Shift).</li>
 * <li>Open HUD editor: unbound by default; the Control Center also opens the editor.</li>
 * </ul>
 * Keyboard defaults use {@link InputConstants.Type#KEYBOARD}, whose values are SDL scancodes in 26.3, the
 * same as vanilla's own defaults. Created when {@code Options} first loads (see {@code OptionsMixin});
 * a category can be registered only once, hence the single static instance.
 */
public final class ControlKeys {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(FlintClient.MOD_ID, "main"));
	static final ControlKeys INSTANCE = new ControlKeys();

	private final KeyMapping controlCenter = new KeyMapping("key.flintclient.control_center", InputConstants.Type.KEYBOARD, InputConstants.KEY_RSHIFT, CATEGORY);
	private final KeyMapping hudEditor = new KeyMapping("key.flintclient.hud_editor", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY);
	private final KeyMapping[] all = {controlCenter, hudEditor};

	private ControlKeys() {
	}

	/** Creates the key bindings if that hasn't happened yet, and returns them. */
	public static ControlKeys get() {
		return INSTANCE;
	}

	/** {@code keyMappings} (Minecraft's list) with Flint's bindings added if they're missing; see {@link MappingList}. */
	public KeyMapping[] addTo(KeyMapping[] keyMappings) {
		return MappingList.appendMissing(keyMappings, all);
	}

	public KeyMapping controlCenter() {
		return controlCenter;
	}

	public KeyMapping hudEditor() {
		return hudEditor;
	}
}
