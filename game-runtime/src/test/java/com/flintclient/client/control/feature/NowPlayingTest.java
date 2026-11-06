package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.module.account.LauncherLink;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Music Player's state: asking, the running position, the buttons, the art, and what it says when it can't. */
class NowPlayingTest {
	private static final long MS = 1_000_000L;
	private static final long SECOND = 1_000_000_000L;

	/** A music app as the Flint app reports it; its clock is the test's. */
	private final class App implements NowPlaying.Source {
		String title = "Midnight City";
		String artist = "M83";
		boolean playing = true;
		long position = 30_000;
		long length = 200_000;
		int artVersion = 1;
		boolean canSeek = true;
		boolean active = true;
		boolean supported = true;
		IOException failure;
		int asked;
		int artAsked;
		final List<String> pressed = new ArrayList<>();

		@Override
		public LauncherLink.Media media(boolean musicOnly) throws IOException {
			asked++;
			if (failure != null) {
				throw failure;
			}
			if (!supported) {
				return new LauncherLink.Media(false, "Music Player needs Windows for now", false, false, "", "", "", "", 0, 0, 0, false, false, false,
					false);
			}
			return new LauncherLink.Media(true, null, active, playing, active ? title : "", artist, "", "Spotify", position, length,
				artVersion, true, true, true, canSeek);
		}

		@Override
		public LauncherLink.Art art(int size) {
			artAsked++;
			return new LauncherLink.Art(artVersion, size, size, NowPlaying.paintCover(size, 0xE7962F, 0x5B1A6E));
		}

		@Override
		public void control(String action, long positionMs, boolean musicOnly) {
			pressed.add(action.equals("seek") ? "seek " + positionMs : action);
		}
	}

	private final App app = new App();
	/** Requests wait here until the test lets them run, as on the worker. */
	private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
	private long now = 1_000 * SECOND;
	private final NowPlaying player = new NowPlaying(app, queue::add, () -> now);

	private void work() {
		while (!queue.isEmpty()) {
			queue.poll().run();
		}
	}

	/** One frame: take answers, ask when due, and let the worker answer at once. */
	private void frame() {
		player.update(now, false);
		work();
		player.update(now, false);
	}

	@Test
	void withoutTheFlintAppNothingIsAsked() {
		NowPlaying none = new NowPlaying(null, Runnable::run, () -> now);
		none.update(now, false);
		assertEquals(NowPlaying.Status.NO_LINK, none.status());
		assertFalse(none.press(NowPlaying.Action.TOGGLE, now));
		assertFalse(none.seek(1000, now));
	}

	@Test
	void aSongShowsAndItsPositionRunsOnSmoothlyBetweenAnswers() {
		assertEquals(NowPlaying.Status.LOADING, player.status());
		frame();
		assertEquals(NowPlaying.Status.TRACK, player.status());
		assertEquals("Midnight City", player.title());
		assertEquals("M83", player.artist());
		assertEquals("Spotify", player.app());
		assertTrue(player.playing());
		assertEquals(30_000, player.positionMs(now));
		assertEquals(30_250, player.positionMs(now + 250 * MS), "it runs on between answers");
		// A second later the app says 31 s, as expected: the bar isn't moved back or forth.
		now += SECOND;
		app.position = 31_020;
		frame();
		assertEquals(31_000, player.positionMs(now), "a few milliseconds off isn't a seek");
		assertEquals(200_000, player.positionMs(now + 500 * SECOND), "never past the end");
	}

	@Test
	void aSeekOrAPauseInTheAppMovesTheBar() {
		frame();
		now += SECOND;
		app.position = 120_000;
		frame();
		assertEquals(120_000, player.positionMs(now), "seeked in the app: the bar goes there");
		now += SECOND;
		app.playing = false;
		app.position = 121_000;
		frame();
		assertFalse(player.playing());
		assertEquals(121_000, player.positionMs(now));
		assertEquals(121_000, player.positionMs(now + 30 * SECOND), "paused: it holds");
	}

	@Test
	void flintIsAskedOnceASecondAndOnlyWhileShown() {
		frame();
		assertEquals(1, app.asked);
		for (int i = 0; i < 50; i++) {
			now += 10 * MS;
			frame();
		}
		assertEquals(1, app.asked, "once a second, not once a frame");
		now += SECOND;
		frame();
		assertEquals(2, app.asked);
		// Hidden for a minute (F1, a menu): nothing is asked, as nothing calls it.
		now += 60 * SECOND;
		frame();
		assertEquals(3, app.asked, "asked again as soon as it shows");
	}

