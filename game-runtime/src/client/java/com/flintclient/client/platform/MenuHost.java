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

	/**
	 * Whether the Right Shift menu shows the game and the HUD live around its panel over a world: the HUD mods stay sharp (with
	 * sample values where they have nothing to show) and only the panel is frosted. 26.3 for now ({@code McCompat.MENU_PREVIEW}).
	 */
	public static boolean livePreview() {
		return com.flintclient.client.compat.McCompat.MENU_PREVIEW;
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

	// ---------- the main menu's pages (Discover, Friends) ----------

	/** This game's Minecraft version (the partner servers it can join). */
	public static String minecraftVersion() {
		return com.flintclient.client.util.FlintInfo.minecraftVersion();
	}

	/** Why the player can't play online now (Minecraft's own reason: a ban, parental controls), or null when they can. */
	public static @Nullable String multiplayerBlocked() {
		net.minecraft.network.chat.Component reason = com.flintclient.client.ui.screen.MultiplayerAccess.disabledReason(Minecraft.getInstance());
		return reason == null ? null : reason.getString();
	}

	/**
	 * Joins {@code address} (named {@code name}) through Minecraft's own connection screen, its errors and timeouts included,
	 * coming back to {@code parent} when it fails; the first multiplayer visit shows Minecraft's safety notice first, as its
	 * Multiplayer button does.
	 */
	public static void joinServer(Object parent, String name, String address) {
		Minecraft minecraft = Minecraft.getInstance();
		net.minecraft.client.gui.screens.Screen back = (net.minecraft.client.gui.screens.Screen) parent;
		if (!minecraft.options.skipMultiplayerWarning) {
			minecraft.gui.setScreen(new net.minecraft.client.gui.screens.multiplayer.SafetyScreen(back));
			return;
		}
		net.minecraft.client.multiplayer.ServerData data = new net.minecraft.client.multiplayer.ServerData(name, address,
			net.minecraft.client.multiplayer.ServerData.Type.OTHER);
		net.minecraft.client.gui.screens.ConnectScreen.startConnecting(back, minecraft,
			net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(address), data, false, null);
	}

	/** Whether this Minecraft has its own friends list (26.2 added it). */
	public static boolean minecraftFriends() {
		return com.flintclient.client.compat.McCompat.FRIENDS;
	}

	/** Opens Minecraft's own friends list over {@code parent} (after its online-options check), where it has one. */
	public static void openMinecraftFriends(Object parent) {
		if (com.flintclient.client.compat.McCompat.FRIENDS) {
			com.flintclient.client.compat.McCompat.openFriends(Minecraft.getInstance(), (net.minecraft.client.gui.screens.Screen) parent);
		}
	}

	// ---------- the in-game Library ----------

	/**
	 * Whether this game offers the in-game Library (the Right Shift tile, the Utilities row): started by a Flint app that has it,
	 * on a line with Instant Swap (its swap engine, so a mod added works once the Library closes). A newer Minecraft running a
	 * line's build keeps that line's answer.
	 */
	public static boolean libraryOffered() {
		return com.flintclient.client.module.social.InstantSwap.available();
	}

	/** Minecraft's Resource Packs screen over {@code parent} (Done applies the choice and comes back), or null. */
	public static @Nullable Object resourcePacksScreen(Object parent) {
		return PackScreens.resourcePacks(Minecraft.getInstance(), (net.minecraft.client.gui.screens.Screen) parent);
	}

	/** Whether the Library offers shader packs here (a line with a shader loader: Iris on 26.x and 1.21.x). */
	public static boolean shadersOffered() {
		return true;
	}

	/** Iris' shader pack screen over {@code parent} (by name: Iris is optional), or null without Iris. */
	public static @Nullable Object shaderPacksScreen(Object parent) {
		try {
			Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
			Object iris = api.getMethod("getInstance").invoke(null);
			return api.getMethod("openMainIrisScreenObj", Object.class).invoke(iris, parent);
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			return null;
		}
	}

	/** Opens Flint's own Discord sign-in page (the launcher checked it, and the game checked it again) in the browser. */
	public static void openTrusted(String url) {
		com.flintclient.client.compat.McCompat.openUri(url);
	}

	// ---------- the wardrobe (control/rshift/WardrobeScreen) ----------

	/**
	 * Draws the player as a whole 3D model in the pixel box ({@code x}, {@code y}, {@code w}, {@code h}) of the frame's canvas:
	 * wearing {@code skin} ({@code slim} its arm model; null: the account's own skin) and {@code cape} (null: none), turned
	 * {@code yaw} degrees from facing the viewer. False where this Minecraft can't draw that (the window shows a picture instead).
	 */
	public static boolean previewPlayer(Frame frame, @Nullable Texture skin, boolean slim, @Nullable Texture cape, int x, int y, int w, int h,
		float yaw, float capeLean) {
		return com.flintclient.client.compat.McCompat.previewAvatar(frame, skin, slim, cape, x, y, w, h, yaw, capeLean);
	}

	/**
	 * Whether this game has the wardrobe: 26.3 and every newer Minecraft (a 26.4 running this line's build too), and every line
	 * whose build has the 3D cosmetics renderer (its platform class, {@code cosmetics/platform/Mc263Cosmetics}); the other lines
	 * keep the Cosmetics window they had.
	 */
	public static boolean wardrobe() {
		if (com.flintclient.client.control.feature.CosmeticKinds.wardrobeOn(minecraftVersion())) {
			return true;
		}
		try {
			Class.forName("com.flintclient.client.cosmetics.platform.Mc263Cosmetics", false, MenuHost.class.getClassLoader());
			return true;
		} catch (ClassNotFoundException | LinkageError e) {
			return false;
		}
	}

	/**
	 * The wardrobe's Apply: the skin just uploaded ({@code skin}, a 64 × 64 texture) on the player's own model at once, until the
	 * next join. False where this Minecraft can't (the skin then shows after the next join).
	 */
	public static boolean wearOwnSkin(Texture skin, boolean slim) {
		return com.flintclient.client.compat.McCompat.wearOwnSkin(skin, slim);
	}
}
