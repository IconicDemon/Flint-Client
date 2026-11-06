package com.flintclient.client.platform;

import com.flintclient.client.control.ControlCenter;
import com.flintclient.client.control.fx.MotionBlur;
import com.flintclient.client.control.hud.render.HudRenderer;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.mods.PlayerMods.TierTagger;
import com.flintclient.client.control.model.mods.UtilityMods.PackOrganizer;
import com.flintclient.client.control.platform.ControlKeys;
import com.flintclient.client.control.platform.Mc263ControlPlatform;
import com.flintclient.client.control.rshift.PacksPanel;
import com.flintclient.client.control.rshift.TierTaggerPanel;
import com.flintclient.client.control.store.ControlSettings;
import com.flintclient.client.ui.kit.Node;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.jspecify.annotations.Nullable;

/**
 * What the Right Shift menu's screens ask of the game around them: the open key (pressed, released, held, its name), the
 * open and close sounds, the menu blur, the gameplay motion blur, and reporting a screen that failed. On 26.x and 1.21.x
 * these are Flint's Control Center ({@code control.ControlCenter}) and Minecraft's key mappings. Platform class (see {@link Clock}). Render thread.
 */
public final class MenuHost {
	private MenuHost() {
	}

	/** {@code key} is the menu's open key (a fresh press). */
	public static boolean isOpenKey(KeyInput key) {
		return ControlCenter.isOpenKey(key.event());
	}

	/** Mouse {@code button} is the menu's open key. */
	public static boolean isOpenButton(double x, double y, int button) {
		return ControlCenter.isOpenKey(new net.minecraft.client.input.MouseButtonEvent(x, y, new MouseButtonInfo(button, 0)));
	}

	/** {@code key} being let go is the open key's (pressed or not). */
	public static boolean isOpenKeyRelease(KeyInput key) {
		ControlKeys keys = ControlCenter.keys();
		KeyEvent event = key.event();
		return keys != null && keys.controlCenter().matches(event);
	}

	/** Mouse {@code button} being let go is the open key's. */
	public static boolean isOpenButtonRelease(double x, double y, int button) {
		ControlKeys keys = ControlCenter.keys();
		net.minecraft.client.input.MouseButtonEvent event = new net.minecraft.client.input.MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
		return keys != null && keys.controlCenter().matchesMouse(event);
	}

	/** The open key is held down right now. */
	public static boolean openKeyHeld() {
		ControlKeys keys = ControlCenter.keys();
		return keys != null && Mc263ControlPlatform.physicallyDown(keys.controlCenter());
	}

	/** An object that changes whenever the open key is rebound (to remake its name only then), or null without one. */
	public static @Nullable Object openKeyBinding() {
		ControlKeys keys = ControlCenter.keys();
		return keys == null ? null : Mc263ControlPlatform.boundKey(keys.controlCenter());
	}

	/** The open key's name as Minecraft's Controls show it ("Right Shift"). */
	public static String openKeyName() {
		ControlKeys keys = ControlCenter.keys();
		return keys == null ? "Right Shift" : Mc263ControlPlatform.keyName(keys.controlCenter()).getString();
	}

	/** Whether the game plays the menu's open and close sounds (their settings show only then). */
	public static boolean menuSounds() {
		return true;
	}

	public static void playOpenSound(float volume) {
		ControlCenter.playOpenSound(volume);
	}

	public static void playCloseSound(float volume) {
		ControlCenter.playCloseSound(volume);
	}

