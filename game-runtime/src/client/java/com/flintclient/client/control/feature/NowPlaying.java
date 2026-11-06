package com.flintclient.client.control.feature;

import com.flintclient.client.module.account.LauncherLink;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Music Player's view of what's playing on this computer: what Windows' own media controls show (Spotify, Apple Music, a
 * browser...), read from the Flint app over the game link ({@link LauncherLink#media}); Flint never connects to a music
 * service. Asked once a second while the HUD shows the module ({@link #update} is called from its view), a little faster
 * just after a button was pressed; never otherwise. The position runs on between answers ({@link #positionMs}), so the bar
 * moves smoothly, and is set again only when the app says something else (a seek, a pause, a new song). The album art is
 * asked for when it changes, at the size it is drawn ({@link #wantArt}), and comes back with its corners rounded.
 *
 * <p>Threads: requests run one after another on a worker of their own; their answers are taken on the game thread by
 * {@link #update}, which (with the getters) is game-thread only. Nothing here allocates per frame. Pure Java: every Minecraft
 * line runs it as it is.
 */
public final class NowPlaying {
	/** How often Flint is asked while the module shows. */
	static final long POLL_NANOS = 1_000_000_000L;
	/** Just after a button: sooner, for a while, so the new song or state shows at once. */
	static final long AFTER_PRESS_NANOS = 300_000_000L;
	static final long BURST_NANOS = 400_000_000L;
	static final long BURST_FOR_NANOS = 2_000_000_000L;
	/** The Flint app is closed (or isn't answering): asked again this much later. */
	static final long OFFLINE_NANOS = 5_000_000_000L;
	/** This computer can't (not Windows), or the Flint app is from before Music Player. */
	static final long UNSUPPORTED_NANOS = 30_000_000_000L;
	/** A reported position this far from where the bar is (ms) is a seek: the bar goes there; nearer, it runs on smoothly. */
	static final long SNAP_MS = 1000;
	/** After play/pause the button shows the new state this long, whatever an answer from before it took says. */
	static final long OPTIMISTIC_NANOS = 900_000_000L;
	/** Failed art is asked for again this much later. */
	static final long ART_RETRY_NANOS = 3_000_000_000L;
	/** The album art's corner radius, as a share of its side. */
	public static final float ART_CORNER = 0.12F;
	public static final int ART_MIN = 8;
	public static final int ART_MAX = 256;

	/** Where the music comes from: the Flint app over the game link, a QA stand-in, or a test's. Blocking calls. */
	public interface Source {
		LauncherLink.Media media(boolean musicOnly) throws IOException;

		LauncherLink.Art art(int size) throws IOException;

		void control(String action, long positionMs, boolean musicOnly) throws IOException;
	}

	/** What the module can show. */
	public enum Status {
		/** Minecraft wasn't started from the Flint app: nothing to ask. */
		NO_LINK,
		/** Not answered yet. */
		LOADING,
		/** The Flint app was closed. */
		OFFLINE,
		/** This computer can't tell (not Windows), or the Flint app needs updating: {@link #reason()}. */
		UNSUPPORTED,
		/** Nothing is playing. */
		IDLE,
		/** A song, playing or paused: {@link #title()} and the rest. */
		TRACK
	}

	/** The player's buttons. */
	public enum Action {
		TOGGLE("toggle"),
		NEXT("next"),
		PREVIOUS("previous"),
		SEEK("seek");

		final String wire;

		Action(String wire) {
			this.wire = wire;
		}
	}

	private final @Nullable Source source;
	private final Executor worker;
	private final LongSupplier clock;
	private final Runnable pollTask = this::poll;
	private final Runnable artTask = this::fetchArt;

	// ---------- game thread ----------
	private Status status;
	private String reason = "";
	private String title = "";
	private String artist = "";
	private String album = "";
	private String app = "";
	private boolean playing;
	private long durationMs;
	private boolean canPrevious;
	private boolean canNext;
	private boolean canToggle;
	private boolean canSeek;
	private int artVersion;
	/** The position at {@link #anchorNanos}; it runs on from there while playing. */
	private long anchorMs;
	private long anchorNanos;
	// Times on the clock (compared by difference, so they start at the clock's own now, never at an extreme that overflows).
	private long nextPoll;
	private boolean polling;
	private boolean musicOnly;
	private long burstUntil;
	private long optimisticUntil;
	/** Bumped by each button press: an answer to a question asked before it is out of date. */
	private int presses;
	private int failures;
	private @Nullable Action lastAction;
	private long lastActionAt;
	// Art.
	private int wantSize;
	private int haveArtVersion;
	private int haveArtSize;
	private int @Nullable [] artArgb;
	private int artSide;
	private int artSerial;
	private boolean fetching;
	private long artRetryAt;

	// ---------- handed to the worker ----------
	private volatile boolean askMusicOnly;
	private volatile int askPresses;
	private volatile int askArtSize;
	// ---------- answers, from the worker ----------
	private volatile @Nullable Answer answer;
	private volatile @Nullable ArtAnswer artAnswer;

	private record Answer(int presses, LauncherLink.@Nullable Media media, @Nullable IOException error, long atNanos) {
	}

	private record ArtAnswer(int size, LauncherLink.@Nullable Art art, int @Nullable [] argb) {
	}

	public NowPlaying(@Nullable Source source, Executor worker, LongSupplier clock) {
		this.source = source;
		this.worker = worker;
		this.clock = clock;
		this.status = source == null ? Status.NO_LINK : Status.LOADING;
		long now = clock.getAsLong();
		nextPoll = now;
		burstUntil = now;
		optimisticUntil = now;
		lastActionAt = now;
		artRetryAt = now;
		anchorNanos = now;
	}

	private static volatile @Nullable NowPlaying shared;

	/** The game's: the Flint app that started it (or the QA stand-in with {@code -Dflintqa.media=demo}). */
	public static NowPlaying get() {
		NowPlaying now = shared;
		if (now == null) {
			Source source = DemoMedia.on() ? new DemoMedia() : link();
			ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
				Thread thread = new Thread(task, "Flint music player");
				thread.setDaemon(true);
				return thread;
			});
			worker.allowCoreThreadTimeOut(true);
			now = new NowPlaying(source, worker, System::nanoTime);
			shared = now;
		}
		return now;
	}

	private static @Nullable Source link() {
		LauncherLink link = LauncherLink.get();
		if (link == null) {
			return null;
		}
		return new Source() {
			@Override
			public LauncherLink.Media media(boolean musicOnly) throws IOException {
				return link.media(musicOnly);
			}

			@Override
			public LauncherLink.Art art(int size) throws IOException {
				return link.mediaArt(size);
			}

			@Override
			public void control(String action, long positionMs, boolean musicOnly) throws IOException {
				link.mediaControl(action, positionMs, musicOnly);
			}
		};
	}

	// ---------- the game thread ----------

	/**
	 * Takes the answers that came and asks again when due; called each frame the module shows (and before a key acts).
	 * {@code musicOnly}: the module's "Music apps only". Allocation-free (asking hands a reused task to the worker).
	 */
	public void update(long now, boolean musicOnly) {
		if (source == null) {
			return;
		}
		if (musicOnly != this.musicOnly) {
			this.musicOnly = musicOnly;
			nextPoll = now;
		}
		take(now);
		if (!polling && now - nextPoll >= 0) {
			polling = true;
			askMusicOnly = musicOnly;
			askPresses = presses;
			worker.execute(pollTask);
		}
		maintainArt(now);
	}

	/** The album art is wanted at {@code size} px square (0: not shown, so not fetched). */
	public void wantArt(int size) {
		wantSize = size <= 0 ? 0 : Math.clamp(size, ART_MIN, ART_MAX);
	}

	private void maintainArt(long now) {
		ArtAnswer got = artAnswer;
		if (got != null) {
			artAnswer = null;
			fetching = false;
			if (got.art() != null && got.argb() != null) {
				haveArtVersion = got.art().version();
				haveArtSize = got.size();
				artArgb = got.argb();
				artSide = got.art().width();
				artSerial++;
			} else {
				artRetryAt = now + ART_RETRY_NANOS;
			}
		}
		if (artVersion == 0 || status != Status.TRACK) {
			if (artArgb != null) {
				artArgb = null;
				haveArtVersion = 0;
				artSerial++;
			}
			return;
		}
		if (!fetching && wantSize > 0 && (artVersion != haveArtVersion || wantSize != haveArtSize) && now - artRetryAt >= 0) {
			fetching = true;
			askArtSize = wantSize;
			worker.execute(artTask);
		}
	}

	private void take(long now) {
		Answer got = answer;
		if (got == null) {
			return;
		}
		answer = null;
		polling = false;
		long interval = now - burstUntil < 0 ? BURST_NANOS : POLL_NANOS;
		if (got.presses() != presses) {
			// Asked before a button was pressed: ask again now.
			nextPoll = now;
			return;
		}
		IOException error = got.error();
		if (error != null) {
			failures++;
			if (error instanceof LauncherLink.LinkException link && link.unreachable()) {
				status = Status.OFFLINE;
				nextPoll = now + OFFLINE_NANOS;
			} else if ("bad request".equals(error.getMessage())) {
				// A Flint app from before Music Player.
				status = Status.UNSUPPORTED;
				reason = "Update the Flint app to use Music Player";
				nextPoll = now + UNSUPPORTED_NANOS;
			} else {
				// Windows was busy for a moment: keep showing what was there and ask again soon.
				if (status == Status.LOADING && failures >= 3) {
					status = Status.IDLE;
				}
				nextPoll = now + interval;
			}
			return;
		}
		failures = 0;
		LauncherLink.Media m = got.media();
		if (m == null) {
			nextPoll = now + interval;
			return;
		}
		if (!m.supported()) {
			status = Status.UNSUPPORTED;
			reason = m.reason() == null || m.reason().isBlank() ? "Music Player can't read this computer's music" : m.reason();
			nextPoll = now + UNSUPPORTED_NANOS;
			return;
		}
		nextPoll = now + interval;
		if (!m.active()) {
			status = Status.IDLE;
			title = "";
			artist = "";
			album = "";
			app = "";
			playing = false;
			artVersion = 0;
			return;
		}
		boolean newTrack = status != Status.TRACK || !m.title().equals(title) || !m.artist().equals(artist) || !m.app().equals(app);
		status = Status.TRACK;
		title = m.title();
		artist = m.artist();
		album = m.album();
		app = m.app();
		durationMs = m.durationMs();
		canPrevious = m.canPrevious();
		canNext = m.canNext();
		canToggle = m.canToggle();
		canSeek = m.canSeek();
		artVersion = m.artVersion();
		boolean nowPlaying = m.playing();
		if (!newTrack && nowPlaying != playing && got.atNanos() - optimisticUntil < 0) {
			// The app hasn't caught up with play/pause yet: the button keeps showing what was pressed.
			nowPlaying = playing;
		}
		long at = got.atNanos();
		long predicted = positionMs(at);
		if (newTrack || nowPlaying != playing || !nowPlaying || Math.abs(m.positionMs() - predicted) > SNAP_MS) {
			anchorMs = m.positionMs();
			anchorNanos = at;
		}
		playing = nowPlaying;
	}

	/**
	 * Presses {@code action} (not {@link Action#SEEK}: see {@link #seek}). The button acts at once on the HUD (play/pause flips,
	 * the position holds), Flint is told, and asked again shortly. A key works even before anything was shown (the HUD hidden
	 * with F1): Flint presses the button of whatever plays. False only without the Flint app.
	 */
	public boolean press(Action action, long now) {
		if (source == null || action == Action.SEEK) {
			return false;
		}
		if (action == Action.TOGGLE && status == Status.TRACK) {
			anchorMs = positionMs(now);
			anchorNanos = now;
			playing = !playing;
			optimisticUntil = now + OPTIMISTIC_NANOS;
		}
		send(action, 0, now);
		return true;
	}

	/** Moves to {@code positionMs} (the bar clicked, or a forward/back key); false when the app can't seek. */
	public boolean seek(long positionMs, long now) {
		if (source == null || status != Status.TRACK || !canSeek || durationMs <= 0) {
			return false;
		}
		long to = Math.clamp(positionMs, 0, durationMs);
		anchorMs = to;
		anchorNanos = now;
		send(Action.SEEK, to, now);
		return true;
	}

	/** Jumps {@code deltaMs} forward (or back, when negative). */
	public boolean skipBy(long deltaMs, long now) {
		return seek(positionMs(now) + deltaMs, now);
	}

	private void send(Action action, long positionMs, long now) {
		presses++;
		lastAction = action;
		lastActionAt = now;
		burstUntil = now + BURST_FOR_NANOS;
		nextPoll = now + AFTER_PRESS_NANOS;
		boolean only = musicOnly;
		Source s = source;
		worker.execute(() -> {
			try {
				s.control(action.wire, positionMs, only);
			} catch (IOException | RuntimeException ignored) {
				// The next answer shows what really happened.
			}
		});
	}

	// ---------- the worker ----------

	private void poll() {
		int asked = askPresses;
		LauncherLink.Media media = null;
		IOException error = null;
		try {
			media = source.media(askMusicOnly);
		} catch (IOException e) {
			error = e;
		} catch (RuntimeException e) {
			error = new IOException(e.getMessage());
		}
		answer = new Answer(asked, media, error, clock.getAsLong());
	}

	private void fetchArt() {
		int size = askArtSize;
		LauncherLink.Art art = null;
		int[] argb = null;
		try {
			art = source.art(size);
			argb = rounded(art.rgba(), art.width(), art.height());
		} catch (IOException | RuntimeException e) {
			art = null;
		}
		artAnswer = new ArtAnswer(size, art, argb);
	}

	/**
	 * A made-up album cover {@code side} px square, as RGBA bytes: a diagonal blend from {@code fromRgb} to {@code toRgb} with a
	 * record in the middle. For the settings page's sample and the QA stand-in, never for a real song.
	 */
	public static byte[] paintCover(int side, int fromRgb, int toRgb) {
		byte[] rgba = new byte[side * side * 4];
		for (int y = 0; y < side; y++) {
			for (int x = 0; x < side; x++) {
				float t = (x + y) / (2.0F * side);
				float dx = (x + 0.5F) / side - 0.5F;
				float dy = (y + 0.5F) / side - 0.5F;
				float d = (float) Math.sqrt(dx * dx + dy * dy);
				// The record: dark, with faint grooves and a lighter label in the middle; soft at its rim.
				float record = Math.clamp((0.34F - d) * side * 0.9F, 0, 1);
				float shade = d < 0.07F ? 0.35F : 0.06F + 0.03F * (float) Math.cos(d * 140);
				int i = (y * side + x) * 4;
				for (int c = 0; c < 3; c++) {
					int shift = 16 - 8 * c;
					float from = fromRgb >> shift & 0xFF;
					float to = toRgb >> shift & 0xFF;
					float v = from + (to - from) * t;
					float disc = d < 0.07F ? v * 0.55F + 255 * 0.2F : 255 * shade;
					rgba[i + c] = (byte) Math.clamp(Math.round(v * (1 - record) + disc * record), 0, 255);
				}
				rgba[i + 3] = (byte) 255;
			}
		}
		return rgba;
	}

	/**
	 * RGBA bytes as ARGB pixels with the corners rounded ({@link #ART_CORNER} of the side), their edges smoothed by how much of
	 * each pixel the curve covers.
	 */
	public static int[] rounded(byte[] rgba, int width, int height) {
		int[] out = new int[width * height];
		float r = Math.max(1, Math.round(Math.min(width, height) * ART_CORNER));
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int i = (y * width + x) * 4;
				int alpha = rgba[i + 3] & 0xFF;
				float px = x + 0.5F;
				float py = y + 0.5F;
				float cx = px < r ? r : px > width - r ? width - r : px;
				float cy = py < r ? r : py > height - r ? height - r : py;
				if (cx != px && cy != py) {
					float d = (float) Math.sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy));
					alpha = Math.round(alpha * Math.clamp(r - d + 0.5F, 0, 1));
				}
				out[y * width + x] = alpha << 24 | (rgba[i] & 0xFF) << 16 | (rgba[i + 1] & 0xFF) << 8 | rgba[i + 2] & 0xFF;
			}
		}
		return out;
	}

	// ---------- what the HUD shows (game thread) ----------

	public Status status() {
		return status;
	}

	/** Why it can't show music ({@link Status#UNSUPPORTED}). */
	public String reason() {
		return reason;
	}

	public String title() {
		return title;
	}

	public String artist() {
		return artist;
	}

	public String album() {
		return album;
	}

	/** The app playing it ("Spotify"). */
	public String app() {
		return app;
	}

	public boolean playing() {
		return status == Status.TRACK && playing;
	}

	/** The song's length in milliseconds; 0 when the app doesn't say. */
	public long durationMs() {
		return durationMs;
	}

	/** Where the song is at {@code now}, in milliseconds: running on from the last answer while it plays. */
	public long positionMs(long now) {
		long at = anchorMs;
		if (status == Status.TRACK && playing) {
			at += Math.max(0, now - anchorNanos) / 1_000_000L;
		}
		return durationMs > 0 ? Math.clamp(at, 0, durationMs) : Math.max(0, at);
	}

	public boolean canPrevious() {
		return canPrevious;
	}

	public boolean canNext() {
		return canNext;
	}

	public boolean canToggle() {
		return canToggle;
	}

	public boolean canSeek() {
		return canSeek && durationMs > 0;
	}

	/** The album art as ARGB pixels ({@link #artSide()} square, corners rounded), or null while there is none. */
	public int @Nullable [] art() {
		return artArgb;
	}

	public int artSide() {
		return artSide;
	}

	/** Changes whenever {@link #art()} does, so a view uploads it once. */
	public int artSerial() {
		return artSerial;
	}

	/** The button last pressed (by a key or a click) and when, for the HUD's press flash. */
	public @Nullable Action lastAction() {
		return lastAction;
	}

	public long lastActionAt() {
		return lastActionAt;
	}
}
