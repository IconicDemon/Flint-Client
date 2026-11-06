package com.flintclient.client.control.model;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft's own video options as the Right Shift menu's Graphics tab shows them. Flint keeps no copy: each row reads and
 * writes Minecraft's option itself (the platform's {@code MinecraftOptions}), so Minecraft's Video Settings and
 * {@code options.txt} always agree with the menu. Values are in the menu's units (percentages, whole steps, option
 * indexes); the defaults are Minecraft's. Pure data, free of Minecraft types.
 */
public final class VideoOptions {
	// ---------- display ----------
	public static final BoolSetting FULLSCREEN = new BoolSetting("fullscreen", "Fullscreen", "Minecraft's Fullscreen (F11)", false);
	public static final BoolSetting VSYNC = new BoolSetting("enableVsync", "VSync",
		"Caps the frame rate to your monitor's refresh rate, without tearing", true);
	public static final IntSetting MAX_FPS = new IntSetting("maxFps", "Max framerate", "Unlimited: as many frames as your PC draws", 120, 10,
		260, 10, " FPS").named(260, "Unlimited");
	public static final IntSetting GUI_SCALE = new IntSetting("guiScale", "GUI scale", "The size of Minecraft's HUD and menus (Auto: the largest "
		+ "that fits)", 0, 0, 8, 1, "").named(0, "Auto");
	public static final IntSetting FOV = new IntSetting("fov", "Field of view", null, 70, 30, 110, 1, "").named(70, "Normal").named(110,
		"Quake Pro");
	public static final IntSetting BRIGHTNESS = new IntSetting("gamma", "Brightness", null, 50, 0, 100, 1, "%").named(0, "Moody").named(100,
		"Bright");
	public static final BoolSetting VIEW_BOBBING = new BoolSetting("bobView", "View bobbing", "The camera sways as you walk", true);

	// ---------- quality ----------
	/** Fast, Fancy or Fabulous; -1 when Minecraft's own Video Settings made it Custom (1.21.11 and later). */
	public static final OptionSetting GRAPHICS = new OptionSetting("graphics", "Graphics",
		"Minecraft's graphics preset; on newer versions it also sets the quality options below, as Minecraft's own button does. Fabulous "
			+ "needs a graphics driver Minecraft doesn't warn about", 1, "fast", "Fast",
		"fancy", "Fancy", "fabulous", "Fabulous");
	public static final IntSetting RENDER_DISTANCE = new IntSetting("renderDistance", "Render distance", "How far the world is drawn", 12, 2, 32,
		1, " chunks");
	public static final IntSetting SIMULATION_DISTANCE = new IntSetting("simulationDistance", "Simulation distance",
		"How far mobs move and crops grow (in your own worlds; a server sets its own)", 12, 5, 32, 1, " chunks");
	public static final IntSetting ENTITY_DISTANCE = new IntSetting("entityDistanceScaling", "Entity distance", "How far away mobs and items are "
		+ "drawn", 100, 50, 500, 25, "%");
	public static final BoolSetting SMOOTH_LIGHTING = new BoolSetting("ao", "Smooth lighting", "Soft shadows in corners and under blocks", true);
	public static final OptionSetting CLOUDS = new OptionSetting("renderClouds", "Clouds", null, 2, "off", "Off", "fast", "Fast", "fancy",
		"Fancy");
	public static final OptionSetting PARTICLES = new OptionSetting("particles", "Particles", null, 0, "all", "All", "decreased", "Decreased",
		"minimal", "Minimal");
	public static final BoolSetting ENTITY_SHADOWS = new BoolSetting("entityShadows", "Entity shadows", "The round shadow under mobs and items",
		true);
	public static final IntSetting BIOME_BLEND = new IntSetting("biomeBlendRadius", "Biome blend", "How softly grass, water and leaf colours "
		+ "blend between biomes", 2, 0, 7, 1, "").named(0, "Off").named(1, "3×3").named(2, "5×5").named(3, "7×7").named(4, "9×9")
		.named(5, "11×11").named(6, "13×13").named(7, "15×15");
	public static final IntSetting MIPMAPS = new IntSetting("mipmapLevels", "Mipmap levels", "Smooths far-away textures; applied when the menu "
		+ "closes", 4, 0, 4, 1, "").named(0, "Off");

	// ---------- performance ----------
	public static final OptionSetting CHUNK_BUILDER = new OptionSetting("prioritizeChunkUpdates", "Chunk builder",
		"Threaded: smoothest. Semi Blocking: blocks you change update at once. Blocking (Minecraft's Fully Blocking): every nearby change at "
			+ "once, which can stutter", 0, "none", "Threaded", "player_affected", "Semi Blocking", "nearby", "Blocking");

	/** Every option, in the order the Graphics tab lists them. */
	public static final List<SettingDescriptor<?>> ALL = List.of(FULLSCREEN, VSYNC, MAX_FPS, GUI_SCALE, FOV, BRIGHTNESS, VIEW_BOBBING, GRAPHICS,
		RENDER_DISTANCE, SIMULATION_DISTANCE, ENTITY_DISTANCE, SMOOTH_LIGHTING, CLOUDS, PARTICLES, ENTITY_SHADOWS, BIOME_BLEND, MIPMAPS,
		CHUNK_BUILDER);

	/**
	 * Options a slider applies when it is let go, not at every step of a drag: each change makes Minecraft rebuild the world's
	 * chunks or its GUI, so dragging through ten values would do that ten times.
	 */
	private static final Set<SettingDescriptor<?>> ON_RELEASE = Set.of(GUI_SCALE, RENDER_DISTANCE, SIMULATION_DISTANCE, ENTITY_DISTANCE,
		BIOME_BLEND, MIPMAPS);

	private VideoOptions() {
	}

	/** The option with {@code id}, or null. */
	public static @Nullable SettingDescriptor<?> byId(String id) {
		for (SettingDescriptor<?> option : ALL) {
			if (option.id().equals(id)) {
				return option;
			}
		}
		return null;
	}

	/** Whether a slider for {@code option} applies its value when let go (see {@link #ON_RELEASE}). */
	public static boolean appliesOnRelease(SettingDescriptor<?> option) {
		return ON_RELEASE.contains(option);
	}
}
