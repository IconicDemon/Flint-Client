package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.feature.NowPlaying;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.MusicMods.MusicPlayer;
import com.flintclient.client.platform.Texture;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.Kit;
import org.jspecify.annotations.Nullable;

/**
 * Music Player on the HUD: a card in the HUD look with the album art on the left; beside it the song (scrolling smoothly when
 * it is too long, or ending in "…"), the artist and the app, and a row with the previous, play/pause and next buttons and the
 * time played and the song's length; along the bottom, a thin progress bar that runs on smoothly between Flint's answers. With
 * chat open the buttons light up under the pointer and take clicks, and a click on the bar moves in the song
 * ({@link HudRenderer#chatClick}). Colours follow the HUD style (its text, softer text and accent), the module's own four, or
 * the HUD style's text with the bar and buttons in a colour taken from the album art (once per picture, gliding between songs).
 *
 * <p>Motion: Calm (the default) fades the card, new art and a new song's name in gently, eases a long title into and out of
 * each slide, glides the bar after a jump and lights the buttons softly; Snappy is the quicker first feel; Off (or Minecraft's
 * reduced motion) shows every change at once. "Dim when paused" softens the content (not the panel) while a song is paused.
 *
 * <p>When nothing plays it fades out (or says so, when "Hide when nothing plays" is off). The art is uploaded once per picture,
 * at the size it is drawn (one texture, shared by the HUD and the previews). Text is Minecraft's font through the shared HUD
 * pen, so it is crisp at every size and has a shadow only where the HUD style draws one. Allocation-free per frame: the texts
 * change only with the song, the times are written into a reused buffer when their second changes. Render thread.
 */
final class MusicPlayerView extends ModuleView {
	static final int PREVIOUS = 0;
	static final int TOGGLE = 1;
	static final int NEXT = 2;
	static final int BAR = 3;
	/** Font Awesome Solid: backward-step, play, pause, forward-step, music. */
	private static final int GLYPH_PREVIOUS = 0xF048;
	private static final int GLYPH_PLAY = 0xF04B;
	private static final int GLYPH_PAUSE = 0xF04C;
	private static final int GLYPH_NEXT = 0xF051;
	private static final int GLYPH_NOTE = 0xF001;
	/*
	 * Motion's timings, by MusicPlayer.MOTION_*: Calm, Snappy (Flint's first feel) and Off (and Minecraft's reduced motion).
	 * A long title: still for MARQUEE_PAUSES at its start, then sliding at MARQUEE_SPEEDS units a second (easing into and out of
	 * the slide over MARQUEE_RAMPS), a gap before it repeats. The card fades in and out over CARD_FADES; new album art over
	 * ART_FADES; a new song's name and artist over TEXT_FADES. BAR_GLIDES, SETTLES and HOVERS are time constants (seconds)
	 * for the bar catching up after a jump, the paused dim and the album art colour, and a button lighting under the pointer.
	 */
	private static final long[] MARQUEE_PAUSES = {4_000_000_000L, 2_500_000_000L, 0};
	private static final float[] MARQUEE_SPEEDS = {13, 20, 20};
	private static final float[] MARQUEE_RAMPS = {0.7F, 0, 0};
	private static final float MARQUEE_GAP = 26;
	private static final float[] CARD_FADES = {0.45F, 0.22F, 0};
	private static final long[] ART_FADES = {650_000_000L, 240_000_000L, 0};
	private static final long[] TEXT_FADES = {420_000_000L, 0, 0};
	private static final float[] BAR_GLIDES = {0.16F, 0, 0};
	private static final float[] SETTLES = {0.32F, 0.1F, 0};
	private static final float[] HOVERS = {0.07F, 0, 0};
	/** A pressed button stays lit this long (a key or a click); with Calm it then fades back over as long again. */
	private static final long FLASH = 260_000_000L;
	/** How bright the card's content stays while the song is paused ("Dim when paused"). */
	private static final float PAUSED_DIM = 0.55F;
	/** The chat pointer counts while chat reported it this recently; a click counts on a card drawn this recently. */
	private static final long POINTER_FRESH = 200_000_000L;
	private static final long DRAWN_FRESH = 250_000_000L;
	/** The tile behind a missing picture, and the line on a bar's unplayed part. */
	private static final int TILE = 0xFF1F1C19;
	private static final float TRACK_ALPHA = 0.16F;

	private static final String NOTHING = "Nothing playing";
	private static final String PLAY_SOMETHING = "Play a song in Spotify, Apple Music or any app";
	private static final String MUSIC_PLAYER = "Music Player";
	private static final String NO_LINK = "Start Minecraft from the Flint app";
	/** The game's link dies with the app that started it (a new one doesn't know this game), so: start the game again. */
	private static final String OFFLINE = "The Flint app was closed";
	private static final String APP_CLOSED = "Flint app closed";
	private static final String OFFLINE_HINT = "Restart Minecraft from the Flint app";
	private static final String LOOKING = "Looking for music…";
	private static final String DASH = "—";
	/** The settings page's sample: fixed, so the look can be judged steadily. */
	private static final String SAMPLE_TITLE = "Midnight City";
	private static final String SAMPLE_ARTIST = "M83";
	private static final String SAMPLE_APP = "Spotify";
	private static final long SAMPLE_POSITION = 96_000;
	private static final long SAMPLE_LENGTH = 243_000;
	private static final int SAMPLE_SIDE = 96;

