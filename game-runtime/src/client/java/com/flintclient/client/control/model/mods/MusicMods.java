package com.flintclient.client.control.model.mods;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import com.flintclient.client.control.model.BoolSetting;
import com.flintclient.client.control.model.Category;
import com.flintclient.client.control.model.IconId;
import com.flintclient.client.control.model.IntSetting;
import com.flintclient.client.control.model.KeySetting;
import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.model.ModuleDescriptor.Requirement;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.OptionSetting;
import com.flintclient.client.control.model.RgbSetting;
import com.flintclient.client.control.model.SettingDescriptor;
import org.jspecify.annotations.Nullable;

/**
 * Music Player: what Spotify, Apple Music or any other app is playing on this computer, on the HUD (album art, the song,
 * the artist, the time and a progress bar) with its previous, play/pause and next buttons, and keys for them. The music is
 * read from Windows' own media controls by the Flint app ({@code control.feature.NowPlaying}); Flint connects to no music
 * service. Drawn in the profile's HUD look (its style, panel, border and text), on every Minecraft line.
 */
public final class MusicMods {
	private MusicMods() {
	}

	public static final class MusicPlayer {
		public static final String ID = "music_player";

		public static final BoolSetting HIDE_IDLE = new BoolSetting("hideWhenIdle", "Hide when nothing plays",
			"Shows only while a song is playing or paused", true);
		public static final BoolSetting HIDE_PAUSED = new BoolSetting("hideWhenPaused", "Hide when paused", "Shows only while music plays",
			false);
		public static final BoolSetting MUSIC_ONLY = new BoolSetting("musicAppsOnly", "Music apps only",
			"Spotify, Apple Music and other music players; off, browsers and video apps show too", false);

		public static final BoolSetting ALBUM_ART = new BoolSetting("albumArt", "Album art", true);
		public static final BoolSetting ARTIST = new BoolSetting("artist", "Artist", true);
		public static final BoolSetting APP = new BoolSetting("appName", "App name", "Which app is playing, after the artist", false);
		public static final BoolSetting PROGRESS = new BoolSetting("progressBar", "Progress bar", "Click it with chat open to move in the song",
			true);
		public static final BoolSetting TIMES = new BoolSetting("times", "Times", "Time played and the song's length", true);
		public static final BoolSetting BUTTONS = new BoolSetting("buttons", "Buttons",
			"Previous, play/pause and next; click them with chat open", true);
		public static final BoolSetting SCROLL = new BoolSetting("scrollTitle", "Scroll long titles", "Off, a long title ends in …", true);
		public static final IntSetting WIDTH = new IntSetting("width", "Width", "At 1× size", 150, 110, 260, 2, "");
		/** The album art's side as a share of the text beside it (100%: as tall as the text, as before). */
		public static final IntSetting ART_SIZE = new IntSetting("albumArtSize", "Album art size", "100% is as tall as the text beside it", 100, 60,
			160, 5, "%");
		public static final IntSetting BAR_THICKNESS = new IntSetting("progressBarThickness", "Progress bar thickness", "At 1× size", 2, 1, 4, 1, "");
		public static final BoolSetting DIM_PAUSED = new BoolSetting("dimWhenPaused", "Dim when paused",
			"The card softens while the song is paused", true);

		/** How the card moves: Calm glides gently (fades, the title, the bar, colours), Snappy is the quick feel, Off shows changes at once. */
		public static final OptionSetting MOTION = new OptionSetting("motion", "Motion",
			"Calm glides gently; Snappy is quicker; Off shows every change at once", 0, "calm", "Calm", "snappy", "Snappy", "off", "Off");
		public static final int MOTION_CALM = 0;
		public static final int MOTION_SNAPPY = 1;
		public static final int MOTION_OFF = 2;

		/** The colours: the HUD style's (its text, softer text and accent), the four below, or an accent taken from the album art. */
		public static final OptionSetting COLORS = new OptionSetting("colors", "Colors",
			"Style follows the HUD style; Album art takes the bar's and buttons' colour from the song's cover", 0, "style", "Style", "custom",
			"Custom", "art", "Album art");
		public static final int STYLE_COLORS = 0;
		public static final int CUSTOM_COLORS = 1;
		public static final int ART_COLORS = 2;
		public static final RgbSetting TITLE_COLOR = new RgbSetting("songNameColor", "Song name color", null, 0xFFFFFF);
		public static final RgbSetting ARTIST_COLOR = new RgbSetting("artistNameColor", "Artist name color", null, 0x8B969B);
		public static final RgbSetting TIME_COLOR = new RgbSetting("durationColor", "Duration color", null, 0x8B969B);
		public static final RgbSetting PROGRESS_COLOR = new RgbSetting("progressBarColor", "Progress bar color", null, 0xE7962F);

