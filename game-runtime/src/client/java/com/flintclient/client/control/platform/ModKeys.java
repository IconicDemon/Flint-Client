package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.feature.Features;
import com.flintclient.client.control.feature.NowPlaying;
import com.flintclient.client.control.model.GameOptions;
import com.flintclient.client.control.model.KeySetting;
import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.model.ModuleRegistry;
import com.flintclient.client.control.model.mods.HudMods;
import com.flintclient.client.control.model.mods.MusicMods.MusicPlayer;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.rshift.Catalog;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Every mod's "Toggle mod key" (26.3): while playing (a world, no screen open), a fresh press of a module's key turns it on
 * or off, says so on the action bar when its "Toggle mod notification" is on, and writes the profile, so the change
 * outlasts the session. Holding the key toggles once. Called once per client tick; it reads a few booleans per module.
 */
final class ModKeys {
	private static boolean[] held = new boolean[0];
	/** The Stopwatch module's three keys, held last tick: start/stop, pause, reset. */
	private static final boolean[] WATCH = new boolean[3];
	private static final ModuleDescriptor STOPWATCH = ModuleRegistry.get(HudMods.Stopwatch.ID);
	private static final KeySetting[] WATCH_KEYS = {HudMods.Stopwatch.START_STOP, HudMods.Stopwatch.PAUSE, HudMods.Stopwatch.RESET};
	/** Music Player's keys, held last tick (in {@link MusicPlayer#KEYS} order). */
	private static final boolean[] MUSIC_KEYS = new boolean[MusicPlayer.KEYS.length];
	private static final ModuleDescriptor MUSIC = ModuleRegistry.get(MusicPlayer.ID);

	private ModKeys() {
	}

	static void tick(boolean playing) {
		List<ModuleState> modules = ControlState.layout().modules();
		if (held.length != modules.size()) {
			held = new boolean[modules.size()];
		}
		boolean changed = false;
		// General › Toggle mod keybinds: off, every mod's own toggle key rests.
		boolean keys = playing && ControlState.settings().gameBool(GameOptions.MOD_KEYS);
		for (int i = 0; i < modules.size(); i++) {
			ModuleState module = modules.get(i);
			KeySetting key = module.descriptor().modKey();
			if (key == null) {
				continue;
			}
			String name = module.text(key);
			boolean down = keys && !name.isEmpty() && Mc263Features.keyHeld(name);
			if (down && !held[i]) {
				boolean on = !module.enabled();
				if (module.setEnabled(on)) {
					changed = true;
					ControlState.settings().recordChange(module.id());
					if (module.bool(module.descriptor().modKeyNotify())) {
						com.flintclient.client.compat.McCompat.hud().setOverlayMessage(Component.literal(name(module) + (on ? " enabled" : " disabled")), false);
					}
				}
			}
			held[i] = down;
		}
		tickStopwatch(playing);
		tickMusic(playing);
		if (changed) {
			try {
				ControlState.saveAll();
			} catch (RuntimeException | LinkageError e) {
				FlintClient.LOGGER.warn("Flint couldn't save after a mod key", e);
			}
		}
	}

	/** The Stopwatch's keys work while it is on and you are playing. */
	private static void tickStopwatch(boolean playing) {
		ModuleState watch = ControlState.layout().module(STOPWATCH);
		boolean active = playing && watch.enabled();
		long now = Features.nowMs();
		for (int i = 0; i < WATCH_KEYS.length; i++) {
			String name = watch.text(WATCH_KEYS[i]);
			boolean down = active && !name.isEmpty() && Mc263Features.keyHeld(name);
			if (down && !WATCH[i]) {
				switch (i) {
					case 0 -> Features.STOPWATCH.startStop(now);
					case 1 -> Features.STOPWATCH.pause(now);
					default -> Features.STOPWATCH.reset();
				}
			}
			WATCH[i] = down;
		}
	}

	/** Music Player's keys work while it is on and you are playing: play/pause, skip, previous, forward and rewind. */
	private static void tickMusic(boolean playing) {
		ModuleState music = ControlState.layout().module(MUSIC);
		boolean active = playing && music.enabled();
		long now = System.nanoTime();
		for (int i = 0; i < MusicPlayer.KEYS.length; i++) {
			String name = music.text(MusicPlayer.KEYS[i]);
			boolean down = active && !name.isEmpty() && Mc263Features.keyHeld(name);
			if (down && !MUSIC_KEYS[i]) {
				NowPlaying player = NowPlaying.get();
				player.update(now, music.bool(MusicPlayer.MUSIC_ONLY));
				long step = music.integer(MusicPlayer.SEEK_STEP) * 1000L;
				switch (i) {
					case 0 -> player.press(NowPlaying.Action.TOGGLE, now);
					case 1 -> player.press(NowPlaying.Action.NEXT, now);
					case 2 -> player.press(NowPlaying.Action.PREVIOUS, now);
					case 3 -> player.skipBy(step, now);
					default -> player.skipBy(-step, now);
				}
			}
			MUSIC_KEYS[i] = down;
		}
	}

	/** The name the Right Shift menu shows for it (its mod's, when a mod has it). */
	private static String name(ModuleState module) {
		Catalog.Mod mod = Catalog.get().modFor(module.id());
		return mod != null && mod.module() == module.descriptor() ? mod.name() : module.descriptor().name();
	}
}