	/** The album art's texture (one picture at a time, whichever view draws), the picture it holds, and when it arrived. */
	private static @Nullable Texture art;
	private static int artSerial = Integer.MIN_VALUE;
	private static int artSide;
	private static long artArrived;
	/** The album art's colour for "Album art" colours (RGB), or -1 when the picture has none to give (or there is none). */
	private static int artAccent = -1;
	private static @Nullable Texture sample;
	private static int sampleAccent = -1;
	/** Where chat last saw the pointer (GUI units), and when. */
	private static double pointerX;
	private static double pointerY;
	private static long pointerAt = Long.MIN_VALUE / 2;

	private final HudText title = new HudText("");
	private final HudText artist = new HudText("");
	private final HudText app = new HudText("");
	/** A message (nothing playing, why it can't) on up to two lines, split at a space; the text and width it was split for. */
	private String message = "";
	private final HudText messageLine1 = new HudText("");
	private final HudText messageLine2 = new HudText("");
	private @Nullable String wrappedMessage;
	private int wrappedWidth = -1;
	private int wrappedTheme = -1;
	private final char[] times = new char[HudFormat.CHARS];
	private final char[] elapsed = new char[HudFormat.CHARS];
	private int timesLength;
	private int elapsedLength;
	private long shownSecond = -1;
	private long shownLength = -1;

	private int mode;
	private long frameNanos;
	/** Seconds since the last frame (at most 0.1), and the motion this frame moves with. */
	private float frameDt;
	private int motion;
	private float alpha;
	private boolean visible;
	/** The paused dim as shown (1: none), the bar's share as shown, and the "Album art" colour as shown (RGB, -1: none yet). */
	private float dim = 1;
	private float shownPlayed = -1;
	private int shownAccent = -1;
	private float accentR;
	private float accentG;
	private float accentB;
	private long accentFrame;
	/** How lit each button is under the pointer (0 to 1), and the bar's knob; the frame they last moved in. */
	private final float[] glow = new float[3];
	private float knobGlow;
	private long glowFrame;
	/** What this draw shows. */
	private boolean track;
	private boolean playing;
	private long positionMs;
	private long lengthMs;
	private boolean canPrevious;
	private boolean canNext;
	private boolean canToggle;
	private boolean canSeek;
	private @Nullable String lastTitle;
	private long marqueeStart;
	/** While paused: the slide after which a long title rests at its start (Long.MIN_VALUE while playing). */
	private long restAfter = Long.MIN_VALUE;

	// Laid out by measure.
	private int artPx;
	private int rows;
	private int textH;
	private int contentH;
	private int barH;
	private int barGap;
	private int glyphPx;
	private boolean secondRow;
	private boolean controlRow;
	private boolean bar;

	/** Where the buttons and the bar were drawn on the HUD (GUI units: x, y, w, h each), whether each was, and when. */
	private final float[] hits = new float[16];
	private final boolean[] hitShown = new boolean[4];
	private long drawnAt = Long.MIN_VALUE / 2;

	MusicPlayerView(int surface) {
		super(MusicPlayer.ID, surface);
	}

	@Override
	void update(ModuleState state, int mode, long nowNanos) {
		float dt = frameNanos == 0 ? 0 : Math.clamp((nowNanos - frameNanos) / 1.0E9F, 0, 0.1F);
		this.mode = mode;
		this.frameNanos = nowNanos;
		this.frameDt = dt;
		this.motion = Kit.reduced() ? MusicPlayer.MOTION_OFF : Math.clamp(state.option(MusicPlayer.MOTION), 0, MusicPlayer.MOTION_OFF);
		boolean show;
		if (mode == SAMPLE) {
			show = true;
			showTrack(SAMPLE_TITLE, SAMPLE_ARTIST, SAMPLE_APP, true, SAMPLE_POSITION, SAMPLE_LENGTH, true, true, true, true);
		} else if (mode == PLACEHOLDER) {
			// The full card's size (a length, so the bar's row counts) for the HUD editor's outline.
			show = true;
			showTrack(DASH, DASH, "", false, 0, 1, false, false, false, false);
		} else {
			NowPlaying now = NowPlaying.get();
			now.update(nowNanos, state.bool(MusicPlayer.MUSIC_ONLY));
			NowPlaying.Status status = now.status();
			if (status == NowPlaying.Status.TRACK) {
				show = mode != HUD || !(state.bool(MusicPlayer.HIDE_PAUSED) && !now.playing());
				if (show) {
					showTrack(now.title(), now.artist(), now.app(), now.playing(), now.positionMs(nowNanos), now.durationMs(), now.canPrevious(),
						now.canToggle(), now.canNext(), now.canSeek());
				}
			} else {
				show = mode != HUD || !state.bool(MusicPlayer.HIDE_IDLE) && status != NowPlaying.Status.LOADING;
				if (show) {
					showMessage(status, now.reason());
				}
			}
		}
		if (mode == HUD) {
			float target = show ? 1 : 0;
			float fade = CARD_FADES[motion];
			float step = fade <= 0 ? 1 : dt / fade;
			alpha = alpha < target ? Math.min(target, alpha + step) : Math.max(target, alpha - step);
			visible = alpha > 0.004F;
		} else {
			// Previews and the editor's outline draw at full opacity; the HUD's own fade is left as it was.
			visible = true;
		}
		boolean live = mode == HUD || mode == PREVIEW;
		// Paused: the content softens (not the panel), and comes back when the song plays on.
		float dimTarget = live && track && !playing && state.bool(MusicPlayer.DIM_PAUSED) ? PAUSED_DIM : 1;
		dim = approach(dim, dimTarget, dt, SETTLES[motion]);
		// The bar runs on with the song; after a jump (a seek, a new song) it glides to the new place instead of snapping.
		float played = lengthMs > 0 ? Math.clamp(positionMs / (float) lengthMs, 0, 1) : 0;
		shownPlayed = !live || shownPlayed < 0 ? played : approach(shownPlayed, played, dt, BAR_GLIDES[motion]);
	}

