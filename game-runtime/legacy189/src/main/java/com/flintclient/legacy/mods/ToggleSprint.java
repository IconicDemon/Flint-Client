package com.flintclient.legacy.mods;

import com.flintclient.client.control.feature.Features;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.PlayerMods;
import com.flintclient.legacy.module.HudBackend;
import net.minecraft.client.Minecraft;
import net.minecraft.client.options.KeyBinding;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * Sprint and sneak by tapping their keys instead of holding them: a tap turns it on, another turns it off (the shared
 * {@code MovementToggles}, as on every line). Minecraft keeps its own rules (sprinting stops when you're hungry or run into a
 * wall); Flint only holds the key down for you. The HUD line that says which is on is the shared view. The 1.8.9 backend of
 * Toggle Sneak/Sprint.
 */
public final class ToggleSprint extends HudBackend {
	public ToggleSprint() {
		super("toggle_sneak_sprint", "togglesprint");
	}

	@Override
	protected void onDisable() {
		Features.MOVEMENT.reset();
	}

	/** Whether Flint holds {@code key} down now (from KeyBinding.isPressed). */
	public boolean holds(KeyBinding key) {
		if (!enabled()) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		return key == mc.options.sprintKey && Features.MOVEMENT.holdSprint() || key == mc.options.sneakKey && Features.MOVEMENT.holdSneak();
	}

	@Override
	public void tick() {
		Minecraft mc = Minecraft.getInstance();
		ModuleState s = state();
		// Keys pressed while a screen is open don't toggle (Minecraft hands them to the screen), and neither does one held
		// through a screen closing (Shift from shift-clicks in the inventory): the keys' real state, screen or not, so a
		// press counts only when it starts in the game.
		Features.MOVEMENT.tick(held(mc.options.sprintKey), held(mc.options.sneakKey), s.bool(PlayerMods.ToggleSprint.SPRINT),
			s.bool(PlayerMods.ToggleSprint.SNEAK), mc.screen == null);
	}

	/**
	 * Whether {@code key}'s key or mouse button is held, read from the keyboard and mouse themselves: Minecraft's own record
	 * of it is cleared when a screen opens and set again only as the screen closes.
	 */
	private static boolean held(KeyBinding key) {
		int code = key == null ? 0 : key.getKeyCode();
		if (code > 0) {
			return code < Keyboard.KEYBOARD_SIZE && Keyboard.isKeyDown(code);
		}
		return code < 0 && code + 100 >= 0 && code + 100 < Mouse.getButtonCount() && Mouse.isButtonDown(code + 100);
	}
}
