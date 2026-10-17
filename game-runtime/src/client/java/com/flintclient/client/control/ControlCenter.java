package com.flintclient.client.control;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.cc.HintGate;
import com.flintclient.client.control.editor.HudEditor;
import com.flintclient.client.control.fx.MotionBlur;
import com.flintclient.client.control.hud.CpsCounter;
import com.flintclient.client.control.hud.render.HudRenderer;
import com.flintclient.client.control.menu.MenuScreen;
import com.flintclient.client.control.platform.ControlKeys;
import com.flintclient.client.control.platform.ControlPlatform;
import com.flintclient.client.control.platform.HookGuard;
import com.flintclient.client.control.platform.Mc263ControlPlatform;
import com.flintclient.client.control.platform.ReleaseLatch;
import com.flintclient.client.control.platform.UiSounds;
import com.flintclient.client.control.store.ControlSettings;
import com.mojang.blaze3d.audio.SoundBuffer;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Flint Control Center's entry points: what the mixins call from Minecraft's ticks, key handling, HUD, mouse
 * input and sound engine, and what Flint's screens call to open it.
 *
 * <p>Each hook path is fault-isolated: the first exception one throws is logged once and turns that path off
 * for the session ({@link HookGuard}), leaving Minecraft and the other paths running. The paths are
 * "keybinds", "hud", "effects", "cps", "open", "sound" and "hint". Per-frame paths ({@link #renderHud},
 * {@link #hideVanillaEffects}) allocate nothing. Render thread only.
 */
public final class ControlCenter {
	private static final ControlPlatform PLATFORM = new Mc263ControlPlatform(ControlCenter::keys);
	private static final CpsCounter CPS = new CpsCounter();
	private static final HookGuard KEYBINDS = new HookGuard("keybinds", ControlCenter::turnedOff);
	private static final HookGuard HUD = new HookGuard("hud", ControlCenter::turnedOff);
	private static final HookGuard EFFECTS = new HookGuard("effects", ControlCenter::turnedOff);
	private static final HookGuard CLICKS = new HookGuard("cps", ControlCenter::turnedOff);
	private static final HookGuard OPEN = new HookGuard("open", ControlCenter::turnedOff);
	private static final HookGuard SOUND = new HookGuard("sound", ControlCenter::turnedOff);
	private static final HookGuard HINT = new HookGuard("hint", ControlCenter::turnedOff);
	/** Flint's own toast id: a hint lasts a little longer than vanilla's 5 s notices, and is never merged with them. */
	private static final SystemToast.SystemToastId HINT_TOAST = new SystemToast.SystemToastId(8000L);
	private static final HintGate HINT_GATE = new HintGate();
	// Armed when something opens or the screen changes, as the key that did it may still be held.
	private static final ReleaseLatch OPEN_KEY_HELD = new ReleaseLatch();
	private static final ReleaseLatch EDITOR_KEY_HELD = new ReleaseLatch();

	private static @Nullable ControlKeys keys;
	// Only compared by identity, to see the screen change; at most one closed screen is kept a tick longer.
	private static @Nullable Screen lastScreen;

	private ControlCenter() {
	}

	/**
	 * From the start of {@code Options.load}: Minecraft's key mappings with Flint's added. If Flint's
	 * couldn't be created, Minecraft's come back unchanged and the key bindings stay off.
	 */
	public static KeyMapping[] withControlKeys(KeyMapping[] keyMappings) {
		// Beta: with the Right Shift customization off, Flint registers no key bindings at all (see BetaFeatures).
		if (!KEYBINDS.on() || !BetaFeatures.RIGHT_SHIFT_CUSTOMIZATION) {
			return keyMappings;
		}
		try {
			ControlKeys created = ControlKeys.get();
			KeyMapping[] merged = created.addTo(keyMappings);
			keys = created;
			return merged;
		} catch (RuntimeException e) {
			KEYBINDS.fail(e);
		} catch (ExceptionInInitializerError e) {
			// A failed static initialiser leaves the class unusable; keys stays null so it's never touched again.
			KEYBINDS.fail(new IllegalStateException("Flint's key bindings couldn't be created", e));
		}
		return keyMappings;
	}

	/**
	 * From the start of every client tick (20 a second, whatever screen is open), before {@link
	 * #onKeybindTick}: whenever the screen has changed (the Control Center closing on its own key, say), a
	 * key held through the change is ignored until it's let go, so its repeats can't reopen what just
	 * closed, in game or on the title screen. Looks at the keys only after a change or while waiting. Also
	 * shows the first-world hint when it is due ({@link #tickHint}).
	 */
	public static void onClientTick() {
		MotionBlur.tick();
		com.flintclient.client.control.platform.Mc263Features.tick();
		com.flintclient.client.control.platform.Mc263Overlay.tick();
		// Lets the HUD stop recording frame times soon after nothing shows FPS any more (F1, title screen).
		if (HUD.on()) {
			try {
				HudRenderer.tick();
			} catch (RuntimeException e) {
				HUD.fail(e);
			}
		}
		tickHint();
		ControlKeys mappings = keys;
		if (mappings == null || !KEYBINDS.on()) {
			return;
		}
		try {
			Screen screen = Mc263ControlPlatform.currentScreen();
			if (screen != lastScreen) {
				lastScreen = screen;
				holdKeys();
			}
			watchRelease(OPEN_KEY_HELD, mappings.controlCenter());
			watchRelease(EDITOR_KEY_HELD, mappings.hudEditor());
		} catch (RuntimeException e) {
			KEYBINDS.fail(e);
		}
	}

	/**
	 * From the start of {@code Minecraft.handleKeybinds}, once per tick while no screen or overlay is open:
	 * a press of the Control Center key opens it, else one of the HUD editor key opens that. Every queued
	 * click is consumed, so a burst of them opens it once, and a key still held from the last open or close
	 * does nothing until it's let go (Minecraft clicks mappings on key repeats too).
	 */
	public static void onKeybindTick() {
		ControlKeys mappings = keys;
		if (mappings == null || !KEYBINDS.on()) {
			return;
		}
		try {
			boolean open = takeClicks(mappings.controlCenter(), OPEN_KEY_HELD);
			boolean editor = takeClicks(mappings.hudEditor(), EDITOR_KEY_HELD);
			if (open) {
				requestOpen();
			} else if (editor) {
				requestHudEditor();
			}
		} catch (RuntimeException e) {
			KEYBINDS.fail(e);
		}
	}

	/**
	 * Whether a key press is the Control Center key, for screens that open it themselves (the title screen).
	 * Screens are sent key repeats as presses, so a key still held since the Control Center closed (or since
	 * any screen change) doesn't count until it's let go; see {@link #onClientTick}.
	 */
	public static boolean isOpenKey(KeyEvent event) {
		ControlKeys mappings = keys;
		if (mappings == null || !KEYBINDS.on()) {
			return false;
		}
		try {
			return mappings.controlCenter().matches(event) && !OPEN_KEY_HELD.holding();
		} catch (RuntimeException e) {
			KEYBINDS.fail(e);
			return false;
		}
	}

	/** {@link #isOpenKey(KeyEvent)} for a mouse button, as the key can be bound to one. */
	public static boolean isOpenKey(MouseButtonEvent event) {
		ControlKeys mappings = keys;
		if (mappings == null || !KEYBINDS.on()) {
			return false;
		}
		try {
			return mappings.controlCenter().matchesMouse(event) && !OPEN_KEY_HELD.holding();
		} catch (RuntimeException e) {
			KEYBINDS.fail(e);
			return false;
		}
	}

	/** Right Shift over the game: in a world the HUD editor with its hub (EDIT FLINT MODS, WIDGETS); elsewhere the menu. */
	public static void requestOpen() {
		requestOpen(null);
	}

	/**
	 * Opens Flint's Right Shift experience. Over a world (no {@code parent}) that is the HUD editor with Flint's hub in the
	 * middle ({@link HudEditorScreen}), from which the module menu opens; otherwise, or when the editor is off this session,
	 * the menu itself ({@link MenuScreen}), and closing it returns to {@code parent} (the title screen, say).
	 */
	public static void requestOpen(@Nullable Screen parent) {
		if (!BetaFeatures.RIGHT_SHIFT_CUSTOMIZATION) {
			return;
		}
		if (parent == null && ControlState.inWorld() && HudEditor.available()) {
			requestHudEditor();
			return;
		}
		openScreen(parent);
	}

	/** Opens the module menu ({@link MenuScreen}) itself; closing it returns to {@code parent}. */
	public static void openMenu(@Nullable Screen parent) {
		if (!BetaFeatures.RIGHT_SHIFT_CUSTOMIZATION) {
			return;
		}
		openScreen(parent);
	}

	/**
	 * Opens the menu on {@code moduleId}'s settings page (from the HUD editor's "All settings"); a module whose page
	 * isn't built yet opens the menu on its grid.
	 */
	public static void openModuleSettings(@Nullable Screen parent, String moduleId) {
		if (!BetaFeatures.RIGHT_SHIFT_CUSTOMIZATION) {
			return;
		}
		MenuScreen.showModule(moduleId);
		openScreen(parent);
	}

	private static void openScreen(@Nullable Screen parent) {
		holdKeys();
		if (!OPEN.on()) {
			return;
		}
		try {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.gui.screen() instanceof MenuScreen) {
				return;
			}
			minecraft.gui.setScreen(new MenuScreen(parent));
		} catch (RuntimeException e) {
			OPEN.fail(e);
		}
	}

	/**
	 * From {@link MenuScreen}, when building, drawing or handling input threw: reported once and the "open" path
	 * turned off for the session, so the key can't keep reopening a screen that fails. The screen itself steps aside.
	 */
	public static void screenFailed(RuntimeException error) {
		OPEN.fail(error);
	}

	/** Opens the HUD editor over the game; it needs a world, so elsewhere nothing happens. */
	public static void requestHudEditor() {
		if (!BetaFeatures.RIGHT_SHIFT_CUSTOMIZATION) {
			return;
		}
		holdKeys();
		if (!OPEN.on() || !ControlState.inWorld() || !HudEditor.available()) {
			return;
		}
		try {
			Minecraft minecraft = Minecraft.getInstance();
			if (!(minecraft.gui.screen() instanceof HudEditorScreen)) {
				minecraft.gui.setScreen(new HudEditorScreen(null));
			}
		} catch (RuntimeException e) {
			OPEN.fail(e);
		}
	}

	/**
	 * From {@code Hud.extractRenderState}, every frame the HUD is drawn, just before chat: draws Flint's HUD.
	 * Not called while the HUD is hidden (F1) or under the loading screen. Must not allocate.
	 */
	public static void renderHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (!HUD.on()) {
			return;
		}
		try {
			drawHud(graphics, deltaTracker);
		} catch (RuntimeException e) {
			HUD.fail(e);
		}
	}

	/** From {@code Hud.extractRenderState}, every frame: whether vanilla's effect icons should be left out. */
	public static boolean hideVanillaEffects() {
		if (!EFFECTS.on()) {
			return false;
		}
		try {
			return effectsModuleHidesVanilla();
		} catch (RuntimeException e) {
			EFFECTS.fail(e);
			return false;
		}
	}

	/**
	 * From the mouse hook: a click (a press) of {@code button}, numbered as {@code InputConstants} numbers
	 * them, that reached gameplay. Left and right clicks made in a world are counted for CPS.
	 */
	public static void onClick(int button) {
		if (!CLICKS.on()) {
			return;
		}
		int counted = button == InputConstants.MOUSE_BUTTON_LEFT ? CpsCounter.LEFT : button == InputConstants.MOUSE_BUTTON_RIGHT ? CpsCounter.RIGHT : -1;
		if (counted < 0) {
			return;
		}
		try {
			if (PLATFORM.gameplayHasInput()) {
				CPS.press(counted, nowMillis());
			}
		} catch (RuntimeException e) {
			CLICKS.fail(e);
		}
	}

	/** Recent clicks, for the CPS module: {@code cps().countLastSecond(CpsCounter.LEFT, nowMillis())}. */
	public static CpsCounter cps() {
		return CPS;
	}

	/** The clock {@link #cps()} is fed from, in milliseconds. */
	public static long nowMillis() {
		return System.nanoTime() / 1_000_000L;
	}

	/** Flint's key bindings, or null before options load or if they couldn't be created. */
	public static @Nullable ControlKeys keys() {
		return keys;
	}

	/** Minecraft-facing state and services for the Control Center screens and HUD. */
	public static ControlPlatform platform() {
		return PLATFORM;
	}

	/**
	 * Plays the open sound at the Control Center's sound volume (0 to 1; the caller checks the sound setting).
	 * The one way to play it: a failure here turns the sounds off instead of reaching the screen.
	 */
	public static void playOpenSound(float volume) {
		if (!SOUND.on()) {
			return;
		}
		try {
			PLATFORM.playOpenSound(volume);
		} catch (RuntimeException e) {
			SOUND.fail(e);
		}
	}

	/** Plays the close sound, as {@link #playOpenSound} does the open one. */
	public static void playCloseSound(float volume) {
		if (!SOUND.on()) {
			return;
		}
		try {
			PLATFORM.playCloseSound(volume);
		} catch (RuntimeException e) {
			SOUND.fail(e);
		}
	}

	/**
	 * From {@code SoundBufferLibrary.getCompleteBuffer}, when the sound engine needs the data of a file in
	 * Flint's namespace: the samples of a generated sound, or null to leave the file to Minecraft (any other
	 * file, or the sounds have failed and are off).
	 */
	public static @Nullable CompletableFuture<SoundBuffer> soundBuffer(Identifier file) {
		if (!SOUND.on()) {
			return null;
		}
		try {
			return UiSounds.buffer(file);
		} catch (RuntimeException e) {
			SOUND.fail(e);
			return null;
		}
	}

	/**
	 * The first-world hint: once, on the first tick with the player in a world and nothing else on screen (so
	 * never over a loading screen), a Minecraft toast "Flint Control Center · Press Right Shift to customise
	 * your HUD" with the key actually bound; then it is remembered as shown and the settings are saved once.
	 * Each tick costs a field read once it has been dealt with. A toast never takes input from the game.
	 */
	private static void tickHint() {
		// Nothing to advertise while the Right Shift customization is off.
		if (!BetaFeatures.RIGHT_SHIFT_CUSTOMIZATION || HINT_GATE.settled() || !HINT.on()) {
			return;
		}
		try {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.level == null || minecraft.player == null || minecraft.gui.screen() != null || minecraft.gui.overlay() != null) {
				return;
			}
			ControlKeys mappings = keys;
			boolean bound = mappings != null && KEYBINDS.on() && !mappings.controlCenter().isUnbound();
			ControlSettings settings = ControlState.settings();
			if (HINT_GATE.poll(settings.hintShown(), true, bound) && mappings != null) {
				SystemToast.add(minecraft.gui.toastManager(), HINT_TOAST, Component.translatable("flintclient.hint.title"),
					Component.translatable("flintclient.hint.body", Mc263ControlPlatform.keyName(mappings.controlCenter())));
				settings.setHintShown(true);
				// Saved now, outside the usual moments (closing a screen, a layout action): the hint shows to players
				// who may never open the Control Center, so waiting for a close could show it again every launch.
				settings.save();
			}
		} catch (RuntimeException e) {
			HINT.fail(e);
		}
	}

	/** Consumes every queued click of {@code mapping} and answers whether they should act. */
	private static boolean takeClicks(KeyMapping mapping, ReleaseLatch held) {
		boolean clicked = false;
		while (mapping.consumeClick()) {
			clicked = true;
		}
		// An unbound mapping is clicked by any key Minecraft can't identify (scancode 0), so it never acts.
		return clicked && !mapping.isUnbound() && !held.holding();
	}

	private static void watchRelease(ReleaseLatch latch, KeyMapping mapping) {
		if (latch.holding()) {
			latch.observe(Mc263ControlPlatform.physicallyDown(mapping));
		}
	}

	// Both keys, since either may be the one still held; a key that isn't is let go at once by watchRelease.
	private static void holdKeys() {
		OPEN_KEY_HELD.arm();
		EDITOR_KEY_HELD.arm();
	}

	private static void drawHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		com.flintclient.client.control.platform.Mc263Overlay.render(graphics, deltaTracker);
		HudRenderer.render(graphics, deltaTracker);
	}

	private static boolean effectsModuleHidesVanilla() {
		return HudRenderer.hideVanillaEffects();
	}

	private static void turnedOff(String path, RuntimeException error) {
		FlintClient.LOGGER.warn("Flint Control Center: the {} hook failed; it's off for the rest of this session", path, error);
	}
}
