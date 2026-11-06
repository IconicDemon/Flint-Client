package com.flintclient.client.platform;

import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.store.ControlSettings;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.legacy.Flint;
import com.flintclient.legacy.shared.KeyNames189;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.client.resource.language.I18n;
import org.apache.logging.log4j.LogManager;
import org.jspecify.annotations.Nullable;
import org.lwjgl.Sys;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * What the Right Shift menu's screens ask of the game around them, on Minecraft 1.8.9: the open key (Flint's own key binding,
 * {@code Flint.menuKey}), the menu and HUD editor bindings, folders, and reporting a screen that failed. 1.8.9 has no menu
 * blur, no generated open and close sounds and no gameplay motion blur of the 26.x kind (its Motion Blur mod reads its own
 * settings). Platform class (see {@link Clock}). Render thread.
 */
public final class MenuHost {
	/** Changes whenever the open key is rebound, so the menu remakes its name only then. */
	private static Object bindingToken = new Object();
	private static int boundCode = Integer.MIN_VALUE;
	private static boolean failed;

	private MenuHost() {
	}

	private static @Nullable KeyBinding menuBinding() {
		return Flint.ready() ? Flint.menuKey() : null;
	}

	private static int openCode() {
		KeyBinding binding = menuBinding();
		return binding == null ? 0 : binding.getKeyCode();
	}

	/** {@code key} is the menu's open key (a fresh press). */
	public static boolean isOpenKey(KeyInput key) {
		int code = openCode();
		return !failed && code > 0 && key.key() == code;
	}

	/** Mouse {@code button} is the menu's open key (1.8.9 keeps a mouse button as its number less 100). */
	public static boolean isOpenButton(double x, double y, int button) {
		int code = openCode();
		return !failed && code < 0 && button == code + 100;
	}

	/** {@code key} being let go is the open key's. */
	public static boolean isOpenKeyRelease(KeyInput key) {
		int code = openCode();
		return code > 0 && key.key() == code;
	}

	public static boolean isOpenButtonRelease(double x, double y, int button) {
		int code = openCode();
		return code < 0 && button == code + 100;
	}

	/** The open key is held down right now. */
	public static boolean openKeyHeld() {
		int code = openCode();
		return code > 0 ? Keyboard.isKeyDown(code) : code < 0 && Mouse.isButtonDown(code + 100);
	}

	/** An object that changes whenever the open key is rebound, or null without one. */
	public static @Nullable Object openKeyBinding() {
		if (menuBinding() == null) {
			return null;
		}
		int code = openCode();
		if (code != boundCode) {
			boundCode = code;
			bindingToken = new Object();
		}
		return bindingToken;
	}

	/** The open key's name ("Right Shift"). */
	public static String openKeyName() {
		String name = KeyNames189.name(openCode());
		return name.isEmpty() ? "None" : Keys.displayName(name);
	}

	/** 1.8.9 plays no open and close sounds (the menu's own are generated for newer Minecraft's sound engine), so their settings hide. */
	public static boolean menuSounds() {
		return false;
	}

	public static void playOpenSound(float volume) {
	}

	public static void playCloseSound(float volume) {
	}

	/** A daily reward claimed: 1.8.9's level-up, bright, with an orb's sparkle (it has no amethyst). */
	public static void playClaimSound() {
		var sounds = Minecraft.getInstance().getSoundManager();
		sounds.play(net.minecraft.client.sound.instance.SimpleSoundInstance.of(new net.minecraft.resource.Identifier("random.levelup"), 1.5F));
		sounds.play(net.minecraft.client.sound.instance.SimpleSoundInstance.of(new net.minecraft.resource.Identifier("random.orb"), 1.8F));
	}

	/** 1.8.9 has no menu blur. */
	public static boolean menuBlurOn() {
		return false;
	}

	/** 1.8.9's Motion Blur mod reads its own settings every frame: nothing to push. */
	public static void applyGameplayBlur(ControlSettings saved) {
	}

	/** No mod 1.8.9 runs has a panel under its settings. */
	public static @Nullable Node extraPanel(String moduleId) {
		return null;
	}

	/** The menu key's saved name ({@code key.keyboard.right.shift}), or "" when unbound. */
	public static String menuKey() {
		return KeyNames189.name(openCode());
	}

	/** Rebinds the menu key ("" unbinds it) and saves the game's options, as Controls does. */
	public static void setMenuKey(String name) {
		KeyBinding binding = menuBinding();
		if (binding == null) {
			return;
		}
		int code = KeyNames189.code(name);
		if (code != binding.getKeyCode()) {
			binding.setKeyCode(code);
			KeyBinding.resetMapping();
			Minecraft mc = Minecraft.getInstance();
			if (mc.options != null) {
				mc.options.save();
			}
		}
	}

	/** 1.8.9 opens the HUD editor from the menu only (no key of its own). */
	public static String hudEditorKey() {
		return "";
	}

	public static void setHudEditorKey(String name) {
	}