		public static final KeySetting PLAY_PAUSE_KEY = new KeySetting("playPauseKey", "Play/pause song", null, KeySetting.NONE);
		public static final KeySetting NEXT_KEY = new KeySetting("skipKey", "Skip song", null, KeySetting.NONE);
		public static final KeySetting PREVIOUS_KEY = new KeySetting("previousKey", "Previous song", null, KeySetting.NONE);
		public static final KeySetting FORWARD_KEY = new KeySetting("forwardKey", "Forward", "Jumps ahead in the song", KeySetting.NONE);
		public static final KeySetting BACK_KEY = new KeySetting("backKey", "Rewind", "Jumps back in the song", KeySetting.NONE);
		public static final IntSetting SEEK_STEP = new IntSetting("seekStep", "Jump by", "How far Forward and Rewind go", 10, 5, 30, 5, " s");

		/** The keys, in the order the game reads them: play/pause, skip, previous, forward, rewind. */
		public static final KeySetting[] KEYS = {PLAY_PAUSE_KEY, NEXT_KEY, PREVIOUS_KEY, FORWARD_KEY, BACK_KEY};

		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Music Player", "Your music on the HUD, with its controls")
			.keywords("music", "spotify", "apple music", "media", "song", "now playing").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("General", HIDE_IDLE, HIDE_PAUSED, MUSIC_ONLY)
			.section("Display", ALBUM_ART, ART_SIZE, ARTIST, APP, PROGRESS, BAR_THICKNESS, TIMES, BUTTONS, SCROLL, WIDTH)
			.section("Colors", COLORS, TITLE_COLOR, ARTIST_COLOR, TIME_COLOR, PROGRESS_COLOR)
			.section("Controls", PLAY_PAUSE_KEY, NEXT_KEY, PREVIOUS_KEY, FORWARD_KEY, BACK_KEY, SEEK_STEP)
			.section("Motion", MOTION, DIM_PAUSED)
			.hud(HudPlacement.of(Anchor.TOP_LEFT, 4, 104))
			.note("Shows what Windows' own media controls show: Spotify, Apple Music, a browser or any other app that plays. Flint doesn't "
				+ "connect to a music service; it reads your computer's media controls and presses their buttons. Needs Windows and Minecraft "
				+ "started from the Flint app. With chat open, click the buttons, or the bar to move in the song.")
			.build();

		private MusicPlayer() {
		}

		/** Why {@code setting} changes nothing as the module is set now, or null. Allocation-free. */
		public static @Nullable String inactiveReason(ModuleState module, SettingDescriptor<?> setting) {
			if (setting == TITLE_COLOR || setting == ARTIST_COLOR || setting == TIME_COLOR || setting == PROGRESS_COLOR) {
				if (module.option(COLORS) != CUSTOM_COLORS) {
					return "Only with Custom colors";
				}
				if (setting == ARTIST_COLOR && !module.bool(ARTIST) && !module.bool(APP)) {
					return "Turn on Artist first";
				}
				if (setting == TIME_COLOR && !module.bool(TIMES)) {
					return "Turn on Times first";
				}
				return setting == PROGRESS_COLOR && !module.bool(PROGRESS) ? "Turn on Progress bar first" : null;
			}
			if (setting == SEEK_STEP) {
				return module.text(FORWARD_KEY).isEmpty() && module.text(BACK_KEY).isEmpty() ? "Set Forward or Rewind first" : null;
			}
			if (setting == ART_SIZE) {
				return module.bool(ALBUM_ART) ? null : "Turn on Album art first";
			}
			if (setting == BAR_THICKNESS) {
				return module.bool(PROGRESS) ? null : "Turn on Progress bar first";
			}
			if (setting == SCROLL) {
				return module.option(MOTION) == MOTION_OFF ? "Motion is Off: long titles end in …" : null;
			}
			if (setting == DIM_PAUSED) {
				return module.bool(HIDE_PAUSED) ? "Hidden while paused" : null;
			}
			return null;
		}
	}
}
