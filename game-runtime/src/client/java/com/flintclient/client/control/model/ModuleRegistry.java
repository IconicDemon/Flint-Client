package com.flintclient.client.control.model;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import com.flintclient.client.control.model.mods.HudMods;
import com.flintclient.client.control.model.mods.PlayerMods;
import com.flintclient.client.control.model.mods.UtilityMods;
import com.flintclient.client.control.model.mods.VisualMods;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Every Flint module, in a fixed order: the eight original HUD modules ({@link ModuleId#CORE}) first, then the rest
 * ({@code model.mods}). Layout files and renderers index modules by this order, so new modules are only ever appended.
 * Immutable.
 *
 * <p>Default placements stack the top-left modules 4 units apart at their default size, so turning every
 * module on at its default spot never overlaps (see {@code ModuleRegistryTest}).
 */
public final class ModuleRegistry {
	private static final List<ModuleDescriptor> CORE = List.of(
		new ModuleDescriptor(ModuleId.FPS, "FPS", "Display your frames per second",
			List.of("frames", "framerate", "performance", "counter", "lag"), IconId.GAUGE, Category.PERFORMANCE,
			List.of(
				SettingSection.of("Style", ModuleSettings.Fps.DISPLAY_MODE, ModuleSettings.Fps.BACKGROUND, ModuleSettings.Fps.BACKGROUND_TYPE,
					ModuleSettings.Fps.WIDTH, ModuleSettings.Fps.HEIGHT, ModuleSettings.Fps.BORDER, ModuleSettings.Fps.TEXT_COLOR,
					ModuleSettings.Fps.TEXT_SHADOW, ModuleSettings.Fps.REVERSED),
				SettingSection.advanced("Background and border", ModuleSettings.Fps.ALIGN, ModuleSettings.Fps.BACKGROUND_COLOR,
					ModuleSettings.Fps.BACKGROUND_OPACITY, ModuleSettings.Fps.BORDER_COLOR, ModuleSettings.Fps.BORDER_THICKNESS,
					ModuleSettings.Fps.CORNER_RADIUS),
				SettingSection.advanced("Advanced", ModuleSettings.Fps.SHOW_LABEL, ModuleSettings.Fps.SHOW_FRAME_TIME, ModuleSettings.Fps.SHOW_LOW,
					ModuleSettings.Fps.INTERVAL, ModuleSettings.Fps.WINDOW, ModuleSettings.Fps.DECIMALS)),
			HudPlacement.of(Anchor.TOP_LEFT, 4, 4), ModuleDescriptor.Requirement.NONE, null),
		new ModuleDescriptor(ModuleId.PING, "Ping", "Your latency to the server, as the server reports it",
			List.of("latency", "ms", "connection", "server", "lag"), IconId.SIGNAL, Category.PERFORMANCE,
			List.of(
				SettingSection.of("Display", ModuleSettings.Ping.LABEL_POSITION, ModuleSettings.Ping.SHOW_BARS),
				SettingSection.advanced("Advanced", ModuleSettings.Ping.COLOR_BY_LATENCY)),
			HudPlacement.of(Anchor.TOP_RIGHT, 4, 4), ModuleDescriptor.Requirement.SERVER_LATENCY,
			"Server-reported latency; updates when the server sends it (about every 30 s on vanilla servers)"),
		new ModuleDescriptor(ModuleId.CPS, "CPS", "Clicks per second for each mouse button",
			List.of("clicks", "click speed", "pvp", "mouse", "combat"), IconId.MOUSE, Category.COMBAT,
			List.of(SettingSection.of("Display", ModuleSettings.Cps.BUTTONS, ModuleSettings.Cps.FORMAT, ModuleSettings.Cps.LABEL_POSITION)),
			HudPlacement.of(Anchor.TOP_LEFT, 4, 34), ModuleDescriptor.Requirement.NONE, null),
		new ModuleDescriptor(ModuleId.KEYSTROKES, "Keystrokes", "Your movement keys and mouse buttons as you press them",
			List.of("keys", "wasd", "keyboard", "input", "pvp"), IconId.KEYS, Category.COMBAT,
			List.of(
				SettingSection.of("Display", ModuleSettings.Keystrokes.LAYOUT, ModuleSettings.Keystrokes.SHOW_CPS,
					ModuleSettings.Keystrokes.LABELS),
				SettingSection.of("Style", ModuleSettings.Keystrokes.KEY_SIZE, ModuleSettings.Keystrokes.SPACING,
					ModuleSettings.Keystrokes.CORNERS, ModuleSettings.Keystrokes.PRESSED_STYLE),
				SettingSection.advanced("Advanced", ModuleSettings.Keystrokes.PRESSED_COLOR, ModuleSettings.Keystrokes.FADE_MS)),
			HudPlacement.of(Anchor.BOTTOM_LEFT, 4, 40), ModuleDescriptor.Requirement.NONE, null),
		new ModuleDescriptor(ModuleId.COORDINATES, "Coordinates", "Your position in the world",
			List.of("position", "xyz", "location", "coords", "nether"), IconId.PIN, Category.WORLD,
			List.of(
				SettingSection.of("Display", ModuleSettings.Coordinates.LAYOUT, ModuleSettings.Coordinates.DECIMALS,
					ModuleSettings.Coordinates.LABELS, ModuleSettings.Coordinates.SHOW_DIRECTION),
				SettingSection.advanced("Advanced", ModuleSettings.Coordinates.OTHER_DIMENSION)),
			HudPlacement.of(Anchor.TOP_LEFT, 4, 49), ModuleDescriptor.Requirement.WORLD, null),
		new ModuleDescriptor(ModuleId.DIRECTION, "Direction", "The way you're facing",
			List.of("compass", "facing", "heading", "yaw", "bearing"), IconId.COMPASS, Category.WORLD,
			List.of(
				SettingSection.of("Display", ModuleSettings.Direction.FORMAT),
				SettingSection.advanced("Advanced", ModuleSettings.Direction.SHOW_AXIS, ModuleSettings.Direction.SHOW_DEGREES)),
			HudPlacement.of(Anchor.TOP_LEFT, 4, 84), ModuleDescriptor.Requirement.WORLD, null),
		new ModuleDescriptor(ModuleId.ARMOR, "Armor", "Your armor and how much durability it has left",
			List.of("armour", "durability", "gear", "equipment", "items"), IconId.SHIELD, Category.COMBAT,
			List.of(
				SettingSection.of("Display", ModuleSettings.Armor.LAYOUT, ModuleSettings.Armor.DURABILITY, ModuleSettings.Armor.SHOW_HANDS,
					ModuleSettings.Armor.HIDE_EMPTY),
				SettingSection.advanced("Advanced", ModuleSettings.Armor.LOW_WARNING, ModuleSettings.Armor.LOW_THRESHOLD)),
			HudPlacement.of(Anchor.BOTTOM_RIGHT, 4, 4), ModuleDescriptor.Requirement.WORLD, null),
		new ModuleDescriptor(ModuleId.EFFECTS, "Effects", "Your active effects and the time they have left",
			List.of("potions", "status", "buffs", "timer", "duration"), IconId.FLASK, Category.COMBAT,
			List.of(
				SettingSection.of("Display", ModuleSettings.Effects.LAYOUT, ModuleSettings.Effects.SHOW_ICONS, ModuleSettings.Effects.SHOW_NAMES,
					ModuleSettings.Effects.SHOW_LEVEL, ModuleSettings.Effects.SHOW_DURATION),
				SettingSection.advanced("Advanced", ModuleSettings.Effects.DURATION_FORMAT, ModuleSettings.Effects.SPACING,
					ModuleSettings.Effects.SORT, ModuleSettings.Effects.BLINK_ENDING, ModuleSettings.Effects.HIDE_VANILLA_EFFECTS)),
			HudPlacement.of(Anchor.LEFT, 4, 0), ModuleDescriptor.Requirement.WORLD, null));

	private static final List<ModuleDescriptor> ALL = all(CORE);

	private ModuleRegistry() {
	}

	private static List<ModuleDescriptor> all(List<ModuleDescriptor> core) {
		List<ModuleDescriptor> all = new ArrayList<>(core);
		all.addAll(List.of(
			HudMods.MemoryUsage.DESCRIPTOR, HudMods.Playtime.DESCRIPTOR, HudMods.DayCounter.DESCRIPTOR, HudMods.ViewAngles.DESCRIPTOR,
			HudMods.HeightLimit.DESCRIPTOR, HudMods.Saturation.DESCRIPTOR, HudMods.PotionCounter.DESCRIPTOR, HudMods.ReachDisplay.DESCRIPTOR,
			HudMods.ComboCounter.DESCRIPTOR, HudMods.Cooldowns.DESCRIPTOR, HudMods.ItemCounter.DESCRIPTOR, HudMods.HorseStats.DESCRIPTOR,
			HudMods.TntCountdown.DESCRIPTOR,
			PlayerMods.ToggleSprint.DESCRIPTOR, PlayerMods.Zoom.DESCRIPTOR, PlayerMods.Freelook.DESCRIPTOR, PlayerMods.Crosshair.DESCRIPTOR,
			PlayerMods.AttackIndicator.DESCRIPTOR, PlayerMods.HitColor.DESCRIPTOR, PlayerMods.Hitbox.DESCRIPTOR, PlayerMods.TierTagger.DESCRIPTOR,
			PlayerMods.DamageTint.DESCRIPTOR, PlayerMods.KillSounds.DESCRIPTOR, PlayerMods.HurtCam.DESCRIPTOR,
			VisualMods.TimeChanger.DESCRIPTOR, VisualMods.FovChanger.DESCRIPTOR, VisualMods.FogCustomizer.DESCRIPTOR, VisualMods.MenuBlur.DESCRIPTOR,
			VisualMods.Lighting.DESCRIPTOR, VisualMods.ChunkBorders.DESCRIPTOR, VisualMods.GuiScale.DESCRIPTOR, VisualMods.MotionBlur.DESCRIPTOR,
			VisualMods.Nametags.DESCRIPTOR, VisualMods.BlockOutline.DESCRIPTOR, VisualMods.ParticleChanger.DESCRIPTOR,
			VisualMods.ColorSaturation.DESCRIPTOR, VisualMods.ItemPhysics.DESCRIPTOR, VisualMods.Skins3d.DESCRIPTOR, VisualMods.Momentum.DESCRIPTOR,
			VisualMods.Items2d.DESCRIPTOR, VisualMods.Overlay.DESCRIPTOR, VisualMods.LightOverlay.DESCRIPTOR,
			UtilityMods.Chat.DESCRIPTOR, UtilityMods.TabEditor.DESCRIPTOR, UtilityMods.Scoreboard.DESCRIPTOR, UtilityMods.Titles.DESCRIPTOR,
			UtilityMods.BossBar.DESCRIPTOR, UtilityMods.NickHider.DESCRIPTOR, UtilityMods.Screenshots.DESCRIPTOR, UtilityMods.Waypoints.DESCRIPTOR,
			UtilityMods.ShulkerPreview.DESCRIPTOR, UtilityMods.ScrollableTooltips.DESCRIPTOR, UtilityMods.PackOrganizer.DESCRIPTOR,
			UtilityMods.Inventory.DESCRIPTOR, UtilityMods.BetterSounds.DESCRIPTOR, UtilityMods.FlintMusic.DESCRIPTOR));
		for (int i = 0; i < all.size(); i++) {
			for (int j = 0; j < i; j++) {
				if (all.get(i).id().equals(all.get(j).id())) {
					throw new IllegalStateException("duplicate module id " + all.get(i).id());
				}
			}
		}
		return List.copyOf(all);
	}

	/** The eight original HUD modules, in order. */
	public static List<ModuleDescriptor> core() {
		return CORE;
	}

	/** Every module, in registry order. */
	public static List<ModuleDescriptor> all() {
		return ALL;
	}

	public static int size() {
		return ALL.size();
	}

	/** The module with this id, or null. */
	public static @Nullable ModuleDescriptor find(String id) {
		int index = indexOf(id);
		return index < 0 ? null : ALL.get(index);
	}

	/** The module with this id; throws when there is none. */
	public static ModuleDescriptor get(String id) {
		ModuleDescriptor descriptor = find(id);
		if (descriptor == null) {
			throw new IllegalArgumentException("no module " + id);
		}
		return descriptor;
	}

	/** The module's position in {@link #all()}, or -1. */
	public static int indexOf(String id) {
		for (int i = 0; i < ALL.size(); i++) {
			if (ALL.get(i).id().equals(id)) {
				return i;
			}
		}
		return -1;
	}
}
