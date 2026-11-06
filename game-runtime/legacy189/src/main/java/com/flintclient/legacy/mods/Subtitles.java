package com.flintclient.legacy.mods;

import com.flintclient.client.control.feature.SoundActivity;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.MoreMods;
import com.flintclient.legacy.Flint;
import com.flintclient.legacy.module.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.living.player.LocalClientPlayerEntity;
import net.minecraft.client.sound.SoundCategory;
import net.minecraft.client.sound.SoundPool;
import net.minecraft.client.sound.instance.SoundInstance;

/**
 * Subtitles on 1.8.9: its Nearby Sound Activity, the sounds around you in the bottom-right corner with an arrow toward each,
 * drawn by the shared HUD exactly as on every other line ({@code control.hud.render.SoundActivityView}). 1.8.9 has no
 * subtitles, so this is the sound hook the newer lines get from Minecraft: every sound the engine is asked to play
 * ({@code mixin.SoundEngineMixin}, before its volume checks, so a muted game still shows them) is captioned from
 * {@link SoundCaptions189} and handed to the shared list. Nothing listens while the list is off.
 *
 * <p>Of Subtitles' settings, 1.8.9 has those of Nearby Sound Activity and the kind colours it can use; colouring Minecraft's
 * own subtitles waits out of sight (there are none), with Voice and Music (no 1.8.9 sound of either has a caption).
 */
public final class Subtitles extends Module {
	/** 1.8.9 plays a sound this far away at full volume (and farther when louder), as its sound engine does. */
	private static final float RANGE = 16;
	private static boolean failed;

	public Subtitles() {
		super(MoreMods.Subtitles.ID, "subtitles");
		reads(MoreMods.Subtitles.ACTIVITY, MoreMods.Subtitles.ARROWS, MoreMods.Subtitles.BACKGROUND, MoreMods.Subtitles.SHADOW,
			MoreMods.Subtitles.SCALE, MoreMods.Subtitles.TEXT_COLOR, MoreMods.Subtitles.KIND_COLORS, MoreMods.Subtitles.BACKGROUND_COLOR,
			MoreMods.Subtitles.BACKGROUND_OPACITY, MoreMods.Subtitles.RIGHT_PADDING, MoreMods.Subtitles.BOTTOM_PADDING,
			MoreMods.Subtitles.MAX_ENTRIES, MoreMods.Subtitles.DURATION, MoreMods.Subtitles.FADE, MoreMods.Subtitles.GROUPING,
			MoreMods.Subtitles.MAX_DISTANCE, MoreMods.Subtitles.OWN_SOUNDS, MoreMods.Subtitles.INCLUDE_AMBIENT,
			MoreMods.Subtitles.INCLUDE_BLOCKS, MoreMods.Subtitles.INCLUDE_ENTITIES, MoreMods.Subtitles.INCLUDE_COMBAT,
			MoreMods.Subtitles.INCLUDE_PLAYERS, MoreMods.Subtitles.RECORDS, MoreMods.Subtitles.WEATHER, MoreMods.Subtitles.BLOCKS,
			MoreMods.Subtitles.HOSTILE, MoreMods.Subtitles.NEUTRAL, MoreMods.Subtitles.PLAYERS, MoreMods.Subtitles.AMBIENT);
	}

	/**
	 * The sound engine is about to play {@code sound} from {@code pool} (null when 1.8.9 doesn't know the sound): captioned and
	 * added to the list when the list is on and wants it. Never throws (a failure turns the list's hook off for the session).
	 */
	public static void heard(SoundInstance sound, SoundPool pool) {
		if (failed || !SoundActivity.on() || sound == null || pool == null) {
			return;
		}
		try {
			SoundCaptions189.Caption caption = SoundCaptions189.of(sound.getLocation().getPath(), sound.getVolume());
			if (caption == null) {
				return;
			}
			ModuleState s = SoundActivity.state();
			boolean positioned = sound.getAttenuationType() != SoundInstance.Attenuation.NONE;
			double x = sound.getX();
			double y = sound.getY();
			double z = sound.getZ();
			double distance = Double.NaN;
			boolean own = false;
			LocalClientPlayerEntity player = Minecraft.getInstance().player;
			if (positioned && player != null) {
				distance = Math.sqrt(sq(x - player.x) + sq(y - player.y) + sq(z - player.z));
				// The player's own sounds play where it stands.
				own = distance < 1.0 && (caption.group() == SoundActivity.PLAYERS || pool.getCategory() == SoundCategory.PLAYERS);
			}
			double range = RANGE * Math.max(1.0F, sound.getVolume());
			if (!SoundActivity.wanted(s, caption.group(), distance, range, own)) {
				return;
			}
			SoundActivity.get().heard(s, caption.text(), caption.group(), kind(pool.getCategory()), positioned, x, y, z, System.nanoTime());
		} catch (RuntimeException | LinkageError e) {
			failed = true;
			Flint.LOG.error("Flint: Nearby Sound Activity's sound hook failed; it's off for this session", e);
		}
	}

	private static double sq(double v) {
		return v * v;
	}

	/** Its colour under Subtitles' Color: 1.8.9's mobs are the hostile ones, its animals the neutral ones. */
	static int kind(SoundCategory category) {
		if (category == null) {
			return SoundActivity.NO_KIND;
		}
		return switch (category) {
			case MUSIC -> SoundActivity.KIND_MUSIC;
			case RECORDS -> SoundActivity.KIND_RECORDS;
			case WEATHER -> SoundActivity.KIND_WEATHER;
			case BLOCKS -> SoundActivity.KIND_BLOCKS;
			case MOBS -> SoundActivity.KIND_HOSTILE;
			case ANIMALS -> SoundActivity.KIND_NEUTRAL;
			case PLAYERS -> SoundActivity.KIND_PLAYERS;
			case AMBIENT -> SoundActivity.KIND_AMBIENT;
			default -> SoundActivity.NO_KIND;
		};
	}

	@Override
	protected void onDisable() {
		SoundActivity.get().clear();
	}
}
