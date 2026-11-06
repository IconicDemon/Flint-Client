package com.flintclient.legacy;

import com.flintclient.legacy.mods.ArmorStatus;
import com.flintclient.legacy.mods.BlockOverlay;
import com.flintclient.legacy.mods.Brightness;
import com.flintclient.legacy.mods.Clock;
import com.flintclient.legacy.mods.Combo;
import com.flintclient.legacy.mods.Coordinates;
import com.flintclient.legacy.mods.Cps;
import com.flintclient.legacy.mods.Crosshair;
import com.flintclient.legacy.mods.DayCounter;
import com.flintclient.legacy.mods.Direction;
import com.flintclient.legacy.mods.Fps;
import com.flintclient.legacy.mods.HitColor;
import com.flintclient.legacy.mods.ItemCounter;
import com.flintclient.legacy.mods.Keystrokes;
import com.flintclient.legacy.mods.MotionBlur;
import com.flintclient.legacy.mods.MusicPlayer;
import com.flintclient.legacy.mods.Nametags;
import com.flintclient.legacy.mods.Ping;
import com.flintclient.legacy.mods.PotionEffects;
import com.flintclient.legacy.mods.Reach;
import com.flintclient.legacy.mods.Saturation;
import com.flintclient.legacy.mods.Scoreboard;
import com.flintclient.legacy.mods.Subtitles;
import com.flintclient.legacy.mods.TntTimer;
import com.flintclient.legacy.mods.ToggleSprint;
import com.flintclient.legacy.mods.UhcOverlay;
import com.flintclient.legacy.mods.Waypoints;
import com.flintclient.legacy.mods.Zoom;
import com.flintclient.legacy.module.Module;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Every Flint mod on 1.8.9, in the menu's order (by name). */
public final class Modules {
	public final ArmorStatus armorStatus = new ArmorStatus();
	public final BlockOverlay blockOverlay = new BlockOverlay();
	public final Brightness brightness = new Brightness();
	public final Clock clock = new Clock();
	public final Combo combo = new Combo();
	public final Coordinates coordinates = new Coordinates();
	public final Cps cps = new Cps();
	public final Crosshair crosshair = new Crosshair();
	public final DayCounter dayCounter = new DayCounter();
	public final Direction direction = new Direction();
	public final Fps fps = new Fps();
	public final HitColor hitColor = new HitColor();
	public final ItemCounter itemCounter = new ItemCounter();
	public final Keystrokes keystrokes = new Keystrokes();
	public final MotionBlur motionBlur = new MotionBlur();
	public final MusicPlayer musicPlayer = new MusicPlayer();
	public final Nametags nametags = new Nametags();
	public final Ping ping = new Ping();
	public final PotionEffects potionEffects = new PotionEffects();
	public final Reach reach = new Reach();
	public final Saturation saturation = new Saturation();
	public final Scoreboard scoreboard = new Scoreboard();
	public final Subtitles subtitles = new Subtitles();
	public final TntTimer tntTimer = new TntTimer();
	public final ToggleSprint toggleSprint = new ToggleSprint();
	public final UhcOverlay uhcOverlay = new UhcOverlay();
	public final Waypoints waypoints = new Waypoints();
	public final Zoom zoom = new Zoom();
	/** Flint's own settings (not a mod: no card). */
	public final General general = new General();

	private final List<Module> all;
	private final Module[] array;

	Modules() {
		List<Module> list = new ArrayList<Module>();
		Collections.addAll(list, armorStatus, blockOverlay, brightness, clock, combo, coordinates, cps, crosshair, dayCounter, direction, fps,
			hitColor, itemCounter, keystrokes, motionBlur, musicPlayer, nametags, ping, potionEffects, reach, saturation, scoreboard,
			subtitles, tntTimer, toggleSprint, uhcOverlay, waypoints, zoom);
		Collections.sort(list, new Comparator<Module>() {
			@Override
			public int compare(Module a, Module b) {
				return a.name.compareToIgnoreCase(b.name);
			}
		});
		all = Collections.unmodifiableList(list);
		array = list.toArray(new Module[0]);
	}

	public List<Module> all() {
		return all;
	}

	/** The same, as an array (no iterator per frame). */
	public Module[] array() {
		return array;
	}


	/** The mod backing shared module {@code id} (an older 1.8.9 id is taken too, for scripts and old favourites). */
	public Module byId(String id) {
		String shared = com.flintclient.legacy.shared.Migration189.SHARED_IDS.getOrDefault(id, id);
		for (Module m : array) {
			if (m.id.equals(shared)) {
				return m;
			}
		}
		return null;
	}
}