	/** The game's own key bindings bound to {@code keyName}, by name. */
	public static List<String> mappingsUsing(String keyName, boolean inScreens) {
		List<String> out = new ArrayList<>();
		int code = KeyNames189.code(keyName);
		Minecraft mc = Minecraft.getInstance();
		if (code == 0 || mc.options == null) {
			return out;
		}
		for (KeyBinding binding : mc.options.keyBindings) {
			if (binding.getKeyCode() == code && binding != menuBinding() && !Flint.KEY_CATEGORY.equals(binding.getCategory())) {
				out.add(I18n.translate(binding.getName()));
			}
		}
		return out;
	}

	public static @Nullable String noLabelReason(String moduleId, HudThemeId theme) {
		return com.flintclient.client.control.hud.render.HudRenderer.noLabelReason(moduleId, theme);
	}

	public static @Nullable String noPaddingReason(String moduleId) {
		return com.flintclient.client.control.hud.render.HudRenderer.noPaddingReason(moduleId);
	}

	public static @Nullable String noAccentReason(String moduleId) {
		return com.flintclient.client.control.hud.render.HudRenderer.noAccentReason(moduleId);
	}

	/** The id Minecraft's chat gives its link confirmation. */
	private static final int LINK_CONFIRM = 31_102_009;

	/**
	 * Opens a web address after Minecraft's own link confirmation (the one a chat link gets: Open, Copy to Clipboard, Cancel),
	 * over {@code parent} (a 1.8.9 screen, or null for the game), which comes back after it; Escape is Cancel. The links are
	 * Flint's own (the Flint Store), so it asks as for a trusted link, without the warning about people you don't trust.
	 */
	public static void openLink(@Nullable Object parent, String url) {
		net.minecraft.client.gui.screen.Screen back = parent instanceof net.minecraft.client.gui.screen.Screen screen ? screen : null;
		ConfirmLink confirm = new ConfirmLink((open, id) -> {
			if (open) {
				Sys.openURL(url);
			}
			show(back);
		}, url);
		confirm.noWarning();
		show(confirm);
	}

	/** Minecraft's link confirmation, with Escape as Cancel (Minecraft's own would close every screen). */
	private static final class ConfirmLink extends net.minecraft.client.gui.screen.game.ConfirmChatLinkScreen {
		private final net.minecraft.client.gui.screen.ConfirmationListener answer;

		ConfirmLink(net.minecraft.client.gui.screen.ConfirmationListener answer, String url) {
			super(answer, url, LINK_CONFIRM, true);
			this.answer = answer;
		}

		@Override
		protected void keyPressed(char chr, int key) {
			if (key == Keyboard.KEY_ESCAPE) {
				answer.confirmResult(false, LINK_CONFIRM);
			} else {
				super.keyPressed(chr, key);
			}
		}
	}

	// ---------- the HUD page ----------

	/** Shows {@code screen} (a 1.8.9 screen, or null for the game). */
	public static void show(@Nullable Object screen) {
		Minecraft.getInstance().openScreen((net.minecraft.client.gui.screen.Screen) screen);
	}

	/** Opens the Right Shift window over {@code parent} (the HUD page), which it returns to. */
	public static void openWindow(Object parent) {
		show(new com.flintclient.client.control.rshift.ModMenuScreen(parent));
	}

	/** Minecraft's Options screen, returning to {@code parent}. */
	public static Object optionsScreen(Object parent) {
		return new net.minecraft.client.gui.screen.menu.options.OptionsScreen((net.minecraft.client.gui.screen.Screen) parent,
			Minecraft.getInstance().options);
	}

	/** 1.8.9 has no screenshots screen of Flint's: the HUD page's tile opens the folder. */
	public static @Nullable Object screenshotsScreen(Object parent) {
		return null;
	}

	/** 1.8.9 has no pack organizer: the HUD page's Resource Packs tile opens Minecraft's own screen. */
	public static boolean packOrganizer() {
		return false;
	}

	public static @Nullable Object packsScreen(Object parent) {
		return new net.minecraft.client.gui.screen.menu.resourcepacks.ResourcePacksScreen((net.minecraft.client.gui.screen.Screen) parent);
	}

	/** Opens the game's folder in the system's file browser. */
	public static void openGameFolder() {
		Sys.openURL(Minecraft.getInstance().gameDir.toURI().toString());
	}

	/** Opens one of the game's folders ({@code screenshots} or {@code resourcepacks}) in the file browser, made first if missing. */
	public static void openFolder(String which) {
		if (!which.equals("screenshots") && !which.equals("resourcepacks")) {
			return;
		}
		File folder = new File(Minecraft.getInstance().gameDir, which);
		if (!folder.isDirectory() && !folder.mkdirs()) {
			return;
		}
		Sys.openURL(folder.toURI().toString());
	}

	/** A menu screen threw while building, drawing or handling input: logged once, and the menu stays shut for the session. */
	public static void screenFailed(Throwable error) {
		if (!failed) {
			failed = true;
			LogManager.getLogger("Flint").error("Flint's menu failed and is switched off for this session", error);
		}
	}

	/** The menu failed this session (Right Shift then leaves it shut). */
	public static boolean failed() {
		return failed;
	}
}