	/** A daily reward claimed: a bright chime over a soft level-up, with an orb's sparkle. */
	public static void playClaimSound() {
		var sounds = Minecraft.getInstance().getSoundManager();
		sounds.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME, 1.25F, 1.0F));
		sounds.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, 1.5F, 0.35F));
		sounds.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, 1.8F, 0.45F));
	}

	/** Minecraft blurs what is behind its menus (its menu blur option is up). */
	public static boolean menuBlurOn() {
		return Minecraft.getInstance().options.getMenuBackgroundBlurriness() >= 1.0F;
	}

	/** Pushes the saved gameplay motion blur choice (off by default) and strength to the effect; after changing them. */
	public static void applyGameplayBlur(ControlSettings saved) {
		MotionBlur.setStrength(saved.gameplayBlurStrength() / 100.0F);
		MotionBlur.setEnabled(saved.gameplayBlur());
	}

	/**
	 * What a mod's settings page shows under its settings, or null: Pack Organizer lists the packs themselves, Tier Tagger
	 * opens the TierTagger mod's own screens.
	 */
	public static @Nullable Node extraPanel(String moduleId) {
		if (moduleId.equals(PackOrganizer.ID)) {
			return new PacksPanel();
		}
		return moduleId.equals(TierTagger.ID) ? new TierTaggerPanel() : null;
	}

	/** The menu key's saved name ({@code key.keyboard.right.shift}), or "" when unbound. */
	public static String menuKey() {
		return Mc263ControlPlatform.menuKey();
	}

	/** Rebinds the menu key ("" unbinds it) and saves the game's options, as Controls does. */
	public static void setMenuKey(String name) {
		Mc263ControlPlatform.setMenuKey(name);
	}

	/** The HUD editor key's saved name, or "" when unbound. */
	public static String hudEditorKey() {
		return Mc263ControlPlatform.hudEditorKey();
	}

	public static void setHudEditorKey(String name) {
		Mc263ControlPlatform.setHudEditorKey(name);
	}

	/** The game's own key mappings bound to {@code keyName} (only those that work in screens with {@code inScreens}), by name. */
	public static List<String> mappingsUsing(String keyName, boolean inScreens) {
		return Mc263ControlPlatform.mappingsUsing(keyName, inScreens);
	}

	/** Why a HUD module's label setting does nothing in {@code theme} (its view has no label there), or null. */
	public static @Nullable String noLabelReason(String moduleId, HudThemeId theme) {
		return HudRenderer.noLabelReason(moduleId, theme);
	}

	/** Why a HUD module's padding does nothing (it has no panel around it), or null. */
	public static @Nullable String noPaddingReason(String moduleId) {
		return HudRenderer.noPaddingReason(moduleId);
	}

	/** Why a HUD module's accent colour does nothing (it has no labels or highlights), or null. */
	public static @Nullable String noAccentReason(String moduleId) {
		return HudRenderer.noAccentReason(moduleId);
	}

	/**
	 * Opens one of the game's folders in the system's file browser ({@code screenshots} or {@code resourcepacks}, the menu's
	 * General tab), made first if it doesn't exist yet.
	 */
	public static void openFolder(String which) {
		String name = switch (which) {
			case "screenshots" -> "screenshots";
			case "resourcepacks" -> "resourcepacks";
			default -> null;
		};
		if (name == null) {
			return;
		}
		java.io.File folder = new java.io.File(Minecraft.getInstance().gameDirectory, name);
		if (!folder.isDirectory() && !folder.mkdirs()) {
			return;
		}
		com.flintclient.client.compat.McCompat.openPath(folder.toPath());
	}

	/**
	 * Opens a web address after Minecraft's own link confirmation (the one a chat link gets), over {@code parent} (an engine
	 * screen), which comes back after it.
	 */
	public static void openLink(Object parent, String url) {
		net.minecraft.client.gui.screens.ConfirmLinkScreen.confirmLinkNow((net.minecraft.client.gui.screens.Screen) parent, java.net.URI.create(url));
	}

	// ---------- the HUD page ----------

	/** Shows {@code screen} (an engine screen object, or null for the game). */
	public static void show(@Nullable Object screen) {
		Minecraft.getInstance().gui.setScreen((net.minecraft.client.gui.screens.Screen) screen);
	}

	/** Opens the Right Shift window over {@code parent} (the HUD page), which it returns to. */
	public static void openWindow(Object parent) {
		ControlCenter.openWindow((net.minecraft.client.gui.screens.Screen) parent);
	}

	/** Minecraft's Options screen, returning to {@code parent}. */
	public static Object optionsScreen(Object parent) {
		return com.flintclient.client.compat.McCompat.optionsScreen((net.minecraft.client.gui.screens.Screen) parent, Minecraft.getInstance().options);
	}

	/** Flint's screenshots screen returning to {@code parent}, or null where there is none (the folder opens instead). */
	public static @Nullable Object screenshotsScreen(Object parent) {
		return new com.flintclient.client.ui.screen.ScreenshotsScreen((net.minecraft.client.gui.screens.Screen) parent);
	}

	/** Whether this line has Flint's pack organizer (the HUD page's Resource Packs tile opens it). */
	public static boolean packOrganizer() {
		return true;
	}

	/** The game's own resource packs screen returning to {@code parent}, where the pack organizer isn't. */
	public static @Nullable Object packsScreen(Object parent) {
		return null;
	}

	/** Opens the game's folder in the system's file browser. */
	public static void openGameFolder() {
		com.flintclient.client.compat.McCompat.openPath(Minecraft.getInstance().gameDirectory.toPath());
	}

	/** A menu screen threw while building, drawing or handling input: reported once, and the open path stays off for the session. */
	public static void screenFailed(Throwable error) {
		ControlCenter.screenFailed(error);
	}
}
