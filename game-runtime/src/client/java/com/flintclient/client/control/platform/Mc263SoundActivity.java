package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.SoundActivity;
import com.flintclient.client.control.model.ModuleState;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * Subtitles' Nearby Sound Activity on Minecraft 26.x and 1.21.x: a listener on the sound engine, the same way Minecraft's
 * own subtitles hear sounds ({@link SoundEventListener}), added only while the list is on and taken off with it, so nothing
 * listens otherwise. Every sound the game plays that has an official caption ({@link WeighedSoundEvents#getSubtitle}, which
 * resource packs and newer versions add to on their own) goes to {@link SoundActivity} once, translated by Minecraft's
 * language: a caption is turned into text once per language, then read from a cache. Sounds without a caption are left
 * out, as Minecraft's subtitles leave them out: nothing is made up.
 *
 * <p>While the list is on, Minecraft's own subtitles stand aside ({@code mixin.feature.SubtitleOverlayMixin}) without the
 * player's Show Subtitles option ever being changed. Where Minecraft hands sounds to its listeners with the audio muted
 * (26.x: always; 1.21.x: with the master volume at 0, not a category at 0) the list keeps showing them. Render thread;
 * fault-isolated.
 */
public final class Mc263SoundActivity implements SoundEventListener {
	private static final HookGuard GUARD = new HookGuard("nearby sound activity",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final Mc263SoundActivity LISTENER = new Mc263SoundActivity();
	/** Each caption's text in the language in use (captions are the sound events' own components, a thousand or so). */
	private static final Map<Component, String> TEXT = new IdentityHashMap<>();
	private static final int MOST_TEXTS = 4096;
	private static Object textLanguage;
	private static boolean listening;

	private Mc263SoundActivity() {
	}

	/** From the client tick: listens while the list is on, and only then. */
	public static void tick() {
		boolean want = GUARD.on() && SoundActivity.on();
		if (want == listening) {
			return;
		}
		try {
			SoundManager sounds = Minecraft.getInstance().getSoundManager();
			if (want) {
				sounds.addListener(LISTENER);
			} else {
				sounds.removeListener(LISTENER);
				SoundActivity.get().clear();
			}
			listening = want;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** Whether Flint's list takes the place of Minecraft's subtitles now (it is on and hearing). */
	public static boolean replacesVanilla() {
		return listening && GUARD.on() && SoundActivity.on();
	}

	@Override
	public void onPlaySound(SoundInstance sound, WeighedSoundEvents events, float range) {
		if (!GUARD.on() || !listening) {
			return;
		}
		try {
			Component caption = events.getSubtitle();
			if (caption == null) {
				return;
			}
			ModuleState s = SoundActivity.state();
			SoundSource source = sound.getSource();
			int group = group(source, sound.getIdentifier().getPath());
			boolean positioned = !sound.isRelative() && sound.getAttenuation() != SoundInstance.Attenuation.NONE;
			double x = sound.getX();
			double y = sound.getY();
			double z = sound.getZ();
			double distance = Double.NaN;
			boolean own = false;
			if (positioned) {
				Vec3 ear = Minecraft.getInstance().getSoundManager().getListenerTransform().position();
				distance = Math.sqrt(ear.distanceToSqr(x, y, z));
				LocalPlayer player = Minecraft.getInstance().player;
				own = source == SoundSource.PLAYERS && player != null && player.distanceToSqr(x, y, z) < 1.0;
			}
			if (!SoundActivity.wanted(s, group, distance, range, own)) {
				return;
			}
			SoundActivity.get().heard(s, text(caption), group, kind(source), positioned, x, y, z, System.nanoTime());
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** The caption's text in the language in use; worked out once per caption and language. */
	private static String text(Component caption) {
		Object language = Language.getInstance();
		if (language != textLanguage || TEXT.size() > MOST_TEXTS) {
			TEXT.clear();
			textLanguage = language;
		}
		String text = TEXT.get(caption);
		if (text == null) {
			text = caption.getString();
			TEXT.put(caption, text);
		}
		return text;
	}

	/** Which Sound categories switch a sound answers to: combat by its id, otherwise by its sound category. */
	static int group(SoundSource source, String path) {
		if (SoundActivity.combat(path)) {
			return SoundActivity.COMBAT;
		}
		return switch (source) {
			case PLAYERS -> SoundActivity.PLAYERS;
			case HOSTILE, NEUTRAL -> SoundActivity.ENTITIES;
			case BLOCKS, RECORDS -> SoundActivity.BLOCKS;
			case AMBIENT, WEATHER -> SoundActivity.AMBIENT;
			default -> SoundActivity.OTHER;
		};
	}

	/** Its colour under Subtitles' Color, by sound category. */
	static int kind(SoundSource source) {
		return switch (source) {
			case MUSIC -> SoundActivity.KIND_MUSIC;
			case RECORDS -> SoundActivity.KIND_RECORDS;
			case WEATHER -> SoundActivity.KIND_WEATHER;
			case BLOCKS -> SoundActivity.KIND_BLOCKS;
			case HOSTILE -> SoundActivity.KIND_HOSTILE;
			case NEUTRAL -> SoundActivity.KIND_NEUTRAL;
			case PLAYERS -> SoundActivity.KIND_PLAYERS;
			case AMBIENT -> SoundActivity.KIND_AMBIENT;
			case VOICE -> SoundActivity.KIND_VOICE;
			default -> SoundActivity.NO_KIND;
		};
	}

	/** The camera's place and yaw (third person included), for the arrows; false before there is a camera. */
	public static boolean listener(double[] out) {
		try {
			Camera camera = com.flintclient.client.compat.McCompat.mainCamera();
			if (camera == null || !camera.isInitialized()) {
				return false;
			}
			Vec3 at = camera.position();
			out[0] = at.x;
			out[1] = at.y;
			out[2] = at.z;
			out[3] = camera.yaw();
			return true;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return false;
		}
	}
}
