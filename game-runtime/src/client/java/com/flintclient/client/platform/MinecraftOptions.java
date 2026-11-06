package com.flintclient.client.platform;

import com.flintclient.client.compat.McCompat;
import com.flintclient.client.control.model.IntSetting;
import com.flintclient.client.control.model.OptionSetting;
import com.flintclient.client.control.model.SettingDescriptor;
import com.flintclient.client.control.model.VideoOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft's own video options for the Right Shift menu's Graphics tab ({@link VideoOptions}): read from and written to
 * Minecraft's {@link Options} itself, through each option's own setter, so Minecraft applies a change exactly as its Video
 * Settings do (the world redrawn, the window resized, the preset turned Custom). What needs a texture reload (mipmaps, and the
 * texture filtering a preset sets) is reloaded when the menu closes, as Minecraft's Video Settings screen does on close, and
 * {@code options.txt} is written then too. An option a Minecraft version lacks reads as unavailable and its row stays hidden.
 * Platform class (see {@link Clock}).
 */
public final class MinecraftOptions {
	/** Mipmaps and texture filtering as the menu opened (to reload textures on close only if they changed). */
	private static int texturesAtOpen = Integer.MIN_VALUE;
	private static boolean changed;

	private MinecraftOptions() {
	}

	private static @Nullable Options options() {
		return Minecraft.getInstance().options;
	}

	/** Minecraft's option behind {@code option}, or null when this version has none. */
	private static @Nullable OptionInstance<?> instance(Options o, SettingDescriptor<?> option) {
		return switch (option.id()) {
			case "fullscreen" -> o.fullscreen();
			case "enableVsync" -> o.enableVsync();
			case "maxFps" -> o.framerateLimit();
			case "guiScale" -> o.guiScale();
			case "fov" -> o.fov();
			case "gamma" -> o.gamma();
			case "bobView" -> o.bobView();
			case "graphics" -> McCompat.graphicsOption();
			case "renderDistance" -> o.renderDistance();
			case "simulationDistance" -> o.simulationDistance();
			case "entityDistanceScaling" -> o.entityDistanceScaling();
			case "ao" -> o.ambientOcclusion();
			case "renderClouds" -> o.cloudStatus();
			case "particles" -> o.particles();
			case "entityShadows" -> o.entityShadows();
			case "biomeBlendRadius" -> o.biomeBlendRadius();
			case "mipmapLevels" -> o.mipmapLevels();
			case "prioritizeChunkUpdates" -> o.prioritizeChunkUpdates();
			default -> null;
		};
	}

	/** Whether this Minecraft has {@code option}. */
	public static boolean has(SettingDescriptor<?> option) {
		Options o = options();
		return o != null && instance(o, option) != null;
	}

	/** {@code option}'s value now, in the menu's units (its default when Minecraft has no such option). */
	public static Object get(SettingDescriptor<?> option) {
		Options o = options();
		OptionInstance<?> instance = o == null ? null : instance(o, option);
		if (instance == null) {
			return option.defaultValue();
		}
		Object value = instance.get();
		if (option == VideoOptions.BRIGHTNESS) {
			return (int) Math.round(((Number) value).doubleValue() * 100);
		}
		if (option == VideoOptions.ENTITY_DISTANCE) {
			return (int) Math.round(((Number) value).doubleValue() * 4) * 25;
		}
		if (value instanceof Enum<?> e) {
			// Graphics: Custom (Minecraft's own Video Settings changed one of its options) is none of the menu's three.
			int at = e.ordinal();
			return option == VideoOptions.GRAPHICS && at > 2 ? -1 : at;
		}
		return value;
	}

	/**
	 * Sets {@code option} to {@code value} (menu units) through Minecraft's own setter, which applies it; whether it changed.
	 * Fabulous is refused on a graphics driver Minecraft's warning list names (its own Video Settings ask first there).
	 */
	@SuppressWarnings("unchecked")
	public static boolean set(SettingDescriptor<?> option, Object value) {
		Options o = options();
		OptionInstance<Object> instance = o == null ? null : (OptionInstance<Object>) instance(o, option);
		if (instance == null || value.equals(get(option))) {
			return false;
		}
		Object now = instance.get();
		Object next;
		if (option == VideoOptions.BRIGHTNESS) {
			next = Math.clamp((Integer) value / 100.0, 0.0, 1.0);
		} else if (option == VideoOptions.ENTITY_DISTANCE) {
			next = Math.clamp((Integer) value / 100.0, 0.5, 5.0);
		} else if (now instanceof Enum<?> e) {
			Object[] constants = e.getDeclaringClass().getEnumConstants();
			int index = (Integer) value;
			if (index < 0 || index >= constants.length) {
				return false;
			}
			if (option == VideoOptions.GRAPHICS && index == 2 && Minecraft.getInstance().getGpuWarnlistManager().hasWarnings()) {
				return false;
			}
			next = constants[index];
		} else if (now instanceof Integer && option instanceof IntSetting number) {
			// Minecraft resets an option to its default on a value outside its range: kept inside it.
			next = Math.clamp((Integer) value, number.min(), max(number));
		} else {
			next = value;
		}
		instance.set(next);
		changed = true;
		return true;
	}

	/**
	 * The highest value Minecraft takes for {@code option} now: the render and simulation distances stop at 16 chunks with
	 * less than a gigabyte for Java (as Minecraft's own sliders), the GUI scale at the largest this window fits.
	 */
	public static int max(IntSetting option) {
		if (option == VideoOptions.RENDER_DISTANCE || option == VideoOptions.SIMULATION_DISTANCE) {
			return Runtime.getRuntime().maxMemory() >= 1_000_000_000L ? option.max() : Math.min(16, option.max());
		}
		if (option == VideoOptions.GUI_SCALE) {
			Minecraft mc = Minecraft.getInstance();
			return Math.clamp(mc.getWindow().calculateScale(0, mc.isEnforceUnicode()), 1, option.max());
		}
		return option.max();
	}

	/** How many of {@code option}'s choices this Minecraft has, from the first (every one here). */
	public static int choices(OptionSetting option) {
		return option.labels().size();
	}

	/** Why {@code option} can't be used now, or null. */
	public static @Nullable String unavailable(SettingDescriptor<?> option) {
		return has(option) ? null : "Not in this Minecraft version";
	}

	/** The Right Shift menu opened: notes the texture options, to reload textures on close only if they change. */
	public static void menuOpened() {
		Options o = options();
		texturesAtOpen = o == null ? Integer.MIN_VALUE : textures(o);
		changed = false;
	}

	/** The Right Shift menu closed: textures reloaded if mipmaps or filtering changed, then {@code options.txt} written. */
	public static void menuClosed() {
		Options o = options();
		if (o == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (texturesAtOpen != Integer.MIN_VALUE && textures(o) != texturesAtOpen) {
			mc.updateMaxMipLevel(o.mipmapLevels().get());
			mc.delayTextureReload();
		}
		texturesAtOpen = Integer.MIN_VALUE;
		if (changed) {
			changed = false;
			o.save();
		}
	}

	private static int textures(Options o) {
		return o.mipmapLevels().get() * 1_000_003 + McCompat.textureFiltering();
	}
}