	/** {@code value} moved towards {@code target} as an exponential ease with time constant {@code tau} seconds (0: at once). */
	static float approach(float value, float target, float dt, float tau) {
		if (tau <= 0 || Math.abs(target - value) < 0.0005F) {
			return target;
		}
		return target + (value - target) * (float) Math.exp(-dt / tau);
	}

	/** Smoothstep: 0 to 1 starting and ending gently. */
	static float ease(float t) {
		float c = Math.clamp(t, 0, 1);
		return c * c * (3 - 2 * c);
	}

	private void showTrack(String songTitle, String songArtist, String songApp, boolean isPlaying, long position, long length, boolean previous,
		boolean toggle, boolean next, boolean seek) {
		track = true;
		if (!songTitle.equals(lastTitle)) {
			lastTitle = songTitle;
			marqueeStart = frameNanos;
			restAfter = Long.MIN_VALUE;
		}
		title.set(songTitle);
		artist.set(songArtist);
		app.set(songApp);
		playing = isPlaying;
		positionMs = position;
		lengthMs = length;
		canPrevious = previous;
		canToggle = toggle;
		canNext = next;
		canSeek = seek;
		times(position, length);
	}

	private void showMessage(NowPlaying.Status status, String reason) {
		track = false;
		String head = status == NowPlaying.Status.IDLE ? NOTHING : status == NowPlaying.Status.OFFLINE ? APP_CLOSED : MUSIC_PLAYER;
		if (!head.equals(lastTitle)) {
			lastTitle = head;
			marqueeStart = frameNanos;
		}
		title.set(head);
		message = switch (status) {
			case NO_LINK -> NO_LINK;
			case OFFLINE -> OFFLINE_HINT;
			case UNSUPPORTED -> reason;
			case LOADING -> LOOKING;
			default -> PLAY_SOMETHING;
		};
		app.set("");
		playing = false;
		lengthMs = 0;
		canPrevious = false;
		canToggle = false;
		canNext = false;
		canSeek = false;
	}

	/** "1:36 / 4:03" into {@link #times} and "1:36" into {@link #elapsed}, rewritten only when a second changes. */
	private void times(long position, long length) {
		long second = position / 1000;
		long total = length / 1000;
		if (second == shownSecond && total == shownLength) {
			return;
		}
		shownSecond = second;
		shownLength = total;
		elapsedLength = HudFormat.songTime(position, elapsed, 0);
		int n = HudFormat.songTime(position, times, 0);
		if (length > 0) {
			times[n++] = ' ';
			times[n++] = '/';
			times[n++] = ' ';
			n = HudFormat.songTime(length, times, n);
		}
		timesLength = n;
	}

	@Override
	float opacity() {
		// Calm eases the fade in and out; Snappy keeps its straight one.
		return motion == MusicPlayer.MOTION_CALM ? ease(alpha) : alpha;
	}

