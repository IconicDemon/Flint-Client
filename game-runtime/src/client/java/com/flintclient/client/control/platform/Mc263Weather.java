package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.mods.VisualMods;

/**
 * Minecraft 26.3's side of Weather Changer, called from {@code LevelWeatherMixin} (the client level's rain and thunder
 * levels, which the sky, fog, rain, snow and their sounds all read) and {@code WeatherEffectRendererMixin} (the color of
 * rain and snow). Fault-isolated; allocation-free.
 */
public final class Mc263Weather {
	private static final int WEATHER = Mods.index(VisualMods.WeatherChanger.ID);
	private static final HookGuard GUARD = new HookGuard("weather",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));

	private Mc263Weather() {
	}

	/** Weather Changer's mode while it is on: 0 the world's own, 1 clear, 2 rain, 3 thunder; -1 when off. */
	private static int weather() {
		if (!GUARD.on() || !Mods.on(WEATHER)) {
			return -1;
		}
		return Mods.state(WEATHER).option(VisualMods.WeatherChanger.MODE);
	}

	/** The client level's rain level (0..1) as you see it. */
	public static float rain(float vanilla) {
		try {
			return switch (weather()) {
				case 1 -> 0;
				case 2, 3 -> 1;
				default -> vanilla;
			};
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return vanilla;
		}
	}

	/** The client level's thunder level (0..1) as you see it. */
	public static float thunder(float vanilla) {
		try {
			return switch (weather()) {
				case 1, 2 -> 0;
				case 3 -> 1;
				default -> vanilla;
			};
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return vanilla;
		}
	}

	/** A rain or snow column's color (ARGB, white with the column's alpha): tinted to the chosen color. */
	public static int precipitationColor(int argb) {
		try {
			if (weather() < 0) {
				return argb;
			}
			int rgb = Mods.state(WEATHER).rgb(VisualMods.WeatherChanger.COLOR);
			return rgb == 0xFFFFFF ? argb : argb & 0xFF000000 | rgb & 0xFFFFFF;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return argb;
		}
	}
}
