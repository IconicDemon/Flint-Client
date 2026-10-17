package com.flintclient.client.control.store;

import com.flintclient.client.control.model.BoolSetting;
import com.flintclient.client.control.model.ChoiceSetting;
import com.flintclient.client.control.model.IntSetting;
import com.flintclient.client.control.model.InterfaceSize;
import com.flintclient.client.control.model.LayoutNames;
import com.flintclient.client.control.model.LibraryQuery;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The Control Center's own settings, in {@code config/flintclient/control-center.json}: sounds, motion,
 * accessibility, the HUD's debug-screen rule, the library's favourites and recents, first-time setup and the
 * active layout. Key bindings are not here: they are Minecraft key mappings, saved in {@code options.txt}.
 *
 * <p>Read once, on first use (or by {@link #load()}, which the Control Center calls at client start so the first
 * HUD frame doesn't do it). Setters change memory and mark the file dirty; {@link #save()} writes it, and is
 * called only at the moments the Control Center decides (closing, Save, layout actions, finishing setup), never
 * per frame or per slider step. Getters are allocation-free.
 */
public final class ControlSettings {
	public static final String DIR = "flintclient";
	public static final String FILE = "control-center.json";
	public static final int SCHEMA = 1;

	public static final BoolSetting SOUNDS = new BoolSetting("enabled", "Interface sounds", true);
	/**
	 * The volume as the slider shows it, 0–100 % in steps of 5. The file keeps it as a fraction ({@code 0.6}), and
	 * playback uses {@link #soundVolume()}, the same fraction.
	 */
	public static final IntSetting VOLUME = new IntSetting("volume", "Volume", null, 60, 0, 100, 5, "%");
	public static final BoolSetting ANIMATIONS = new BoolSetting("animations", "Animations", true);
	public static final BoolSetting FOLLOW_REDUCED_MOTION = new BoolSetting("followMinecraftReducedMotion",
		"Follow Minecraft's reduced motion", "When the panorama speed is 0 in Minecraft's Accessibility settings", true);
	public static final BoolSetting TRANSITION_BLUR = new BoolSetting("transitionBlur", "Motion blur",
		"A brief directional blur on panel edges while they move", true);
	public static final BoolSetting GAMEPLAY_BLUR = new BoolSetting("gameplayBlur", "Gameplay motion blur",
		"Blends recent frames while the camera moves. Costs some frame time.", false);
	public static final IntSetting GAMEPLAY_BLUR_STRENGTH = new IntSetting("gameplayBlurStrength", "Strength", null, 50, 5, 100, 5, "%");
	public static final ChoiceSetting<InterfaceSize> INTERFACE_SIZE = new ChoiceSetting<>("interfaceSize", "Interface size",
		"Larger sizes are used only when they fit your window.", InterfaceSize.class, InterfaceSize.AUTO);
	public static final BoolSetting HIGH_CONTRAST = new BoolSetting("highContrast", "High-contrast outlines", false);
	public static final BoolSetting SHOW_WITH_DEBUG_SCREEN = new BoolSetting("showWithDebugScreen",
		"Show the HUD with the debug screen (F3)", false);

	private static final int MAX_ID_LENGTH = 64;

	private final JsonStore store;
	private boolean loaded;
	private boolean dirty;

	private boolean sounds;
	/** Percent, 0..100. */
	private int volume;
	private boolean animations;
	private boolean followReducedMotion;
	private boolean transitionBlur;
	private boolean gameplayBlur;
	/** Percent, 5..100. */
	private int gameplayBlurStrength;
	private InterfaceSize interfaceSize = InterfaceSize.AUTO;
	private boolean highContrast;
	private boolean showWithDebugScreen;
	private List<String> favorites = List.of();
	private List<String> recents = List.of();
	private boolean onboardingCompleted;
	private boolean hintShown;
	private @Nullable String activeLayout;
	private com.flintclient.client.control.model.MenuTheme menuTheme = com.flintclient.client.control.model.MenuTheme.FLINT;
	/** The mods grid as compact rows (two columns) instead of cards. */
	private boolean compactView;
	/** Settings sections the player folded away, as {@code "moduleId/Section title"}. */
	private List<String> collapsed = List.of();

	/** Settings under {@code configDir} (Fabric's config folder): {@code configDir/flintclient/control-center.json}. */
	public ControlSettings(Path configDir) {
		this(configDir, Clock.systemDefaultZone());
	}

	ControlSettings(Path configDir, Clock clock) {
		this.store = new JsonStore(configDir.resolve(DIR).resolve(FILE), SCHEMA, clock);
	}

	public Path file() {
		return store.file();
	}

	/** Reads the file now if it hasn't been read yet (otherwise the first getter does). */
	public void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		JsonObject root = store.root();
		JsonObject soundsObject = JsonFields.object(root, "sounds");
		sounds = SOUNDS.parse(soundsObject == null ? null : soundsObject.get(SOUNDS.id()));
		volume = volumePercent(soundsObject == null ? null : soundsObject.get(VOLUME.id()));
		JsonObject motion = JsonFields.object(root, "motion");
		animations = ANIMATIONS.parse(motion == null ? null : motion.get(ANIMATIONS.id()));
		followReducedMotion = FOLLOW_REDUCED_MOTION.parse(motion == null ? null : motion.get(FOLLOW_REDUCED_MOTION.id()));
		transitionBlur = TRANSITION_BLUR.parse(motion == null ? null : motion.get(TRANSITION_BLUR.id()));
		gameplayBlur = GAMEPLAY_BLUR.parse(motion == null ? null : motion.get(GAMEPLAY_BLUR.id()));
		gameplayBlurStrength = GAMEPLAY_BLUR_STRENGTH.parse(motion == null ? null : motion.get(GAMEPLAY_BLUR_STRENGTH.id()));
		JsonObject accessibility = JsonFields.object(root, "accessibility");
		interfaceSize = INTERFACE_SIZE.parse(accessibility == null ? null : accessibility.get(INTERFACE_SIZE.id()));
		highContrast = HIGH_CONTRAST.parse(accessibility == null ? null : accessibility.get(HIGH_CONTRAST.id()));
		JsonObject hud = JsonFields.object(root, "hud");
		showWithDebugScreen = SHOW_WITH_DEBUG_SCREEN.parse(hud == null ? null : hud.get(SHOW_WITH_DEBUG_SCREEN.id()));
		JsonObject library = JsonFields.object(root, "library");
		favorites = ids(JsonFields.strings(library, "favorites"), Integer.MAX_VALUE);
		recents = ids(JsonFields.strings(library, "recents"), LibraryQuery.MAX_RECENTS);
		JsonObject onboarding = JsonFields.object(root, "onboarding");
		onboardingCompleted = Boolean.TRUE.equals(JsonFields.bool(onboarding, "completed"));
		hintShown = Boolean.TRUE.equals(JsonFields.bool(onboarding, "hintShown"));
		String active = JsonFields.string(root, "activeLayout");
		activeLayout = LayoutNames.validId(active) ? active : null;
		JsonObject menu = JsonFields.object(root, "menu");
		JsonObject theme = JsonFields.object(menu, "theme");
		menuTheme = com.flintclient.client.control.model.MenuTheme.read(JsonFields.string(theme, "accent"), JsonFields.string(theme, "surface"),
			integer(theme, "roundness"), integer(theme, "dim"));
		compactView = Boolean.TRUE.equals(JsonFields.bool(menu, "compactView"));
		collapsed = keys(JsonFields.strings(menu, "collapsed"));
	}

	private static @Nullable Integer integer(@Nullable JsonObject parent, String key) {
		JsonElement e = parent == null ? null : parent.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
			return null;
		}
		double v = e.getAsDouble();
		return Double.isFinite(v) ? (int) Math.round(Math.clamp(v, -1_000_000, 1_000_000)) : null;
	}

	/** Section keys: short, distinct, at most 512 of them. */
	private static List<String> keys(@Nullable List<String> raw) {
		if (raw == null) {
			return List.of();
		}
		List<String> out = new ArrayList<>();
		for (String key : raw) {
			if (key != null && !key.isEmpty() && key.length() <= 160 && !out.contains(key) && out.size() < 512) {
				out.add(key);
			}
		}
		return List.copyOf(out);
	}

	/** Writes the file if anything changed (and it may be written). Returns true when disk matches memory. */
	public boolean save() {
		load();
		if (!dirty) {
			return true;
		}
		JsonObject root = store.root();
		// Each value is rewritten only when it differs from what the file says (see JsonFields), so an option from a
		// newer Flint survives until the player changes that setting.
		JsonObject soundsObject = JsonFields.child(root, "sounds");
		JsonFields.put(soundsObject, SOUNDS.id(), SOUNDS, sounds);
		JsonFields.put(soundsObject, VOLUME.id(), volumePercent(soundsObject.get(VOLUME.id())), volume, new JsonPrimitive(volume / 100.0));
		JsonObject motion = JsonFields.child(root, "motion");
		JsonFields.put(motion, ANIMATIONS.id(), ANIMATIONS, animations);
		JsonFields.put(motion, FOLLOW_REDUCED_MOTION.id(), FOLLOW_REDUCED_MOTION, followReducedMotion);
		JsonFields.put(motion, TRANSITION_BLUR.id(), TRANSITION_BLUR, transitionBlur);
		JsonFields.put(motion, GAMEPLAY_BLUR.id(), GAMEPLAY_BLUR, gameplayBlur);
		JsonFields.put(motion, GAMEPLAY_BLUR_STRENGTH.id(), GAMEPLAY_BLUR_STRENGTH, gameplayBlurStrength);
		JsonObject accessibility = JsonFields.child(root, "accessibility");
		JsonFields.put(accessibility, INTERFACE_SIZE.id(), INTERFACE_SIZE, interfaceSize);
		JsonFields.put(accessibility, HIGH_CONTRAST.id(), HIGH_CONTRAST, highContrast);
		JsonFields.put(JsonFields.child(root, "hud"), SHOW_WITH_DEBUG_SCREEN.id(), SHOW_WITH_DEBUG_SCREEN, showWithDebugScreen);
		JsonObject library = JsonFields.child(root, "library");
		library.add("favorites", JsonFields.array(favorites));
		library.add("recents", JsonFields.array(recents));
		JsonObject onboarding = JsonFields.child(root, "onboarding");
		onboarding.addProperty("completed", onboardingCompleted);
		onboarding.addProperty("hintShown", hintShown);
		if (activeLayout != null) {
			root.addProperty("activeLayout", activeLayout);
		}
		// The menu's look and view: written once something differs from Flint's defaults (or the file has them already).
		boolean menuDefaults = menuTheme.equals(com.flintclient.client.control.model.MenuTheme.FLINT) && !compactView && collapsed.isEmpty();
		if (!menuDefaults || root.has("menu")) {
			JsonObject menu = JsonFields.child(root, "menu");
			JsonObject theme = JsonFields.child(menu, "theme");
			theme.addProperty("accent", menuTheme.accentHex());
			theme.addProperty("surface", menuTheme.surface().id());
			theme.addProperty("roundness", menuTheme.roundness());
			theme.addProperty("dim", menuTheme.dim());
			menu.addProperty("compactView", compactView);
			menu.add("collapsed", JsonFields.array(collapsed));
		}
		store.markDirty();
		if (store.save()) {
			dirty = false;
			return true;
		}
		return false;
	}

	public boolean dirty() {
		return dirty;
	}

	/** Why the file couldn't be used as is (shown in the Control Center), or null. */
	public @Nullable String notice() {
		return store.notice();
	}

	/** True when changes can't be saved (a newer Flint wrote the file). */
	public boolean readOnly() {
		return store.readOnly();
	}

	public boolean soundsEnabled() {
		load();
		return sounds;
	}

	public void setSoundsEnabled(boolean value) {
		load();
		if (sounds != value) {
			sounds = value;
			dirty = true;
		}
	}

	/** Interface sound volume for playback, 0..1 (Minecraft's master and interface volumes apply on top). */
	public float soundVolume() {
		load();
		return volume / 100.0F;
	}

	/** The volume as the slider shows it: 0..100, in steps of 5 ({@link #VOLUME}). */
	public int soundVolumePercent() {
		load();
		return volume;
	}

	/** Sets the volume in percent, snapped to {@link #VOLUME}'s steps. */
	public void setSoundVolumePercent(int percent) {
		load();
		int clamped = VOLUME.clamp(percent);
		if (volume != clamped) {
			volume = clamped;
			dirty = true;
		}
	}

	public boolean animations() {
		load();
		return animations;
	}

	public void setAnimations(boolean value) {
		load();
		if (animations != value) {
			animations = value;
			dirty = true;
		}
	}

	/** The Control Center's edge smear while panels move (off with reduced motion anyway). */
	public boolean transitionBlur() {
		load();
		return transitionBlur;
	}

	public void setTransitionBlur(boolean value) {
		load();
		if (transitionBlur != value) {
			transitionBlur = value;
			dirty = true;
		}
	}

	/** The optional gameplay motion blur (off by default). */
	public boolean gameplayBlur() {
		load();
		return gameplayBlur;
	}

	public void setGameplayBlur(boolean value) {
		load();
		if (gameplayBlur != value) {
			gameplayBlur = value;
			dirty = true;
		}
	}

	/** Gameplay motion blur strength in percent, 5..100. */
	public int gameplayBlurStrength() {
		load();
		return gameplayBlurStrength;
	}

	public void setGameplayBlurStrength(int percent) {
		load();
		int clamped = GAMEPLAY_BLUR_STRENGTH.clamp(percent);
		if (gameplayBlurStrength != clamped) {
			gameplayBlurStrength = clamped;
			dirty = true;
		}
	}

	public boolean followMinecraftReducedMotion() {
		load();
		return followReducedMotion;
	}

	public void setFollowMinecraftReducedMotion(boolean value) {
		load();
		if (followReducedMotion != value) {
			followReducedMotion = value;
			dirty = true;
		}
	}

	/**
	 * Whether transitions should be instant: animations are off, or Minecraft's reduced motion (panorama speed
	 * 0) is on and the player asked Flint to follow it.
	 */
	public boolean instantMotion(boolean minecraftReducedMotion) {
		load();
		return !animations || followReducedMotion && minecraftReducedMotion;
	}

	public InterfaceSize interfaceSize() {
		load();
		return interfaceSize;
	}

	public void setInterfaceSize(InterfaceSize value) {
		load();
		if (interfaceSize != value) {
			interfaceSize = value;
			dirty = true;
		}
	}

	public boolean highContrast() {
		load();
		return highContrast;
	}

	public void setHighContrast(boolean value) {
		load();
		if (highContrast != value) {
			highContrast = value;
			dirty = true;
		}
	}

	public boolean showHudWithDebugScreen() {
		load();
		return showWithDebugScreen;
	}

	public void setShowHudWithDebugScreen(boolean value) {
		load();
		if (showWithDebugScreen != value) {
			showWithDebugScreen = value;
			dirty = true;
		}
	}

	/** Favourite module ids, in the order they were added (may include ids from a newer Flint). */
	public List<String> favorites() {
		load();
		return favorites;
	}

	public boolean isFavorite(String moduleId) {
		load();
		return favorites.contains(moduleId);
	}

	public void setFavorite(String moduleId, boolean favorite) {
		load();
		if (favorite == favorites.contains(moduleId)) {
			return;
		}
		List<String> next = new ArrayList<>(favorites);
		if (favorite) {
			next.add(moduleId);
		} else {
			next.remove(moduleId);
		}
		favorites = List.copyOf(next);
		dirty = true;
	}

	/** Flips a favourite; returns the new state. */
	public boolean toggleFavorite(String moduleId) {
		boolean favorite = !isFavorite(moduleId);
		setFavorite(moduleId, favorite);
		return favorite;
	}

	/** The most recently changed modules, newest first, at most {@value LibraryQuery#MAX_RECENTS}. */
	public List<String> recents() {
		load();
		return recents;
	}

	/** Remembers that the player changed {@code moduleId} (it moves to the front of the recents). */
	public void recordChange(String moduleId) {
		load();
		List<String> next = LibraryQuery.withRecent(recents, moduleId);
		if (!next.equals(recents)) {
			recents = next;
			dirty = true;
		}
	}

	public boolean onboardingCompleted() {
		load();
		return onboardingCompleted;
	}

	public void setOnboardingCompleted(boolean value) {
		load();
		if (onboardingCompleted != value) {
			onboardingCompleted = value;
			dirty = true;
		}
	}

	/** Whether the first-world "Press Right Shift" toast has been shown. */
	public boolean hintShown() {
		load();
		return hintShown;
	}

	public void setHintShown(boolean value) {
		load();
		if (hintShown != value) {
			hintShown = value;
			dirty = true;
		}
	}

	/** The Right Shift menu's theme (Flint's own until the player changes it). */
	public com.flintclient.client.control.model.MenuTheme menuTheme() {
		load();
		return menuTheme;
	}

	public void setMenuTheme(com.flintclient.client.control.model.MenuTheme value) {
		load();
		if (!menuTheme.equals(value)) {
			menuTheme = value;
			dirty = true;
		}
	}

	/** Whether the mods grid shows compact rows instead of cards. */
	public boolean compactView() {
		load();
		return compactView;
	}

	public void setCompactView(boolean value) {
		load();
		if (compactView != value) {
			compactView = value;
			dirty = true;
		}
	}

	/** Whether the settings section {@code key} ({@code "moduleId/Title"}) is folded away. */
	public boolean sectionCollapsed(String key) {
		load();
		return collapsed.contains(key);
	}

	public void setSectionCollapsed(String key, boolean value) {
		load();
		if (value == collapsed.contains(key) || key.isEmpty() || key.length() > 160) {
			return;
		}
		List<String> next = new ArrayList<>(collapsed);
		if (value) {
			if (next.size() >= 512) {
				next.removeFirst();
			}
			next.add(key);
		} else {
			next.remove(key);
		}
		collapsed = List.copyOf(next);
		dirty = true;
	}

	/** The id of the layout in use, or null when none was chosen yet. */
	public @Nullable String activeLayout() {
		load();
		return activeLayout;
	}

	public void setActiveLayout(String id) {
		load();
		if (!LayoutNames.validId(id)) {
			throw new IllegalArgumentException("not a layout id: " + id);
		}
		if (!id.equals(activeLayout)) {
			activeLayout = id;
			dirty = true;
		}
	}

	/** The file's fraction (0.6) as a valid percent (60); the default when missing or unusable. */
	private static int volumePercent(@Nullable JsonElement json) {
		if (json != null && json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) {
			double fraction = json.getAsDouble();
			if (Double.isFinite(fraction)) {
				return VOLUME.clampInt(fraction * 100.0);
			}
		}
		return VOLUME.defaultValue();
	}

	/** Non-empty, short, without repeats, at most {@code max}. */
	private static List<String> ids(List<String> values, int max) {
		List<String> result = new ArrayList<>();
		for (String value : values) {
			if (result.size() == max) {
				break;
			}
			if (!value.isEmpty() && value.length() <= MAX_ID_LENGTH && !result.contains(value)) {
				result.add(value);
			}
		}
		return List.copyOf(result);
	}
}