	@Override
	void measure(HudPen p, ModuleState state) {
		if (!visible) {
			width = 0;
			height = 0;
			return;
		}
		HudStyle s = p.s;
		width = s.px(state.integer(MusicPlayer.WIDTH));
		boolean art = state.bool(MusicPlayer.ALBUM_ART);
		secondRow = !track || state.bool(MusicPlayer.ARTIST) && !artist.blank(s.theme) || state.bool(MusicPlayer.APP) && !app.blank(s.theme);
		controlRow = track && (state.bool(MusicPlayer.BUTTONS) || state.bool(MusicPlayer.TIMES) && (positionMs > 0 || lengthMs > 0));
		rows = 1 + (secondRow ? 1 : 0) + (controlRow ? 1 : 0);
		int artShare = state.integer(MusicPlayer.ART_SIZE);
		if (!track) {
			// A message has the controls' row to itself: it takes a second line when it needs one (split as for the card with
			// three rows, whose art is the widest).
			int artMax = art ? artSide(s, linesHeight(p, 3), artShare) : 0;
			wrap(p, width - (art ? artMax + s.px(6) : 0));
			rows = messageLine2.blank(s.theme) ? 2 : 3;
		}
		textH = linesHeight(p, rows);
		artPx = art ? artSide(s, textH, artShare) : 0;
		contentH = Math.max(textH, artPx);
		bar = track && state.bool(MusicPlayer.PROGRESS) && lengthMs > 0;
		int thickness = state.integer(MusicPlayer.BAR_THICKNESS);
		barH = Math.max(thickness, s.px(thickness));
		barGap = s.px(5);
		glyphPx = Math.max(6, Math.round(p.cap(HudFaces.VALUE) * 8 / 7.0F));
		height = contentH + (bar ? barGap + barH : 0);
		if (mode == HUD) {
			// "Album art" colours need the picture even when it isn't shown: a small one is enough to take its colour from.
			boolean artColors = state.option(MusicPlayer.COLORS) == MusicPlayer.ART_COLORS;
			NowPlaying.get().wantArt(artPx > 0 ? artPx : artColors ? s.px(16) : 0);
		}
	}

	/** The album art's side: as tall as the text beside it (at least 22 units) at 100%, scaled by "Album art size". */
	private static int artSide(HudStyle s, int textHeight, int sharePercent) {
		int full = Math.max(textHeight, s.px(22));
		return Math.max(s.px(12), Math.round(full * sharePercent / 100.0F));
	}

	/**
	 * Splits {@link #message} into {@link #messageLine1} and {@link #messageLine2} for {@code maxW} px: the most whole words
	 * that fit on the first line, the rest on the second (cut with "…" if it is still too long). Only when the message, the
	 * width or the style changed.
	 */
	private void wrap(HudPen p, int maxW) {
		if (message.equals(wrappedMessage) && maxW == wrappedWidth && p.s.theme.ordinal() == wrappedTheme) {
			return;
		}
		wrappedMessage = message;
		wrappedWidth = maxW;
		wrappedTheme = p.s.theme.ordinal();
		messageLine1.set(message);
		messageLine2.set("");
		if (p.width(messageLine1, HudFaces.VALUE) <= maxW) {
			return;
		}
		for (int space = message.lastIndexOf(' '); space > 0; space = message.lastIndexOf(' ', space - 1)) {
			messageLine1.set(message.substring(0, space));
			if (p.width(messageLine1, HudFaces.VALUE) <= maxW) {
				messageLine2.set(message.substring(space + 1));
				return;
			}
		}
		// One word longer than the line: it ends in "…".
		messageLine1.set(message);
	}

	@Override
	void draw(HudPen p, ModuleState state, int x, int y) {
		HudStyle s = p.s;
		int colors = state.option(MusicPlayer.COLORS);
		boolean custom = colors == MusicPlayer.CUSTOM_COLORS;
		int titleColor = custom ? 0xFF000000 | state.rgb(MusicPlayer.TITLE_COLOR) : s.text;
		int artistColor = custom ? 0xFF000000 | state.rgb(MusicPlayer.ARTIST_COLOR) : s.muted;
		int timeColor = custom ? 0xFF000000 | state.rgb(MusicPlayer.TIME_COLOR) : s.muted;
		int barColor = custom ? 0xFF000000 | state.rgb(MusicPlayer.PROGRESS_COLOR)
			: colors == MusicPlayer.ART_COLORS ? artAccent(s.accent) : s.accent;
		// Paused, the content softens; a new song's name and artist fade in.
		float textIn = dim * songIn();
		titleColor = Palette.withAlpha(titleColor, textIn);
		artistColor = Palette.withAlpha(artistColor, textIn);
		timeColor = Palette.withAlpha(timeColor, dim);
		barColor = Palette.withAlpha(barColor, dim);
		boolean hud = mode == HUD && !p.recording();
		boolean pointing = hud && frameNanos - pointerAt < POINTER_FRESH;
		float k = 1.0F / Math.max(1, s.unitPx);
		if (hud) {
			hitShown[PREVIOUS] = false;
			hitShown[TOGGLE] = false;
			hitShown[NEXT] = false;
			hitShown[BAR] = false;
			drawnAt = frameNanos;
		}

		if (artPx > 0) {
			drawArt(p, x, y + (contentH - artPx) / 2, artPx, Palette.withAlpha(custom ? artistColor : s.muted, dim));
		}
		int tx = artPx > 0 ? x + artPx + s.px(6) : x;
		int colW = Math.max(1, x + width - tx);
		int top = y + (contentH - textH) / 2;
		int rowStep = s.lineH + s.lineGap;

		drawTitle(p, state, tx, top, colW, titleColor);
		int rowTop = top + rowStep;
		if (!track) {
			p.text(messageLine1, HudFaces.VALUE, tx, p.baseline(rowTop), artistColor, colW);
			if (rows > 2) {
				p.text(messageLine2, HudFaces.VALUE, tx, p.baseline(rowTop + rowStep), artistColor, colW);
			}
			return;
		}
		if (secondRow) {
			drawArtist(p, state, tx, rowTop, colW, artistColor);
			rowTop += rowStep;
		}
		if (controlRow) {
			int buttonsEnd = tx;
			if (state.bool(MusicPlayer.BUTTONS)) {
				// The buttons don't fade in with a new song: they are the same buttons.
				int buttonColor = Palette.withAlpha(custom ? 0xFF000000 | state.rgb(MusicPlayer.TITLE_COLOR) : s.text, dim);
				buttonsEnd = drawButtons(p, tx, rowTop, buttonColor, barColor, hud, pointing, k);
			}
			if (state.bool(MusicPlayer.TIMES)) {
				drawTimes(p, buttonsEnd, tx, tx + colW, rowTop, timeColor, buttonsEnd > tx);
			}
		}
		if (bar) {
			drawBar(p, x, y + contentH + barGap, width, barColor, hud, pointing, k);
		}
	}

