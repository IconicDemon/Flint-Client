package com.flintclient.client.cosmetics.platform;

import com.flintclient.client.control.platform.ControlKeys;
import com.flintclient.client.control.platform.MappingList;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * The "Emote wheel" key (B by default, under Flint's controls): added to Minecraft's key list when options load
 * ({@code mixin/cosmetics/OptionsEmoteKeyMixin}), so it shows in Controls and can be rebound. Pressed in game, it opens the
 * {@link EmoteWheelScreen}; letting go there plays the emote pointed at.
 */
public final class EmoteKeys {
	/** Made only when it is added (a key mapping registers itself with Minecraft when made). */
	private static KeyMapping wheel;

	private EmoteKeys() {
	}

	/** {@code keyMappings} with the wheel key added if it is missing. */
	public static KeyMapping[] addTo(KeyMapping[] keyMappings) {
		if (wheel == null) {
			wheel = new KeyMapping("key.flintclient.emote_wheel", com.flintclient.client.compat.McCompat.KEYBOARD, InputConstants.KEY_B,
				ControlKeys.CATEGORY);
		}
		return MappingList.appendMissing(keyMappings, new KeyMapping[] {wheel});
	}

	/** Every tick: the key opens the wheel in game. */
	static void tick(Minecraft mc) {
		KeyMapping key = wheel;
		if (key == null) {
			return;
		}
		if (mc.player != null) {
			// Your emotes are asked for ahead (every 30 s at most), so the wheel opens with them.
			Mc263Emotes.refreshWheel();
		}
		while (key.consumeClick()) {
			Mc263Emotes.refreshWheel();
			if (mc.gui.screen() == null && mc.player != null) {
				mc.gui.setScreen(new EmoteWheelScreen(key));
			}
		}
	}
}
