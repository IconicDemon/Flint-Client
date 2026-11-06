package com.flintclient.client.control.model;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import com.flintclient.client.control.model.mods.CameraMods;
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
					ModuleSettings.Fps.WIDTH, ModuleSettings.Fps.HEIGHT, ModuleSettings.Fps.STYLE.horizontalPadding,
					ModuleSettings.Fps.STYLE.verticalPadding, ModuleSettings.Fps.BORDER, ModuleSettings.Fps.TEXT_COLOR,
					ModuleSettings.Fps.TEXT_SHADOW, ModuleSettings.Fps.REVERSED),
				SettingSection.advanced("Background and border", ModuleSettings.Fps.ALIGN, ModuleSettings.Fps.BACKGROUND_COLOR,
					ModuleSettings.Fps.BACKGROUND_OPACITY, ModuleSettings.Fps.BORDER_COLOR, ModuleSettings.Fps.BORDER_THICKNESS,
					ModuleSettings.Fps.CORNER_RADIUS),
				SettingSection.advanced("Advanced", ModuleSettings.Fps.SHOW_LABEL, ModuleSettings.Fps.SHOW_FRAME_TIME, ModuleSettings.Fps.SHOW_LOW,
					ModuleSettings.Fps.INTERVAL, ModuleSettings.Fps.WINDOW, ModuleSettings.Fps.DECIMALS)),
			HudPlacement.of(Anchor.TOP_LEFT, 4, 4), ModuleDescriptor.Requirement.NONE, null).withBoxStyle(ModuleSettings.Fps.STYLE),
		new ModuleDescriptor(ModuleId.PING, "Ping", "Your latency to the server, as the server reports it",
			List.of("latency", "ms", "connection", "server", "lag"), IconId.SIGNAL, Category.PERFORMANCE,
			List.of(
				ModuleSettings.Ping.STYLE.section("Style"),
				SettingSection.of("Display", ModuleSettings.Ping.LABEL_POSITION, ModuleSettings.Ping.SHOW_BARS),
				SettingSection.advanced("Background and border", ModuleSettings.Ping.STYLE.details()),
				SettingSection.advanced("Advanced", ModuleSettings.Ping.COLOR_BY_LATENCY)),
			HudPlacement.of(Anchor.TOP_RIGHT, 4, 4), ModuleDescriptor.Requirement.SERVER_LATENCY,
			"Server-reported latency; updates when the server sends it (about every 30 s on vanilla servers)").withBoxStyle(ModuleSettings.Ping.STYLE),
		new ModuleDescriptor(ModuleId.CPS, "CPS", "Clicks per second for each mouse button",
			List.of("clicks", "click speed", "pvp", "mouse", "combat"), IconId.MOUSE, Category.COMBAT,
			List.of(ModuleSettings.Cps.STYLE.section("Style"),
				SettingSection.of("Display", ModuleSettings.Cps.BUTTONS, ModuleSettings.Cps.FORMAT, ModuleSettings.Cps.LABEL_POSITION),
				SettingSection.advanced("Background and border", ModuleSettings.Cps.STYLE.details())),
			HudPlacement.of(Anchor.TOP_LEFT, 4, 34), ModuleDescriptor.Requirement.NONE, null).withBoxStyle(ModuleSettings.Cps.STYLE),
		new ModuleDescriptor(ModuleId.KEYSTROKES, "Keystrokes", "Your movement keys and mouse buttons as you press them",
			List.of("keys", "wasd", "keyboard", "input", "pvp"), IconId.KEYS, Category.COMBAT,
			List.of(
				SettingSection.of("Display", ModuleSettings.Keystrokes.LAYOUT, ModuleSettings.Keystrokes.SHOW_CPS, ModuleSettings.Keystrokes.CPS_PLACE,
					ModuleSettings.Keystrokes.CPS_REVERSED, ModuleSettings.Keystrokes.LABELS, ModuleSettings.Keystrokes.MOUSE_LABELS,
					ModuleSettings.Keystrokes.JUMP_LABEL),
				SettingSection.of("Style", ModuleSettings.Keystrokes.KEY_SIZE, ModuleSettings.Keystrokes.SPACING,
					ModuleSettings.Keystrokes.CORNERS, ModuleSettings.Keystrokes.PRESSED_STYLE, ModuleSettings.Keystrokes.ANIMATION,
					ModuleSettings.Keystrokes.FILL_DIRECTION),
				SettingSection.advanced("Advanced", ModuleSettings.Keystrokes.PRESSED_COLOR, ModuleSettings.Keystrokes.TEXT_ACTIVE,
					ModuleSettings.Keystrokes.FADE_MS)),
			HudPlacement.of(Anchor.BOTTOM_LEFT, 4, 40), ModuleDescriptor.Requirement.NONE, null),
		new ModuleDescriptor(ModuleId.COORDINATES, "Coordinates", "Your position in the world",
			List.of("position", "xyz", "location", "coords", "nether"), IconId.PIN, Category.WORLD,
			List.of(
				SettingSection.of("Display", ModuleSettings.Coordinates.LAYOUT, ModuleSettings.Coordinates.ALIGNMENT, ModuleSettings.Coordinates.DECIMALS,
					ModuleSettings.Coordinates.LABELS, ModuleSettings.Coordinates.SHOW_DIRECTION, ModuleSettings.Coordinates.SHOW_ANGLE,
					ModuleSettings.Coordinates.FORMAT_COLOR),
				SettingSection.of("Coordinates", ModuleSettings.Coordinates.SHOW_COORDINATES, ModuleSettings.Coordinates.SHOW_XZ,
					ModuleSettings.Coordinates.SHOW_Y, ModuleSettings.Coordinates.MOVEMENT),
				SettingSection.of("Extras", ModuleSettings.Coordinates.BLOCK, ModuleSettings.Coordinates.CHUNK, ModuleSettings.Coordinates.IN_CHUNK,
					ModuleSettings.Coordinates.BIOME, ModuleSettings.Coordinates.LOADED, ModuleSettings.Coordinates.ONE_LINE,
					ModuleSettings.Coordinates.CHUNK_KEY, ModuleSettings.Coordinates.BIOME_KEY, ModuleSettings.Coordinates.LOADED_KEY),
				SettingSection.advanced("Advanced", ModuleSettings.Coordinates.OTHER_DIMENSION, ModuleSettings.Coordinates.COPY_KEY)),
			HudPlacement.of(Anchor.TOP_LEFT, 4, 49), ModuleDescriptor.Requirement.WORLD, null),
		new ModuleDescriptor(ModuleId.DIRECTION, "Direction", "The way you're facing",
			List.of("compass", "facing", "heading", "yaw", "bearing"), IconId.COMPASS, Category.WORLD,
			List.of(
				SettingSection.of("Display", ModuleSettings.Direction.TYPE, ModuleSettings.Direction.FORMAT),
				SettingSection.of("Compass", ModuleSettings.Direction.WIDTH, ModuleSettings.Direction.VISIBLE_DEGREES, ModuleSettings.Direction.MARKER_SIZE,
					ModuleSettings.Direction.MARKER_COLOR, ModuleSettings.Direction.SHADOW, ModuleSettings.Direction.WAYPOINT_DOTS),
				SettingSection.advanced("Advanced", ModuleSettings.Direction.SHOW_AXIS, ModuleSettings.Direction.SHOW_DEGREES)),
			HudPlacement.of(Anchor.TOP_LEFT, 4, 84), ModuleDescriptor.Requirement.WORLD, null),
		new ModuleDescriptor(ModuleId.ARMOR, "Armor", "Your armor in hotbar slots, with a warning when a piece is about to break",
			List.of("armour", "durability", "gear", "equipment", "uku", "armor hud"), IconId.SHIELD, Category.COMBAT,
			List.of(
				SettingSection.of("Style", ModuleSettings.Armor.STYLE, ModuleSettings.Armor.WIDGET_SHOWN, ModuleSettings.Armor.OFFHAND_SLOT,
					ModuleSettings.Armor.DURABILITY, ModuleSettings.Armor.REVERSED, ModuleSettings.Armor.ICONS),
				SettingSection.of("Items", ModuleSettings.Armor.SWORD, ModuleSettings.Armor.PICKAXE, ModuleSettings.Armor.AXE, ModuleSettings.Armor.MACE,
					ModuleSettings.Armor.TRIDENT, ModuleSettings.Armor.BOW, ModuleSettings.Armor.SHIELD, ModuleSettings.Armor.MAINHAND_DURABILITY,
					ModuleSettings.Armor.OFFHAND_DURABILITY),
				SettingSection.of("Text", ModuleSettings.Armor.TEXT_POSITION, ModuleSettings.Armor.TEXT_SCALE, ModuleSettings.Armor.TEXT_BOLD,
					ModuleSettings.Armor.TEXT_SHADOW, ModuleSettings.Armor.TEXT_COLOR_MODE, ModuleSettings.Armor.TEXT_COLOR),
				SettingSection.of("Position", ModuleSettings.Armor.ANCHOR, ModuleSettings.Armor.SIDE, ModuleSettings.Armor.LAYOUT,
					ModuleSettings.Armor.PUSH_BOSSBARS, ModuleSettings.Armor.PUSH_EFFECTS, ModuleSettings.Armor.PUSH_SUBTITLES, ModuleSettings.Armor.OFFSET_X,
					ModuleSettings.Armor.OFFSET_Y),
				SettingSection.of("Warning", ModuleSettings.Armor.BREAK_SOUND, ModuleSettings.Armor.WARNING, ModuleSettings.Armor.BOB,
					ModuleSettings.Armor.MIN_VALUE, ModuleSettings.Armor.MIN_PERCENT)),
			HudPlacement.of(Anchor.BOTTOM_RIGHT, 4, 4), ModuleDescriptor.Requirement.WORLD,
			"Drawn with Minecraft's hotbar, as uku's Armor HUD draws it: next to the hotbar, along the top, middle or bottom of the screen, "
				+ "or anywhere you drag it in the HUD editor (Right Shift).",
			ModuleDescriptor.Kind.HUD, ModuleDescriptor.Status.WORKING, null).withMinecraftLook(),
		new ModuleDescriptor(ModuleId.EFFECTS, "Effects", "Your active effects and the time they have left",
			List.of("potions", "status", "buffs", "timer", "duration"), IconId.FLASK, Category.COMBAT,
			List.of(
				SettingSection.of("Display", ModuleSettings.Effects.VIEW, ModuleSettings.Effects.SHOW_DURATION, ModuleSettings.Effects.SHOW_NAMES,
					ModuleSettings.Effects.SHOW_LEVEL, ModuleSettings.Effects.EFFECT_COLORS, ModuleSettings.Effects.TIME_COLOR),
				SettingSection.of("Tiles", ModuleSettings.Effects.TILE_DIRECTION, ModuleSettings.Effects.TILE_SPLIT, ModuleSettings.Effects.TILE_TIME,
					ModuleSettings.Effects.TILE_LEVEL,
					ModuleSettings.Effects.TILE_SHADOW,
					ModuleSettings.Effects.TILE_GAP, ModuleSettings.Effects.TILE_BACKGROUND, ModuleSettings.Effects.TILE_COLOR,
					ModuleSettings.Effects.TILE_OPACITY, ModuleSettings.Effects.TILE_BORDER, ModuleSettings.Effects.TILE_HARMFUL_BORDER),
				SettingSection.of("List", ModuleSettings.Effects.STYLE, ModuleSettings.Effects.LAYOUT, ModuleSettings.Effects.TEXT_SIDE,
					ModuleSettings.Effects.TWO_LINES, ModuleSettings.Effects.SHOW_ICONS, ModuleSettings.Effects.SPACING),
				SettingSection.of("Ending", ModuleSettings.Effects.BLINK_ENDING, ModuleSettings.Effects.BLINK_SECONDS, ModuleSettings.Effects.LOSS_SOUND,
					ModuleSettings.Effects.LOSS_VOLUME),
				SettingSection.advanced("Advanced", ModuleSettings.Effects.DURATION_FORMAT, ModuleSettings.Effects.SHORTHAND,
					ModuleSettings.Effects.SORT, ModuleSettings.Effects.HIDE_VANILLA_EFFECTS, ModuleSettings.Effects.HIDE_INVENTORY),
				SettingSection.of("Status Effect Bars", ModuleSettings.Effects.BAR_COLOR_MODE, ModuleSettings.Effects.BAR_BACKGROUND,
					ModuleSettings.Effects.BAR_BACKGROUND_OPACITY, ModuleSettings.Effects.BAR_BENEFICIAL, ModuleSettings.Effects.BAR_BENEFICIAL_OPACITY,
					ModuleSettings.Effects.BAR_HARMFUL, ModuleSettings.Effects.BAR_HARMFUL_OPACITY, ModuleSettings.Effects.BAR_NEUTRAL,
					ModuleSettings.Effects.BAR_NEUTRAL_OPACITY, ModuleSettings.Effects.BAR_MAX_REMAINING, ModuleSettings.Effects.BAR_MIN_AMBIENT_AGE),
				SettingSection.of("HUD Bars", ModuleSettings.Effects.HUD_BARS.settings()),
				SettingSection.of("Inventory Bars", ModuleSettings.Effects.INVENTORY_BARS.settings())),
			HudPlacement.of(Anchor.TOP_RIGHT, 1, 1), ModuleDescriptor.Requirement.WORLD, null));

	private static final List<ModuleDescriptor> ALL = all(CORE);

	private ModuleRegistry() {
	}

	private static List<ModuleDescriptor> all(List<ModuleDescriptor> core) {
		List<ModuleDescriptor> all = new ArrayList<>(core);
		all.addAll(List.of(
			HudMods.MemoryUsage.DESCRIPTOR, HudMods.Playtime.DESCRIPTOR, HudMods.DayCounter.DESCRIPTOR, HudMods.ViewAngles.DESCRIPTOR,
			HudMods.HeightLimit.DESCRIPTOR, HudMods.Saturation.DESCRIPTOR, HudMods.PotionCounter.DESCRIPTOR, HudMods.ReachDisplay.DESCRIPTOR,
			HudMods.ComboCounter.DESCRIPTOR, HudMods.Cooldowns.DESCRIPTOR, HudMods.ItemCounter.DESCRIPTOR, HudMods.HorseStats.DESCRIPTOR,
			HudMods.TntCountdown.DESCRIPTOR, HudMods.Clock.DESCRIPTOR, HudMods.Tps.DESCRIPTOR, HudMods.ServerAddress.DESCRIPTOR,
			HudMods.Stopwatch.DESCRIPTOR,
			PlayerMods.ToggleSprint.DESCRIPTOR, PlayerMods.Zoom.DESCRIPTOR, PlayerMods.Freelook.DESCRIPTOR, PlayerMods.Crosshair.DESCRIPTOR,
			PlayerMods.AttackIndicator.DESCRIPTOR, PlayerMods.HitColor.DESCRIPTOR, PlayerMods.Hitbox.DESCRIPTOR, PlayerMods.TierTagger.DESCRIPTOR,
			PlayerMods.DamageTint.DESCRIPTOR, PlayerMods.KillSounds.DESCRIPTOR, PlayerMods.HurtCam.DESCRIPTOR,
			VisualMods.TimeChanger.DESCRIPTOR, VisualMods.FovChanger.DESCRIPTOR, VisualMods.FogCustomizer.DESCRIPTOR, VisualMods.MenuBlur.DESCRIPTOR,
			VisualMods.Lighting.DESCRIPTOR, VisualMods.ChunkBorders.DESCRIPTOR, VisualMods.GuiScale.DESCRIPTOR, VisualMods.MotionBlur.DESCRIPTOR,
			VisualMods.Nametags.DESCRIPTOR, VisualMods.BlockOutline.DESCRIPTOR, VisualMods.ParticleChanger.DESCRIPTOR,
			VisualMods.ColorSaturation.DESCRIPTOR, VisualMods.ItemPhysics.DESCRIPTOR, VisualMods.Skins3d.DESCRIPTOR, VisualMods.Momentum.DESCRIPTOR,
			VisualMods.Items2d.DESCRIPTOR, VisualMods.Overlay.DESCRIPTOR, VisualMods.LightOverlay.DESCRIPTOR, VisualMods.AutohideHud.DESCRIPTOR,
			VisualMods.ViewModel.DESCRIPTOR, VisualMods.WeatherChanger.DESCRIPTOR, VisualMods.Hotbar.DESCRIPTOR,
			CameraMods.Snaplook.DESCRIPTOR, CameraMods.AutoPerspective.DESCRIPTOR, CameraMods.Camera.DESCRIPTOR,
			UtilityMods.Chat.DESCRIPTOR, UtilityMods.TabEditor.DESCRIPTOR, UtilityMods.Scoreboard.DESCRIPTOR, UtilityMods.Titles.DESCRIPTOR,
			UtilityMods.BossBar.DESCRIPTOR, UtilityMods.NickHider.DESCRIPTOR, UtilityMods.Screenshots.DESCRIPTOR, UtilityMods.Waypoints.DESCRIPTOR,
			UtilityMods.ShulkerPreview.DESCRIPTOR, UtilityMods.ScrollableTooltips.DESCRIPTOR, UtilityMods.PackOrganizer.DESCRIPTOR,
			UtilityMods.Inventory.DESCRIPTOR, UtilityMods.BetterSounds.DESCRIPTOR,
			UtilityMods.DeathInfo.DESCRIPTOR, UtilityMods.Reconnect.DESCRIPTOR, UtilityMods.ToastControl.DESCRIPTOR));
		all.addAll(com.flintclient.client.control.model.mods.MoreMods.all());
		all.addAll(com.flintclient.client.control.model.mods.MoreHudMods.all());
		all.addAll(com.flintclient.client.control.model.mods.LookMods.all());
		all.addAll(com.flintclient.client.control.model.mods.ToolMods.all());
		all.add(VisualMods.WavyCapes.DESCRIPTOR);
		all.add(com.flintclient.client.control.model.mods.MapMods.Minimap.DESCRIPTOR);
		all.add(com.flintclient.client.control.model.mods.MapMods.WorldMap.DESCRIPTOR);
		all.addAll(com.flintclient.client.control.model.mods.ServerMods.all());
		all.addAll(com.flintclient.client.control.model.mods.SoundMods.all());
		all.add(PlayerMods.HealthIndicators.DESCRIPTOR);
		all.add(VisualMods.ModelGapFix.DESCRIPTOR);
		all.add(VisualMods.FallingLeaves.DESCRIPTOR);
		all.add(com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.DESCRIPTOR);
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