	/** How far a new song's name and artist have faded in (1: fully), by Motion. */
	private float songIn() {
		long fade = TEXT_FADES[motion];
		if (fade <= 0 || mode == PLACEHOLDER) {
			return 1;
		}
		return ease((frameNanos - marqueeStart) / (float) fade);
	}

	/**
	 * "Album art" colours: the colour the song's cover gives (or {@code fallback}, the style's accent, when it gives none),
	 * gliding from the last song's to the new one's.
	 */
	private int artAccent(int fallback) {
		int target;
		if (mode == SAMPLE) {
			sampleCover();
			target = sampleAccent;
		} else if (track && mode != PLACEHOLDER) {
			syncArt(NowPlaying.get());
			target = art != null ? artAccent : -1;
		} else {
			target = -1;
		}
		if (target < 0) {
			target = fallback & 0xFFFFFF;
		}
		float tr = target >> 16 & 255;
		float tg = target >> 8 & 255;
		float tb = target & 255;
		if (shownAccent < 0) {
			accentR = tr;
			accentG = tg;
			accentB = tb;
		} else if (accentFrame != frameNanos && (accentR != tr || accentG != tg || accentB != tb)) {
			// Once a frame, however many times the card is drawn in it.
			accentFrame = frameNanos;
			float tau = SETTLES[motion] * 1.5F;
			accentR = approach(accentR, tr, frameDt, tau);
			accentG = approach(accentG, tg, frameDt, tau);
			accentB = approach(accentB, tb, frameDt, tau);
		}
		shownAccent = target;
		return 0xFF000000 | Math.round(accentR) << 16 | Math.round(accentG) << 8 | Math.round(accentB);
	}

	/**
	 * The colour a picture gives the bar and buttons: its vivid pixels averaged (each weighted by how vivid and how bright it
	 * is), calmed a little towards grey, brought to an even brightness that reads on the HUD's panels, and softened towards
	 * white. -1 for a picture without colour (black and white, or nearly). Once per picture, never per frame.
	 */
	static int accentOf(int[] argb) {
		double r = 0;
		double g = 0;
		double b = 0;
		double total = 0;
		int counted = 0;
		for (int c : argb) {
			if (c >>> 24 < 128) {
				continue;
			}
			counted++;
			int cr = c >> 16 & 255;
			int cg = c >> 8 & 255;
			int cb = c & 255;
			int max = Math.max(cr, Math.max(cg, cb));
			if (max < 24) {
				continue;
			}
			float saturation = (max - Math.min(cr, Math.min(cg, cb))) / (float) max;
			double weight = saturation * saturation * (max / 255.0);
			r += cr * weight;
			g += cg * weight;
			b += cb * weight;
			total += weight;
		}
		if (counted == 0 || total < counted * 0.015) {
			return -1;
		}
		r /= total;
		g /= total;
		b /= total;
		double grey = 0.2126 * r + 0.7152 * g + 0.0722 * b;
		r += (grey - r) * 0.18;
		g += (grey - g) * 0.18;
		b += (grey - b) * 0.18;
		double top = Math.max(r, Math.max(g, b));
		double scale = top > 0 ? 225 / top : 1;
		int outR = (int) Math.clamp(Math.round(r * scale + (255 - r * scale) * 0.1), 0, 255);
		int outG = (int) Math.clamp(Math.round(g * scale + (255 - g * scale) * 0.1), 0, 255);
		int outB = (int) Math.clamp(Math.round(b * scale + (255 - b * scale) * 0.1), 0, 255);
		return outR << 16 | outG << 8 | outB;
	}

