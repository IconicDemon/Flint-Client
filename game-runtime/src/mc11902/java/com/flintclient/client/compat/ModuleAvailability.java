package com.flintclient.client.compat;

import com.flintclient.client.control.model.ModuleSettings;
import com.flintclient.client.control.model.SettingDescriptor;
import com.flintclient.client.control.model.mods.PlayerMods;
import com.flintclient.client.control.model.mods.ServerMods;
import com.flintclient.client.control.model.mods.ToolMods;
import com.flintclient.client.control.model.mods.UtilityMods;
import com.flintclient.client.control.model.mods.VisualMods;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The modules and settings Minecraft 1.19.2 can't have, and why (shown on their card and page; they can't be turned on). Kept
 * apart from {@link McCompat}, which makes Minecraft objects as it loads: every module's description asks this as it is built,
 * on Flint's settings thread while Minecraft itself is still starting (ControlState.readAhead), so this class touches nothing
 * of Minecraft's (see the 1.21.1 overlay's).
 */
public final class ModuleAvailability {
	private static final String NO_POST_PASSES = "Needs the GPU post-processing passes Minecraft added in 1.21.5; 1.19.2 doesn't have them";
	private static final String NO_DEBUG_ENTRIES = "Works on the configurable F3 screen Minecraft added in 1.21.9; 1.19.2's F3 screen is fixed";
	private static final String NO_MENU_BLUR = "Minecraft 1.19.2 has no menu background blur (1.20.5 added it)";

	private static final Map<String, String> UNAVAILABLE = Map.ofEntries(
		Map.entry("color_saturation", NO_POST_PASSES),
		Map.entry("custom_f3", NO_DEBUG_ENTRIES),
		Map.entry("menu_blur", NO_MENU_BLUR));

	private ModuleAvailability() {
	}

	/** Why the module {@code id} can't work on this Minecraft, or null when it can. */
	public static @Nullable String unavailable(String id) {
		return UNAVAILABLE.get(id);
	}

	/** Why {@code setting} can't apply on 1.19.2, or null when it does (the rest of its module works). */
	public static @Nullable String settingUnavailable(SettingDescriptor<?> setting) {
		if (setting == UtilityMods.Chat.HEAD_POSITION) {
			return "Minecraft 1.19.2 can't put a head inside chat text (1.21.9 added that), so heads go before the line";
		}
		if (setting == VisualMods.FallingLeaves.VANILLA_PARTICLES || setting == VisualMods.FallingLeaves.VANILLA_TEXTURES) {
			return "Minecraft 1.19.2 has no falling leaves of its own (1.21.5 added them), so Flint's are used";
		}
		if (setting == ToolMods.Animations.MAP || setting == ToolMods.Animations.LANTERNS || setting == ToolMods.Animations.OFFHAND_HIDING
			|| setting == ToolMods.Animations.NO_SLEEP_ITEMS) {
			return "Minecraft 1.19.2 draws held items without the render states these need (1.21.2 added them)";
		}
		if (setting == ModuleSettings.Armor.ICONS) {
			return "Minecraft 1.19.2 has no outlines for empty slots in its GUI (1.21.2 added them)";
		}
		if (setting == PlayerMods.HitColor.HIT_INDICATOR || setting == PlayerMods.HitColor.HIT_COLOR || setting == PlayerMods.HitColor.HIT_DURATION
			|| setting == PlayerMods.HitColor.HIT_SIZE) {
			return "Minecraft 1.19.2's server doesn't send where a hit came from (1.19.4 added that), so there's no direction to point to";
		}
		if (setting == PlayerMods.KillSounds.BOW || setting == PlayerMods.KillSounds.BOW_VOLUME || setting == PlayerMods.KillSounds.ROD
			|| setting == PlayerMods.KillSounds.ROD_VOLUME || setting == PlayerMods.KillSounds.THROWABLE
			|| setting == PlayerMods.KillSounds.THROWABLE_VOLUME) {
			return "Minecraft 1.19.2's server doesn't say what hit an entity (1.19.4 added that), so only your melee hits count as yours";
		}
		if (setting == ModuleSettings.Armor.MACE || setting == com.flintclient.client.control.model.mods.MoreMods.UhcOverlay.MACE) {
			return "Minecraft 1.19.2 has no mace (1.21 added it)";
		}
		if (setting == PlayerMods.AttackIndicator.WIND_CHARGE) {
			return "Minecraft 1.19.2 has no wind charge (1.21 added it)";
		}
		if (setting == com.flintclient.client.control.model.mods.LookMods.Glint.SHINY_POTS) {
			return "Minecraft 1.19.2 gives potions their glint itself (1.19.4 took it away), so they always shine";
		}
		if (setting == com.flintclient.client.control.model.mods.LookMods.Glint.ARMOR_MODE
			|| setting == com.flintclient.client.control.model.mods.LookMods.Glint.ARMOR_COLOR) {
			return "Minecraft 1.19.2 draws items and armor with one glint texture (1.20.5 split them), so armor follows the item glint";
		}
		String soundType = soundTypeUnavailable(setting);
		if (soundType != null) {
			return soundType;
		}
		if (setting == ServerMods.NoChatReports.REMOVE_TELEMETRY_BUTTON) {
			return "Minecraft 1.19.2 has no Telemetry Data button in its options (1.19.3 added it); Disable telemetry still turns telemetry off";
		}
		if (setting == ServerMods.NoChatReports.HIDE_SIGNING_MESSAGE) {
			return "Minecraft 1.19.2 servers ask for signing at login, not with a chat message (1.19.3 changed that); Prompt and On Demand act there";
		}
		return null;
	}

	/** Sound Physics' rows for the sound types 1.19.3 to 1.20 added (the hanging signs on): no 1.19.2 block makes those sounds. */
	private static @Nullable String soundTypeUnavailable(SettingDescriptor<?> setting) {
		String[] types = com.flintclient.client.control.model.mods.SoundPhysicsSettings.SOUND_TYPES;
		int first = java.util.Arrays.asList(types).indexOf("HANGING_SIGN");
		if (first < 0) {
			return null;
		}
		for (int i = first; i < types.length; i++) {
			if (setting == com.flintclient.client.control.model.mods.SoundPhysicsSettings.REFLECTIVITY[i]
				|| setting == com.flintclient.client.control.model.mods.SoundPhysicsSettings.OCCLUSION[i]) {
				return "Minecraft 1.19.2 has no blocks with this sound (1.19.3 to 1.20 added them)";
			}
		}
		return null;
	}
}