	@Test
	void playPauseFlipsAtOnceAndALateAnswerDoesntFlipItBack() {
		frame();
		now += SECOND;
		player.update(now, false);
		// A question is on its way when the button is pressed...
		assertEquals(1, queue.size());
		assertTrue(player.press(NowPlaying.Action.TOGGLE, now));
		assertFalse(player.playing(), "the button shows Play at once");
		long held = player.positionMs(now);
		work();
		assertEquals(List.of("toggle"), app.pressed);
		// ...and its answer (asked before the press, still playing) is dropped.
		player.update(now, false);
		assertFalse(player.playing());
		assertEquals(held, player.positionMs(now + 5 * SECOND), "paused where it was pressed");
		// The app is slow to pause: an answer soon after still says playing, and the button keeps showing what was pressed.
		now += 300 * MS;
		frame();
		assertFalse(player.playing());
		// Later the app really is paused.
		app.playing = false;
		now += SECOND;
		frame();
		assertFalse(player.playing());
		assertEquals(NowPlaying.Action.TOGGLE, player.lastAction());
	}

	@Test
	void nextIsSentAndAskedAboutSoon() {
		frame();
		int before = app.asked;
		assertTrue(player.press(NowPlaying.Action.NEXT, now));
		work();
		assertEquals(List.of("next"), app.pressed);
		app.title = "Blinding Lights";
		app.position = 0;
		now += 300 * MS;
		frame();
		assertEquals(before + 1, app.asked, "asked a third of a second after, not a second");
		assertEquals("Blinding Lights", player.title());
		assertEquals(0, player.positionMs(now), "a new song starts its own bar");
	}

	@Test
	void seekingNeedsAnAppThatCanAndStaysInTheSong() {
		app.canSeek = false;
		frame();
		assertFalse(player.seek(10_000, now));
		app.canSeek = true;
		now += SECOND;
		frame();
		assertTrue(player.seek(500_000, now));
		assertEquals(200_000, player.positionMs(now));
		assertTrue(player.skipBy(-10_000, now));
		assertTrue(player.skipBy(-1_000_000, now));
		assertEquals(0, player.positionMs(now));
		work();
		assertEquals(List.of("seek 200000", "seek 190000", "seek 0"), app.pressed);
	}

	@Test
	void theArtIsFetchedOncePerPictureAndSizeWithItsCornersRounded() {
		player.wantArt(32);
		frame();
		frame();
		assertEquals(1, app.artAsked);
		int[] art = player.art();
		assertNotNull(art);
		assertEquals(32, player.artSide());
		assertEquals(0, art[0] >>> 24, "the corner is cut away");
		assertEquals(255, art[16 * 32 + 16] >>> 24, "the middle is opaque");
		int serial = player.artSerial();
		for (int i = 0; i < 10; i++) {
			frame();
		}
		assertEquals(1, app.artAsked, "the same picture at the same size isn't asked for again");
		assertEquals(serial, player.artSerial());
		player.wantArt(48);
		frame();
		frame();
		assertEquals(2, app.artAsked, "a new size");
		assertEquals(48, player.artSide());
		app.artVersion = 2;
		now += SECOND;
		frame();
		frame();
		assertEquals(3, app.artAsked, "a new picture");
		app.artVersion = 0;
		now += SECOND;
		frame();
		assertNull(player.art(), "no picture: none shown");
		player.wantArt(0);
		app.artVersion = 5;
		now += SECOND;
		frame();
		frame();
		assertEquals(3, app.artAsked, "album art turned off: not fetched");
	}

	@Test
	void whatItSaysWhenItCant() {
		app.failure = new LauncherLink.LinkException("Flint isn't reachable.", true);
		frame();
		assertEquals(NowPlaying.Status.OFFLINE, player.status());
		now += SECOND;
		frame();
		assertEquals(1, app.asked, "the closed app is asked again five seconds later, not every second");
		app.failure = new LauncherLink.LinkException("bad request", false);
		now += 5 * SECOND;
		frame();
		assertEquals(NowPlaying.Status.UNSUPPORTED, player.status());
		assertEquals("Update the Flint app to use Music Player", player.reason());
		app.failure = null;
		app.supported = false;
		now += 30 * SECOND;
		frame();
		assertEquals(NowPlaying.Status.UNSUPPORTED, player.status());
		assertEquals("Music Player needs Windows for now", player.reason());
		app.supported = true;
		app.active = false;
		now += 30 * SECOND;
		frame();
		assertEquals(NowPlaying.Status.IDLE, player.status());
		assertFalse(player.playing());
	}

	@Test
	void aBusyMomentKeepsTheSongShown() {
		frame();
		app.failure = new LauncherLink.LinkException("busy", false);
		now += SECOND;
		frame();
		assertEquals(NowPlaying.Status.TRACK, player.status());
		assertEquals("Midnight City", player.title());
	}

	@Test
	void keysWorkBeforeAnythingWasShown() {
		// The HUD was hidden (F1): nothing was asked yet, and a key still presses the app's button.
		assertTrue(player.press(NowPlaying.Action.NEXT, now));
		work();
		assertEquals(List.of("next"), app.pressed);
	}
}