	/** The art, its fade-in over the tile, or the tile with a note when there is none. */
	private void drawArt(HudPen p, int x, int y, int size, int noteColor) {
		int radius = Math.max(1, Math.round(size * NowPlaying.ART_CORNER));
		if (mode == SAMPLE) {
			p.texture(sampleCover(), x, y, size, size, SAMPLE_SIDE, SAMPLE_SIDE, Palette.withAlpha(0xFFFFFFFF, dim));
			return;
		}
		Texture picture = null;
		float shown = 0;
		if (track && mode != PLACEHOLDER) {
			NowPlaying now = NowPlaying.get();
			syncArt(now);
			picture = art;
			long fade = ART_FADES[motion];
			shown = fade <= 0 ? 1 : ease((frameNanos - artArrived) / (float) fade);
		}
		if (picture == null || shown < 1) {
			p.box(x, y, size, size, radius, Palette.withAlpha(TILE, dim));
			int note = Math.max(4, Math.round(size * 0.42F));
			p.glyph(GLYPH_NOTE, x + (size - note) / 2, y + (size - note) / 2, note, Palette.withAlpha(noteColor, 0.8F * (1 - shown)));
		}
		if (picture != null) {
			p.texture(picture, x, y, size, size, artSide, artSide, Palette.withAlpha(0xFFFFFFFF, shown * dim));
		}
	}

	/** Uploads a new picture (once, whichever view sees it first), or frees the texture when there is none. */
	private void syncArt(NowPlaying now) {
		if (now.artSerial() == artSerial) {
			return;
		}
		artSerial = now.artSerial();
		int[] pixels = now.art();
		if (pixels == null) {
			if (art != null) {
				art.release();
				art = null;
			}
			artAccent = -1;
			return;
		}
		artAccent = accentOf(pixels);
		// A new picture fades in only when one is replaced or appears; a resize of the same one doesn't flash.
		if (art == null || now.artSide() == artSide) {
			artArrived = frameNanos;
		}
		artSide = now.artSide();
		art = Texture.upload("hud/music_art", artSide, artSide, pixels, true);
	}

	private static Texture sampleCover() {
		Texture cover = sample;
		if (cover == null) {
			int[] pixels = NowPlaying.rounded(NowPlaying.paintCover(SAMPLE_SIDE, 0xE7962F, 0x5B1A6E), SAMPLE_SIDE, SAMPLE_SIDE);
			sampleAccent = accentOf(pixels);
			cover = Texture.upload("hud/music_sample", SAMPLE_SIDE, SAMPLE_SIDE, pixels, true);
			sample = cover;
		}
		return cover;
	}

	/** The song: sliding along when it is too long (and scrolling is on), else cut short with "…". */
	private void drawTitle(HudPen p, ModuleState state, int x, int top, int maxW, int color) {
		HudStyle s = p.s;
		int baseline = p.baseline(top);
		int w = p.width(title, HudFaces.VALUE);
		if (w <= maxW || !track || mode == PLACEHOLDER || !state.bool(MusicPlayer.SCROLL) || motion == MusicPlayer.MOTION_OFF || p.recording()) {
			p.text(title, HudFaces.VALUE, x, baseline, color, maxW);
			return;
		}
		float speed = Math.max(1, MARQUEE_SPEEDS[motion] * s.u);
		int travel = w + s.px(MARQUEE_GAP);
		float ramp = MARQUEE_RAMPS[motion];
		long slide = (long) (slideSeconds(travel, speed, ramp) * 1.0E9F);
		long pause = MARQUEE_PAUSES[motion];
		long cycle = pause + slide;
		long elapsed = frameNanos - marqueeStart;
		long phase = Math.floorMod(elapsed, cycle);
		// Paused, the title finishes the slide it is in (one not yet begun doesn't begin) and then rests at its start; playing
		// again, it waits there a while first.
		long round = Math.floorDiv(elapsed, cycle);
		if (!playing) {
			if (restAfter == Long.MIN_VALUE) {
				restAfter = phase < pause ? round - 1 : round;
			}
			if (round > restAfter) {
				phase = 0;
			}
		} else if (restAfter != Long.MIN_VALUE) {
			if (round > restAfter) {
				marqueeStart = frameNanos;
				phase = 0;
			}
			restAfter = Long.MIN_VALUE;
		}
		int offset = phase < pause ? 0 : Math.round(slideOffset((phase - pause) / 1.0E9F, travel, speed, ramp));
		int pad = s.px(2);
		p.clip(x, top - pad, x + maxW, top + s.lineH + pad);
		try {
			p.text(title, HudFaces.VALUE, x - offset, baseline, color);
			if (offset > 0) {
				p.text(title, HudFaces.VALUE, x - offset + travel, baseline, color);
			}
		} finally {
			p.unclip();
		}
	}

	/** How long a slide of {@code travel} px takes at {@code speed} px a second, easing in and out over {@code ramp} seconds. */
	static float slideSeconds(float travel, float speed, float ramp) {
		float cruise = travel / speed;
		return cruise + Math.min(ramp, cruise);
	}

