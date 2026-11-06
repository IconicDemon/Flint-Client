package com.flintclient.client.platform;

import com.flintclient.client.control.model.IntSetting;
import com.flintclient.client.control.model.OptionSetting;
import com.flintclient.client.control.model.SettingDescriptor;
import com.flintclient.client.control.model.VideoOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.options.GameOptions;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft's own video options for the Right Shift menu's Graphics tab ({@link VideoOptions}), on Minecraft 1.8.9: read from
 * and written to 1.8.9's {@link GameOptions}, through its own setters where it has them, so a change applies as its Video
 * Settings apply it (the world redrawn for graphics and lighting, VSync and fullscreen switched at once). Mipmap levels are
 * applied when the menu closes (a texture reload), and {@code options.txt} is written then. The options 1.8.9 doesn't have
 * (Fabulous, simulation and entity distance, biome blend, the chunk builder) read as unavailable and their rows stay hidden.
 * Platform class (see {@link Clock}).
 */
public final class MinecraftOptions {
	private static int mipmapsAtOpen = Integer.MIN_VALUE;
	private static boolean changed;

	private MinecraftOptions() {
	}

	private static @Nullable GameOptions options() {
		Minecraft mc = Minecraft.getInstance();
		return mc == null ? null : mc.options;
	}

	/** Whether 1.8.9 has {@code option}. */
	public static boolean has(SettingDescriptor<?> option) {
		return options() != null && switch (option.id()) {
			case "fullscreen", "enableVsync", "maxFps", "guiScale", "fov", "gamma", "bobView", "graphics", "renderDistance", "ao",
				"renderClouds", "particles", "entityShadows", "mipmapLevels" -> true;
			default -> false;
		};
	}

	/** {@code option}'s value now, in the menu's units (its default when 1.8.9 has no such option). */
	public static Object get(SettingDescriptor<?> option) {
		GameOptions o = options();
		if (o == null || !has(option)) {
			return option.defaultValue();
		}
		return switch (option.id()) {
			case "fullscreen" -> o.fullscreen;
			case "enableVsync" -> o.vsync;
			case "maxFps" -> o.fpsLimit;
			case "guiScale" -> o.guiScale;
			case "fov" -> Math.round(o.fov);
			case "gamma" -> Math.round(o.gamma * 100);
			case "bobView" -> o.viewBobbing;
			case "graphics" -> o.fancyGraphics ? 1 : 0;
			case "renderDistance" -> o.viewDistance;
			case "ao" -> o.ambientOcclusion > 0;
			case "renderClouds" -> Math.clamp(o.cloudRenderMode, 0, 2);
			case "particles" -> Math.clamp(o.particles, 0, 2);
			case "entityShadows" -> o.entityShadows;
			case "mipmapLevels" -> o.mipmapLevels;
			default -> option.defaultValue();
		};
	}

	/** Sets {@code option} to {@code value} (menu units) as 1.8.9's own Video Settings would; whether it changed. */
	public static boolean set(SettingDescriptor<?> option, Object value) {
		GameOptions o = options();
		if (o == null || !has(option) || value.equals(get(option))) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		switch (option.id()) {
			case "fullscreen" -> mc.toggleFullscreen();
			case "enableVsync" -> o.set(GameOptions.Option.ENABLE_VSYNC, 1);
			case "maxFps" -> o.set(GameOptions.Option.FRAMERATE_LIMIT, (float) (int) (Integer) value);
			case "guiScale" -> {
				o.guiScale = Math.clamp((Integer) value, 0, max((IntSetting) option));
				if (mc.screen != null) {
					// The window's GUI size changes with it: the open screen is laid out again, as a resize would.
					net.minecraft.client.render.Window window = new net.minecraft.client.render.Window(mc);
					mc.screen.init(mc, window.getWidth(), window.getHeight());
				}
			}
			case "fov" -> o.fov = Math.clamp((Integer) value, 30, 110);
			case "gamma" -> o.gamma = Math.clamp((Integer) value / 100.0F, 0, 1);
			case "bobView" -> o.viewBobbing = (Boolean) value;
			case "graphics" -> {
				int index = (Integer) value;
				if (index != 0 && index != 1) {
					return false;
				}
				o.set(GameOptions.Option.GRAPHICS, 1);
			}
			case "renderDistance" -> o.set(GameOptions.Option.RENDER_DISTANCE, (float) Math.clamp((Integer) value, 2, max((IntSetting) option)));
			case "ao" -> {
				o.ambientOcclusion = (Boolean) value ? 2 : 0;
				if (mc.worldRenderer != null) {
					mc.worldRenderer.reload();
				}
			}
			case "renderClouds" -> o.cloudRenderMode = Math.clamp((Integer) value, 0, 2);
			case "particles" -> o.particles = Math.clamp((Integer) value, 0, 2);
			case "entityShadows" -> o.entityShadows = (Boolean) value;
			case "mipmapLevels" -> o.mipmapLevels = Math.clamp((Integer) value, 0, 4);
			default -> {
				return false;
			}
		}
		changed = true;
		return true;
	}

	/**
	 * The highest value 1.8.9 takes for {@code option} now: 16 chunks of render distance, 32 on a 64-bit Java with a gigabyte
	 * (as its own slider), GUI scale 3 (Large).
	 */
	public static int max(IntSetting option) {
		if (option == VideoOptions.RENDER_DISTANCE) {
			return Runtime.getRuntime().maxMemory() >= 1_000_000_000L ? Math.min(32, option.max()) : 16;
		}
		if (option == VideoOptions.GUI_SCALE) {
			return Math.min(3, option.max());
		}
		return option.max();
	}

	/** How many of {@code option}'s choices 1.8.9 has, from the first: no Fabulous graphics. */
	public static int choices(OptionSetting option) {
		return option == VideoOptions.GRAPHICS ? 2 : option.labels().size();
	}

	/** Why {@code option} can't be used now, or null. */
	public static @Nullable String unavailable(SettingDescriptor<?> option) {
		return has(option) ? null : "Not in Minecraft 1.8.9";
	}

	/** The Right Shift menu opened: notes the mipmap levels, to reload textures on close only if they change. */
	public static void menuOpened() {
		GameOptions o = options();
		mipmapsAtOpen = o == null ? Integer.MIN_VALUE : o.mipmapLevels;
		changed = false;
	}

	/** The Right Shift menu closed: textures reloaded if the mipmap levels changed, then {@code options.txt} written. */
	public static void menuClosed() {
		GameOptions o = options();
		if (o == null) {
			return;
		}
		if (mipmapsAtOpen != Integer.MIN_VALUE && o.mipmapLevels != mipmapsAtOpen) {
			int wanted = o.mipmapLevels;
			// Through 1.8.9's own setter, which reloads the block atlas at the new level.
			o.mipmapLevels = mipmapsAtOpen;
			o.set(GameOptions.Option.MIPMAP_LEVELS, (float) wanted);
		}
		mipmapsAtOpen = Integer.MIN_VALUE;
		if (changed) {
			changed = false;
			o.save();
		}
	}
}
