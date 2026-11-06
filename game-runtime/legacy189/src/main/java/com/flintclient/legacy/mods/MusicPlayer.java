package com.flintclient.legacy.mods;

import com.flintclient.client.control.feature.NowPlaying;
import com.flintclient.client.control.model.mods.MusicMods;
import com.flintclient.legacy.module.HudBackend;
import com.flintclient.legacy.shared.KeyNames189;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * What Spotify, Apple Music or any other app is playing on your computer, with its buttons: the 1.8.9 backend of the shared
 * Music Player, drawn by the shared HUD as on every line (the music comes from the Flint app, {@code control.feature.NowPlaying}).
 * Its keys (play/pause, skip, previous, forward, rewind) are read here while you play; with chat open its buttons take clicks
 * ({@code mixin.ChatScreenMixin}).
 */
public final class MusicPlayer extends HudBackend {
	private final boolean[] held = new boolean[MusicMods.MusicPlayer.KEYS.length];

	public MusicPlayer() {
		super(MusicMods.MusicPlayer.ID, "musicplayer");
	}

	@Override
	public void tick() {
		Minecraft mc = Minecraft.getInstance();
		boolean playing = mc.screen == null;
		long now = System.nanoTime();
		for (int i = 0; i < held.length; i++) {
			int code = KeyNames189.code(state().text(MusicMods.MusicPlayer.KEYS[i]));
			boolean down = playing && code != 0 && (code > 0 ? Keyboard.isKeyDown(code) : Mouse.isButtonDown(code + 100));
			if (down && !held[i]) {
				press(i, now);
			}
			held[i] = down;
		}
	}

	private void press(int key, long now) {
		NowPlaying player = NowPlaying.get();
		player.update(now, state().bool(MusicMods.MusicPlayer.MUSIC_ONLY));
		long step = state().integer(MusicMods.MusicPlayer.SEEK_STEP) * 1000L;
		switch (key) {
			case 0 -> player.press(NowPlaying.Action.TOGGLE, now);
			case 1 -> player.press(NowPlaying.Action.NEXT, now);
			case 2 -> player.press(NowPlaying.Action.PREVIOUS, now);
			case 3 -> player.skipBy(step, now);
			default -> player.skipBy(-step, now);
		}
	}

	@Override
	protected void onDisable() {
		java.util.Arrays.fill(held, false);
	}
}