	/**
	 * How far a long title has slid {@code t} seconds into its slide of {@code travel} px: speeding up gently over
	 * {@code ramp} seconds, on at {@code speed} px a second, and slowing down as gently into place (0 to {@code travel}).
	 */
	static float slideOffset(float t, float travel, float speed, float ramp) {
		float cruise = travel / speed;
		float r = Math.min(ramp, cruise);
		float total = cruise + r;
		if (t <= 0) {
			return 0;
		}
		if (t >= total) {
			return travel;
		}
		if (r <= 0) {
			return Math.min(travel, speed * t);
		}
		if (t < r) {
			return speed * t * t / (2 * r);
		}
		if (t > total - r) {
			float left = total - t;
			return travel - speed * left * left / (2 * r);
		}
		return speed * (t - r / 2);
	}

	/** The artist, and the app after it in a softer shade; a long artist gives way to the app's name, never the other way. */
	private void drawArtist(HudPen p, ModuleState state, int x, int top, int maxW, int color) {
		int baseline = p.baseline(top);
		boolean showArtist = !track || state.bool(MusicPlayer.ARTIST);
		boolean showApp = track && state.bool(MusicPlayer.APP) && !app.blank(p.s.theme);
		int appW = showApp ? p.width(app, HudFaces.VALUE) + 2 * p.s.space + p.width(DOT, HudFaces.VALUE) : 0;
		if (showApp && showArtist && !artist.blank(p.s.theme) && maxW - appW < p.s.px(24)) {
			showApp = false;
			appW = 0;
		}
		int end = x;
		if (showArtist && !artist.blank(p.s.theme)) {
			end = p.text(artist, HudFaces.VALUE, x, baseline, color, maxW - appW);
			if (showApp) {
				end = separator(p, true, end, baseline);
			}
		}
		if (showApp) {
			p.text(app, HudFaces.VALUE, end, baseline, Palette.withAlpha(color, 0.75F), Math.max(1, x + maxW - end));
		}
	}

	/** Previous, play/pause and next, lit under the chat pointer and when just pressed; returns where they end. */
	private int drawButtons(HudPen p, int x, int top, int color, int lit, boolean hud, boolean pointing, float k) {
		HudStyle s = p.s;
		int g = glyphPx;
		int gap = s.px(5);
		int gy = top + (s.lineH - g) / 2;
		NowPlaying.Action pressed = mode == HUD || mode == PREVIEW ? NowPlaying.get().lastAction() : null;
		long sincePress = pressed == null ? Long.MAX_VALUE : frameNanos - NowPlaying.get().lastActionAt();
		boolean calm = motion == MusicPlayer.MOTION_CALM;
		// A press lights its button; with Calm the light then fades back instead of going out.
		float flashLight = sincePress < FLASH ? 1 : calm && sincePress < 2 * FLASH ? 1 - ease((sincePress - FLASH) / (float) FLASH) : 0;
		boolean step = glowFrame != frameNanos;
		glowFrame = frameNanos;
		int bx = x;
		for (int i = PREVIOUS; i <= NEXT; i++) {
			boolean enabled = i == PREVIOUS ? canPrevious : i == NEXT ? canNext : canToggle;
			int pad = Math.max(1, s.px(1.5F));
			int hx = bx - pad;
			int hy = gy - pad;
			int hs = g + 2 * pad;
			boolean hover = pointing && enabled && pointerX >= hx * k && pointerX < (hx + hs) * k && pointerY >= hy * k && pointerY < (hy + hs) * k;
			if (step) {
				glow[i] = approach(glow[i], hover ? 1 : 0, frameDt, HOVERS[motion]);
			}
			float flash = pressed == (i == PREVIOUS ? NowPlaying.Action.PREVIOUS : i == NEXT ? NowPlaying.Action.NEXT : NowPlaying.Action.TOGGLE)
				? flashLight : 0;
			if (glow[i] > 0.01F) {
				p.box(hx, hy, hs, hs, hs / 2, Palette.withAlpha(0xFFFFFFFF, 0.12F * glow[i] * dim));
			}
			int glyph = i == PREVIOUS ? GLYPH_PREVIOUS : i == NEXT ? GLYPH_NEXT : playing ? GLYPH_PAUSE : GLYPH_PLAY;
			int ink = !enabled ? Palette.withAlpha(color, 0.3F) : Palette.lerp(color, lit, Math.max(glow[i], flash));
			p.glyph(glyph, bx, gy, g, ink);
			if (hud) {
				hit(i, hx * k, hy * k, hs * k, hs * k);
			}
			bx += g + gap;
		}
		return bx - gap;
	}

	/** "1:36 / 4:03" at the row's right end (just "1:36" when both don't fit beside the buttons); at its start without buttons. */
	private void drawTimes(HudPen p, int after, int left, int right, int top, int color, boolean buttons) {
		int baseline = p.baseline(top);
		int room = buttons ? right - after - p.s.px(6) : right - left;
		char[] text = times;
		int length = timesLength;
		int w = p.width(text, length, HudFaces.VALUE);
		if (w > room) {
			text = elapsed;
			length = elapsedLength;
			w = p.width(text, length, HudFaces.VALUE);
			if (w > room) {
				return;
			}
		}
		p.digits(text, length, HudFaces.VALUE, buttons ? right - w : left, baseline, color);
	}

