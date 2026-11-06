package com.flintclient.client.control.feature;

import com.flintclient.client.module.account.LauncherLink;

/**
 * A music app in memory, for QA only: with {@code -Dflintqa.media=demo} Music Player reads this instead of the Flint app, so
 * the HUD can be tried and photographed in a game started without the app. Three songs (the second with a title long enough
 * to scroll) that play in real time, move on at their end, and answer every button; each has album art drawn here.
 * {@code -Dflintqa.media.track=N} starts at song N (0 to 2), {@code -Dflintqa.media.paused=true} paused, and
 * {@code -Dflintqa.media.idle=true} with nothing playing. Never used without the property. Version-neutral.
 */
final class DemoMedia implements NowPlaying.Source {
	private record Song(String title, String artist, String album, long lengthMs, int from, int to) {
	}

	private static final Song[] SONGS = {
		new Song("Midnight City", "M83", "Hurry Up, We're Dreaming", 243_000, 0xFFE7962F, 0xFF5B1A6E),
		new Song("A Really Long Song Title That Scrolls Across The Card (Extended Mix)", "Flint QA Orchestra feat. Somebody Famous",
			"Long Names", 301_000, 0xFF2E86DE, 0xFF0B1F3A),
		new Song("Blinding Lights", "The Weeknd", "After Hours", 200_000, 0xFFE84855, 0xFF2B0A12),
	};

	private int song;
	private boolean playing;
	private final boolean idle;
	/** The position at {@link #since} (wall clock, ms). */
	private long at;
	private long since;
	private int artVersion = 1;

	DemoMedia() {
		song = Math.floorMod(Integer.getInteger("flintqa.media.track", 0), SONGS.length);
		playing = !Boolean.getBoolean("flintqa.media.paused");
		idle = Boolean.getBoolean("flintqa.media.idle");
		at = 42_000;
		since = System.currentTimeMillis();
	}

	static boolean on() {
		return "demo".equals(System.getProperty("flintqa.media"));
	}

	private long position(long now) {
		return playing ? at + now - since : at;
	}

	/** Moves on to the next song when this one has ended. */
	private void roll(long now) {
		long p = position(now);
		while (p >= SONGS[song].lengthMs()) {
			p -= SONGS[song].lengthMs();
			change(song + 1, now);
			at = p;
			since = now;
		}
	}

	private void change(int to, long now) {
		song = Math.floorMod(to, SONGS.length);
		at = 0;
		since = now;
		artVersion++;
	}

	@Override
	public synchronized LauncherLink.Media media(boolean musicOnly) {
		long now = System.currentTimeMillis();
		if (idle) {
			return new LauncherLink.Media(true, null, false, false, "", "", "", "", 0, 0, 0, false, false, false, false);
		}
		roll(now);
		Song s = SONGS[song];
		return new LauncherLink.Media(true, null, true, playing, s.title(), s.artist(), s.album(), "Spotify", position(now), s.lengthMs(), artVersion,
			true, true, true, true);
	}

	@Override
	public synchronized LauncherLink.Art art(int size) {
		Song s = SONGS[song];
		int n = Math.clamp(size, NowPlaying.ART_MIN, NowPlaying.ART_MAX);
		return new LauncherLink.Art(artVersion, n, n, NowPlaying.paintCover(n, s.from(), s.to()));
	}

	@Override
	public synchronized void control(String action, long positionMs, boolean musicOnly) {
		long now = System.currentTimeMillis();
		roll(now);
		switch (action) {
			case "toggle" -> {
				at = position(now);
				since = now;
				playing = !playing;
			}
			case "next" -> change(song + 1, now);
			case "previous" -> {
				// As music apps do: back to the start, unless it has only just started.
				if (position(now) > 3_000) {
					at = 0;
					since = now;
				} else {
					change(song - 1, now);
				}
			}
			case "seek" -> {
				at = Math.clamp(positionMs, 0, SONGS[song].lengthMs());
				since = now;
			}
			default -> {
			}
		}
	}
}
