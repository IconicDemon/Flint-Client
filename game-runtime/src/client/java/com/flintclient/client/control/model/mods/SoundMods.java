package com.flintclient.client.control.model.mods;

import static com.flintclient.client.control.model.mods.SoundPhysicsSettings.*;

import com.flintclient.client.control.model.Category;
import com.flintclient.client.control.model.IconId;
import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.model.ModuleDescriptor.Requirement;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.SettingDescriptor;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The sound mods: Sound Physics (Sound Physics Remastered's ray-traced reverb, occlusion and absorption) and Ambient
 * Sounds (AmbientSounds' biome, dimension and weather ambience, played with Minecraft's own sounds).
 */
public final class SoundMods {
	private SoundMods() {
	}

	/** Whether Simple Voice Chat is installed next to Flint (set by the game side at start-up); its two settings wait for it. */
	public static volatile boolean voiceChatPresent;

	public static final class SoundPhysics {
		public static final String ID = "sound_physics";
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Sound Physics",
				"Echo by room size, sounds muffled through walls and under water")
			.keywords("sound", "physics", "reverb", "echo", "occlusion", "muffle", "underwater", "audio", "remastered").icon(IconId.GAUGE)
			.category(Category.UTILITY)
			.section("General", VOICE_CHAT, HEAR_SELF, ATTENUATION_FACTOR, REVERB_ATTENUATION_DISTANCE, REVERB_GAIN, REVERB_BRIGHTNESS, REVERB_DISTANCE,
				BLOCK_ABSORPTION, OCCLUSION_VARIATION, DEFAULT_REFLECTIVITY, DEFAULT_OCCLUSION, DISTANCE_ALLOWANCE, AIR_ABSORPTION, UNDERWATER_FILTER,
				AMBIENT_SOUNDS)
			.section("Performance", RAY_COUNT, RAY_BOUNCES, NON_FULL_BLOCK_OCCLUSION, MAX_OCCLUSION_RAYS, MAX_OCCLUSION, STRICT_OCCLUSION,
				DIRECTION_EVALUATION, REDIRECT_NON_OCCLUDED, UPDATE_MOVING, UPDATE_INTERVAL)
			.advanced("World Access", MAX_DISTANCE, UNSAFE_LEVEL_ACCESS, CLONE_RANGE, CLONE_TICKS, CLONE_DISTANCE, SOUND_RATES)
			.advanced("Block Reflectivity", REFLECTIVITY)
			.advanced("Block Occlusion", occlusionSection())
			.advanced("Debug", DEBUG_LOGGING, OCCLUSION_LOGGING, ENVIRONMENT_LOGGING, PERFORMANCE_LOGGING, RENDER_BOUNCES, RENDER_OCCLUSION)
			.needs(Requirement.WORLD)
			.note("Sound Physics Remastered by Sonic Ether, vlad2305m, Max Henkel and Saint (GPL-3.0), built into Flint. Rays are traced on the "
				+ "sound thread from a copy of the world around you. While it's on, Sound Filters steps aside. Needs a sound device with OpenAL's "
				+ "effects extension.")
			.build();

		private SoundPhysics() {
		}
	}

	public static final class AmbientSounds {
		public static final String ID = "ambient_sounds";
		public static final ModuleDescriptor DESCRIPTOR = build();

		private static ModuleDescriptor build() {
			ModuleDescriptor.Builder b = ModuleDescriptor.builder(ID, "Ambient Sounds", "Wind, insects, birds, caves and weather by biome and time of day")
				.keywords("ambient", "ambience", "sound", "biome", "wind", "birds", "crickets", "cave", "atmosphere", "audio").icon(IconId.GAUGE)
				.category(Category.UTILITY)
				.section("General", AmbientSoundsSettings.VOLUME, AmbientSoundsSettings.MASTER, AmbientSoundsSettings.SCAN_STEPS,
					AmbientSoundsSettings.OFFSET, AmbientSoundsSettings.FADE_VOLUME, AmbientSoundsSettings.FADE_PITCH,
					AmbientSoundsSettings.SILENT_DIMENSIONS, AmbientSoundsSettings.DEBUG)
				.section("Categories", AmbientSoundsSettings.CATEGORY_VOLUME)
				.advanced("Dimensions", AmbientSoundsSettings.DIMENSION_VOLUME)
				.advanced("Regions", AmbientSoundsSettings.silentRegions());
			// Each region with sounds: its overall volume, then each sound's.
			for (int i = 0; i < AmbientSoundsSettings.REGIONS.length; i++) {
				if (AmbientSoundsSettings.REGIONS[i].length > 1) {
					b.advanced("Region: " + AmbientSoundsSettings.label(AmbientSoundsSettings.REGIONS[i][0]), AmbientSoundsSettings.region(i));
				}
			}
			return b.needs(Requirement.WORLD)
				.note("AmbientSounds by CreativeMD and AriaFreeze (LGPL-3.0), built into Flint with its rules for dimensions, biomes, height, caves, "
					+ "weather and time. Its own recordings can't be shared, so it plays Minecraft's sounds; it is silent while Minecraft's "
					+ "Ambient/Environment volume is 0.")
				.build();
		}

		private AmbientSounds() {
		}
	}

	/** Why {@code setting} of one of these modules changes nothing as things are set now, or null. */
	public static @Nullable String inactiveReason(ModuleState module, SettingDescriptor<?> setting) {
		if (setting == VOICE_CHAT) {
			return voiceChatPresent ? null : "Needs Simple Voice Chat";
		}
		if (setting == HEAR_SELF) {
			return !voiceChatPresent ? "Needs Simple Voice Chat" : module.bool(VOICE_CHAT) ? null : "Turn on Simple Voice Chat first";
		}
		if (setting == UPDATE_INTERVAL) {
			return module.bool(UPDATE_MOVING) ? null : "Turn on Update long sounds first";
		}
		if (setting == REDIRECT_NON_OCCLUDED) {
			return module.bool(DIRECTION_EVALUATION) ? null : "Turn on Sound direction first";
		}
		if (setting == OCCLUSION_VARIATION) {
			return module.bool(STRICT_OCCLUSION) ? "Strict occlusion uses one path" : null;
		}
		if (setting == CLONE_RANGE || setting == CLONE_TICKS || setting == CLONE_DISTANCE) {
			return module.bool(UNSAFE_LEVEL_ACCESS) ? "Direct world access is on" : null;
		}
		return null;
	}

	/** The batch above, for the registry. */
	public static List<ModuleDescriptor> all() {
		return List.of(SoundPhysics.DESCRIPTOR, AmbientSounds.DESCRIPTOR);
	}
}