	/** The progress bar: the played part in the bar colour, its last pixel shaded by how far into it the song is (so it glides). */
	private void drawBar(HudPen p, int x, int y, int w, int color, boolean hud, boolean pointing, float k) {
		int radius = barH / 2;
		p.box(x, y, w, barH, radius, Palette.withAlpha(0xFFFFFFFF, TRACK_ALPHA * dim));
		float share = mode == HUD || mode == PREVIEW ? shownPlayed : lengthMs > 0 ? Math.clamp(positionMs / (float) lengthMs, 0, 1) : 0;
		float played = lengthMs > 0 ? Math.clamp(share, 0, 1) * w : 0;
		int whole = (int) played;
		if (whole > 0) {
			p.box(x, y, Math.max(Math.min(barH, w), whole), barH, radius, color);
		}
		float part = played - whole;
		if (whole >= barH && whole < w && part > 0.02F) {
			p.fill(x + whole, y, x + whole + 1, y + barH, Palette.withAlpha(color, part));
		}
		// Taller hit area than the bar itself: it is only a couple of pixels.
		int pad = Math.max(2, p.s.px(3));
		boolean hover = pointing && canSeek && pointerX >= x * k && pointerX < (x + w) * k && pointerY >= (y - pad) * k
			&& pointerY < (y + barH + pad) * k;
		// The knob grows in under the pointer (with Calm; at once otherwise) and shrinks away when it leaves.
		if (!p.recording()) {
			knobGlow = approach(knobGlow, hover ? 1 : 0, frameDt, HOVERS[motion]);
		}
		if (knobGlow > 0.05F) {
			int knob = Math.max(barH, Math.round(barH * 3 * (0.5F + 0.5F * knobGlow)));
			int kx = Math.clamp(x + Math.round(played) - knob / 2, x - knob / 2, x + w - knob / 2);
			p.box(kx, y + barH / 2 - knob / 2, knob, knob, knob / 2, Palette.withAlpha(color, knobGlow));
		}
		if (hud && canSeek) {
			hit(BAR, x * k, (y - pad) * k, w * k, (barH + 2 * pad) * k);
		}
	}

	private void hit(int index, float x, float y, float w, float h) {
		hits[4 * index] = x;
		hits[4 * index + 1] = y;
		hits[4 * index + 2] = w;
		hits[4 * index + 3] = h;
		hitShown[index] = true;
	}

	// ---------- chat's pointer and clicks ----------

	/** Chat reported the pointer at ({@code x}, {@code y}) GUI units (each frame while it is open). */
	static void pointer(double x, double y, long now) {
		pointerX = x;
		pointerY = y;
		pointerAt = now;
	}

	/** A left click at ({@code x}, {@code y}) GUI units with chat open: true when it pressed a button or the bar as last drawn. */
	boolean click(double x, double y, long now) {
		if (now - drawnAt > DRAWN_FRESH) {
			return false;
		}
		for (int i = PREVIOUS; i <= BAR; i++) {
			if (!hitShown[i]) {
				continue;
			}
			float hx = hits[4 * i];
			float hy = hits[4 * i + 1];
			float hw = hits[4 * i + 2];
			float hh = hits[4 * i + 3];
			if (x < hx || x >= hx + hw || y < hy || y >= hy + hh) {
				continue;
			}
			NowPlaying playing = NowPlaying.get();
			switch (i) {
				case PREVIOUS -> {
					if (canPrevious) {
						playing.press(NowPlaying.Action.PREVIOUS, now);
					}
				}
				case NEXT -> {
					if (canNext) {
						playing.press(NowPlaying.Action.NEXT, now);
					}
				}
				case TOGGLE -> {
					if (canToggle) {
						playing.press(NowPlaying.Action.TOGGLE, now);
					}
				}
				default -> playing.seek(Math.round(Math.clamp((x - hx) / Math.max(0.001F, hw), 0, 1) * lengthMs), now);
			}
			return true;
		}
		return false;
	}

	// ---------- for the menu and the HUD editor ----------

	@Override
	@Nullable String noLabelReason(HudThemeId theme) {
		return "Music Player has no label";
	}

	@Override
	String emptyReason() {
		NowPlaying now = NowPlaying.get();
		return switch (now.status()) {
			case NO_LINK -> NO_LINK;
			case OFFLINE -> OFFLINE;
			case UNSUPPORTED -> now.reason();
			case LOADING -> LOOKING;
			case TRACK -> "Hidden while paused";
			default -> NOTHING;
		};
	}

	@Override
	int summary(ModuleState state, char[] out, long nowNanos) {
		NowPlaying now = NowPlaying.get();
		if (now.status() != NowPlaying.Status.TRACK) {
			return -1;
		}
		String song = now.title();
		int n = Math.min(song.length(), out.length);
		song.getChars(0, n, out, 0);
		String by = now.artist();
		if (!by.isEmpty() && n + 3 < out.length) {
			out[n++] = ' ';
			out[n++] = '·';
			out[n++] = ' ';
			int more = Math.min(by.length(), out.length - n);
			by.getChars(0, more, out, n);
			n += more;
		}
		return n;
	}
}
