package com.flintclient.client.control.platform.ambient;

import static com.flintclient.client.control.model.mods.AmbientSoundsSettings.*;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.SoundMods;
import com.flintclient.client.control.platform.HookGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Ambient Sounds: AmbientSounds' engine inside Flint. Each tick, while the module is on and you're in a world (and
 * Minecraft's Ambient/Environment volume isn't 0): every two seconds the slow look around (ground height, biomes, the air
 * pocket scan on its own thread), every four ticks the quick one (time, weather, height, water, entities) and the regions'
 * rules, and every tick the sounds' fades. The engine loads when the module first runs and again whenever Minecraft
 * reloads its sounds (a resource pack change). The settings apply as they change. Game thread; fault-isolated.
 *
 * <p>Adapted from AmbientSounds' {@code AmbientTickHandler} (LGPL-3.0, CreativeMD and AriaFreeze; see
 * META-INF/licenses/LGPL-3.0-AmbientSounds.txt).
 */
public final class AmbientSounds {
	static final Logger LOGGER = FlintClient.LOGGER;
	private static final int MODULE = Mods.index(SoundMods.AmbientSounds.ID);
	private static final HookGuard GUARD = new HookGuard("ambient sounds",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final String ENGINE = "basic";

	private static @Nullable AmbientEngine engine;
	private static final AmbientSoundEngine SOUNDS = new AmbientSoundEngine();
	private static @Nullable AmbientEnvironment environment;
	private static volatile boolean reload = true;
	private static boolean loadFailed;
	private static int timer;
	private static @Nullable ModuleState appliedState;
	private static int appliedRevision = -1;
	private static @Nullable AmbientEngine appliedEngine;
	private static @Nullable ClientLevel seenLevel;
	// Read by the sounds as they start (game thread).
	private static double volume = 1;
	private static boolean master;
	private static boolean offset = true;
	private static int scanSteps = 100;

	private AmbientSounds() {
	}

	static double masterVolume() {
		return volume;
	}

	static boolean useMaster() {
		return master;
	}

	static boolean playWithOffset() {
		return offset;
	}

	/** Minecraft reloaded its sounds (a resource change): the engine loads again before it next plays. */
	public static void soundsReloaded() {
		reload = true;
	}

	/** Each client tick. */
	public static void tick() {
		if (!GUARD.on()) {
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			if (!Mods.on(MODULE)) {
				stop();
				return;
			}
			if (reload) {
				reload = false;
				stop();
				engine = null;
				loadFailed = false;
			}
			if (engine == null && !loadFailed) {
				try {
					engine = AmbientEngine.load(mc.getResourceManager(), mc.getSoundManager(), ENGINE);
					engine.soundEngine = SOUNDS;
				} catch (Exception e) {
					loadFailed = true;
					LOGGER.error("Ambient Sounds: no sound engine could be loaded, no sounds will be played", e);
				}
			}
			AmbientEngine e = engine;
			if (e == null) {
				return;
			}
			apply(e, Mods.state(MODULE));
			ClientLevel level = mc.level;
			LocalPlayer player = mc.player;
			if (level != seenLevel) {
				seenLevel = level;
				if (level != null) {
					e.onClientLoad();
				}
				if (environment != null) {
					environment.terrain.stopScan();
				}
			}
			if (level == null || player == null || mc.options.getSoundSourceVolume(SoundSource.AMBIENT) <= 0) {
				if (!e.activeRegions.isEmpty()) {
					e.stopEngine();
				}
				return;
			}
			if (mc.isPaused()) {
				return;
			}
			AmbientEnvironment env = environment;
			if (env == null) {
				env = new AmbientEnvironment();
				environment = env;
			}
			AmbientDimension dimension = e.getDimension(level);
			if (env.dimension != dimension) {
				e.changeDimension(env, dimension);
				env.dimension = dimension;
			}
			if (timer % e.environmentTickTime == 0) {
				env.analyzeSlow(e, dimension, player, level, scanSteps);
			}
			if (timer % e.soundTickTime == 0) {
				env.analyzeFast(e, dimension, player, level, 1);
				env.dimension.manipulateEnvironment(env);
				e.tick(env);
			}
			e.fastTick(env);
			timer++;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			try {
				stop();
			} catch (RuntimeException | LinkageError ignored) {
				// already failing
			}
		}
	}

	private static void stop() {
		AmbientEngine e = engine;
		if (e != null) {
			e.stopEngine();
		}
		SOUNDS.stopAll();
		if (environment != null) {
			environment.terrain.stopScan();
			environment = null;
		}
	}

	/** The settings, onto the engine's dimensions, regions, sounds and categories (when they or the engine change). */
	private static void apply(AmbientEngine e, ModuleState state) {
		if (state == appliedState && state.revision() == appliedRevision && e == appliedEngine) {
			return;
		}
		appliedState = state;
		appliedRevision = state.revision();
		appliedEngine = e;
		volume = state.decimal(VOLUME);
		master = state.bool(MASTER);
		offset = state.bool(OFFSET);
		scanSteps = state.integer(SCAN_STEPS);
		e.fadeVolumeSetting = state.decimal(FADE_VOLUME);
		e.fadePitchSetting = state.decimal(FADE_PITCH);
		e.silentDimensions.clear();
		for (String part : state.text(SILENT_DIMENSIONS).split(",")) {
			if (!part.isBlank()) {
				e.silentDimensions.add(part.strip());
			}
		}
		for (int i = 0; i < DIMENSIONS.length; i++) {
			AmbientDimension dimension = e.dimensions.get(DIMENSIONS[i]);
			if (dimension != null) {
				dimension.volumeSetting = state.decimal(DIMENSION_VOLUME[i]);
			}
		}
		for (int i = 0; i < CATEGORIES.length; i++) {
			AmbientSoundCategory category = e.soundCategories.get(CATEGORIES[i][0]);
			if (category != null) {
				category.volumeSetting = state.decimal(CATEGORY_VOLUME[i]);
			}
		}
		for (int i = 0; i < REGIONS.length; i++) {
			AmbientRegion region = e.allRegions.get(REGIONS[i][0]);
			if (region == null) {
				continue;
			}
			region.volumeSetting = state.decimal(REGION_VOLUME[i]);
			if (region.loadedSounds != null) {
				for (int j = 1; j < REGIONS[i].length; j++) {
					AmbientSound sound = region.loadedSounds.get(REGIONS[i][j]);
					if (sound != null) {
						sound.volumeSetting = state.decimal(SOUND_VOLUME[i][j - 1]);
					}
				}
			}
		}
	}

	// ---------- the debug overlay (the mod's /ambient-debug) ----------

	/** Over the HUD, while Debug overlay is on: what the engine hears around you and what it plays. */
	public static void renderDebug(GuiGraphicsExtractor graphics) {
		AmbientEngine e = engine;
		AmbientEnvironment env = environment;
		Minecraft mc = Minecraft.getInstance();
		if (!GUARD.on() || e == null || env == null || env.dimension == null || mc.level == null || mc.isPaused() || !Mods.on(MODULE)
			|| !Mods.state(MODULE).bool(DEBUG)) {
			return;
		}
		try {
			List<String> lines = debugLines(e, env, mc);
			Font font = mc.font;
			int y = 2;
			for (String line : lines) {
				// The mod's look: yellow on a grey band, region names in green.
				graphics.fill(1, y - 1, 3 + font.width(line), y + 9, 0x90505050);
				int x = 2;
				int green = line.startsWith(REGION) ? line.indexOf(',') : -1;
				if (green > 0) {
					String name = line.substring(REGION.length(), green);
					graphics.text(font, REGION, x, y, YELLOW, false);
					x += font.width(REGION);
					graphics.text(font, name, x, y, GREEN, false);
					x += font.width(name);
					graphics.text(font, line.substring(green), x, y, YELLOW, false);
				} else {
					graphics.text(font, line, x, y, YELLOW, false);
				}
				y += 10;
			}
		} catch (RuntimeException | LinkageError ex) {
			GUARD.fail(ex);
		}
	}

	private static final String REGION = "region:";
	private static final int YELLOW = 0xFFFFFF55;
	private static final int GREEN = 0xFF00AA00;
	private static final ThreadLocal<java.text.DecimalFormat> NUMBER = ThreadLocal.withInitial(
		() -> new java.text.DecimalFormat("#.##", java.text.DecimalFormatSymbols.getInstance(Locale.ROOT)));

	/** A number as the mod's debug text writes it (up to two decimals). */
	static String f(double value) {
		return NUMBER.get().format(value);
	}

	/** The mod's debug text, line by line ("key:value" pairs, comma-separated). */
	static List<String> debugLines(AmbientEngine e, AmbientEnvironment env, Minecraft mc) {
		List<String> lines = new ArrayList<>();
		lines.add(e.name + " v" + e.version + ",playing:" + SOUNDS.playingCount() + ",dim-name:" + AmbientCompat.id(mc.level.dimension()));
		lines.add("dimension:" + env.dimension + ",night:" + env.night + ",rain:" + env.raining + ",rainSurfaceVolume:" + f(env.rainSurfaceVolume)
			+ ",snow:" + env.snowing + ",storm:" + env.thundering + ",sun:" + f(env.sunAngle) + ",time:" + f(env.dayTimeHour));
		lines.add("underwater:" + f(env.underwater) + ",temp:" + f(env.temperature) + ",height:r:" + f(env.relativeHeight) + ",a:"
			+ f(env.terrain.averageHeight) + " (" + f(env.relativeMinHeight) + "," + f(env.relativeMaxHeight) + ")");
		AirPocket pocket = env.terrain.airPocket;
		StringBuilder features = new StringBuilder();
		pocket.features.forEach((k, v) -> features.append(features.isEmpty() ? "" : ",").append(k).append(": ").append(f(v)));
		lines.add("features:[" + features + "],light:" + f(pocket.averageLight) + ",block-light:" + f(pocket.averageBlockLight) + ",sky-light:"
			+ f(pocket.averageSkyLight) + ",air:" + f(pocket.air) + ",sky:" + f(pocket.sky));
		StringBuilder biomes = new StringBuilder("b-volume:" + env.biomeVolume);
		for (BiomeEnvironment.Entry entry : env.biome) {
			biomes.append(',').append(entry.area().biomeId()).append(':').append(entry.volume());
		}
		lines.add(biomes.toString());
		StringBuilder categories = new StringBuilder();
		for (AmbientSoundCategory category : e.sortedSoundCategories) {
			appendCategory(categories, category);
		}
		lines.add(categories.toString());
		for (AmbientRegion region : e.activeRegions) {
			lines.add(REGION + region.name + ",playing:" + region.playing.size());
			for (AmbientSound sound : region.playing) {
				if (sound.isPlaying()) {
					if (sound.stream1 != null) {
						lines.add(streamDetails(sound, sound.stream1));
					}
					if (sound.stream2 != null) {
						lines.add(streamDetails(sound, sound.stream2));
					}
				}
			}
		}
		return lines;
	}

	private static String streamDetails(AmbientSound sound, AmbientSound.Stream stream) {
		return "[,n:" + stream.file + ",v:" + f(stream.effectiveVolume) + ",cv:" + f(stream.conditionVolume()) + ",i:" + stream.index + ",p:"
			+ f(stream.pitch) + ",t:" + stream.ticksPlayed + ",d:" + stream.duration + "]";
	}

	private static void appendCategory(StringBuilder out, AmbientSoundCategory category) {
		if (category.selection != null) {
			out.append(out.isEmpty() ? "" : ",").append(category.name).append(':').append(f(category.selection.volume()));
			for (AmbientSoundCategory child : category.children) {
				appendCategory(out, child);
			}
		}
	}

	/** For logs and QA: the debug overlay's lines, one per line ("" when the engine isn't running). */
	public static String debugText() {
		AmbientEngine e = engine;
		AmbientEnvironment env = environment;
		Minecraft mc = Minecraft.getInstance();
		if (e == null || env == null || env.dimension == null || mc.level == null) {
			return "";
		}
		return String.join("\n", debugLines(e, env, mc));
	}
}
