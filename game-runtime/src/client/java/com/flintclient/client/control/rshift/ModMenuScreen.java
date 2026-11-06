package com.flintclient.client.control.rshift;

import com.flintclient.client.FlintLog;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.model.GameOptions;
import com.flintclient.client.control.model.ToggleKeyLogic;
import com.flintclient.client.control.store.ControlSettings;
import com.flintclient.client.platform.Clock;
import com.flintclient.client.platform.Frame;
import com.flintclient.client.platform.KeyInput;
import com.flintclient.client.platform.MenuHost;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.ui.FlintFrame;
import com.flintclient.client.ui.Motion;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.KitScreen;
import com.flintclient.client.ui.kit.Node;
import org.jspecify.annotations.Nullable;


/**
 * Flint's Right Shift window ({@link ModMenuWindow}), over the game (dimmed, and blurred by Minecraft's menu blur when
 * "Blur Flint menus" is on) or over Flint's artwork on the title screen. It opens from the HUD page's EDIT FLINT MODS (or
 * straight away where there is no world), fades in over 180 ms and out over 120 ms, and closes with Right Shift (a fresh
 * press, never while typing), Escape once no picker or question inside wants it, or the close button. Right Shift and
 * Escape leave Flint's menu altogether (back to the game, or to the title or pause screen it was opened over); Close goes
 * back to where the window was opened from (the HUD page). Each opening starts afresh: the Mod Menu grid, no search, at the
 * top.
 *
 * <p>Changes apply at once and are written a second after the first unsaved one and again on close. An exception while
 * building, drawing or handling input is logged once, the window steps aside and the open path stays off for the
 * session, so a fault costs the menu, not the game. It doesn't pause the game. Render thread.
 */
public final class ModMenuScreen extends KitScreen {
	private static final long CLOSE_TIMEOUT_MILLIS = 600;
	private static final long SAVE_AFTER_MILLIS = 1000;
	private static final float RISE = 12;
	private static final float DROP = 6;

	/** The engine's screen it was opened from (the HUD page, the title screen...), shown again when it closes. */
	private final @Nullable Object parent;
	private final MenuMotion motion = new MenuMotion();
	private final ToggleKeyLogic toggleKey = new ToggleKeyLogic();
	private @Nullable ModMenuWindow window;
	/** Where the next window opens (null: the Mod Menu grid). */
	private static ModMenuWindow.@Nullable Place nextPlace;
	private static @Nullable String nextModule;
	private boolean closeToGame;
	private boolean started;
	private boolean framed;
	private boolean closing;
	private boolean closed;
	private boolean failed;
	private long closingSince;
	private long dirtySince = -1;

	public ModMenuScreen(@Nullable Object parent) {
		super("Flint", 320, 200);
		this.parent = parent;
	}

	/** Makes the next window open on the Mod Menu tab with {@code moduleId}'s mod page open (if a mod holds it). */
	public static void showModule(String moduleId) {
		nextPlace = ModMenuWindow.Place.MODS;
		nextModule = moduleId;
	}

	/** Makes the next window open on the waypoint list. */
	public static void showWaypoints() {
		nextPlace = ModMenuWindow.Place.WAYPOINTS;
		nextModule = null;
	}

	/** Makes the next window open on the Mod Menu tab's grid. */
	public static void showMods() {
		nextPlace = ModMenuWindow.Place.MODS;
		nextModule = null;
	}

	// ---------- the open key's name ----------

	private static @Nullable Object keyNameFor;
	private static String keyName = "Right Shift";
	private static String keyNameUpper = "RIGHT SHIFT";

	/** The open key's name ("Right Shift"); made again only when the key changes, so per-frame hints allocate nothing. */
	public static String openKeyName() {
		refreshKeyName();
		return keyName;
	}

	public static String openKeyNameUpper() {
		refreshKeyName();
		return keyNameUpper;
	}

	private static void refreshKeyName() {
		Object bound = MenuHost.openKeyBinding();
		if (bound == keyNameFor) {
			return;
		}
		keyNameFor = bound;
		keyName = bound == null ? "Right Shift" : MenuHost.openKeyName();
		keyNameUpper = keyName.toUpperCase(java.util.Locale.ROOT);
	}

	private static ControlSettings settings() {
		return ControlState.settings();
	}

	// ---------- lifecycle ----------

	@Override
	protected void shown() {
		super.shown();
		try {
			com.flintclient.client.platform.MinecraftOptions.menuOpened();
		} catch (RuntimeException | LinkageError e) {
			broken(e);
		}
		if (started) {
			return;
		}
		started = true;
		try {
			toggleKey.opened(MenuHost.openKeyHeld());
			motion.open();
			if (settings().soundsEnabled()) {
				MenuHost.playOpenSound(settings().soundVolume());
			}
		} catch (RuntimeException | LinkageError e) {
			broken(e);
		}
	}

	/**
	 * Gets the first opening ready while the player is elsewhere, all on a worker: the catalog read, every card's picture
	 * read and trimmed, and the menu's own classes loaded. Called once, after the game has loaded; nothing here waits on it
	 * (an opening before it finishes does what it hasn't yet, as without it).
	 */
	public static void warmUp() {
		com.flintclient.client.platform.Services.background(() -> {
			try {
				java.util.List<String> keys = new java.util.ArrayList<>();
				for (Catalog.Mod mod : Catalog.get().mods()) {
					keys.add(mod.key());
				}
				com.flintclient.client.render.ModIcons.preload(keys);
				long began = System.nanoTime();
				int classes = com.flintclient.client.ClassWarmer.load(ModMenuScreen.class, java.util.List.of("com/flintclient/client/control/rshift/",
					"com/flintclient/client/ui/kit/", "com/flintclient/client/render/", "com/flintclient/client/control/hud/",
					"com/flintclient/client/control/editor/"));
				FlintLog.LOGGER.info("Flint: Right Shift menu readied ahead ({} classes loaded in {} ms on a worker)", classes,
					(System.nanoTime() - began) / 1_000_000);
			} catch (RuntimeException | LinkageError e) {
				FlintLog.LOGGER.warn("Flint: couldn't ready the Right Shift menu ahead; it loads when first opened", e);
			}
		});
	}

	@Override
	protected void build(Node root) {
		try {
			ModMenuWindow.Place place = nextPlace;
			String module = nextModule;
			nextPlace = null;
			nextModule = null;
			ModMenuWindow shown = root.add(new ModMenuWindow(this));
			window = shown;
			if (module != null) {
				shown.openModule(module);
			} else if (place != null) {
				shown.show(place, 0);
			}
		} catch (RuntimeException | LinkageError e) {
			broken(e);
		}
	}

	/** The UI Scaling the menu is laid out at ({@link #rescaleIfChanged}). */
	private float zoom = Float.NaN;

	@Override
	protected int computeUiScale(int windowW, int windowH) {
		return MenuStyle.apply(windowW, windowH);
	}

	@Override
	protected float tooltipScale() {
		return MenuStyle.tooltipScale();
	}

	@Override
	protected boolean minecraftFont() {
		return true;
	}

	/** Dawn's tooltip: a #2C3033 panel with 8 corners, no edge, the title in white over grey lines. */
	@Override
	protected TooltipLook tooltipLook() {
		return MenuStyle.tooltipLook();
	}

	/** One scale for every word on the screen, from the window (see {@link MenuStyle#pixelScale}). */
	@Override
	protected int minecraftFontScale() {
		return MenuStyle.pixelScale();
	}

	@Override
	protected void layoutContent(float width, float height) {
		if (window != null) {
			window.setBounds(0, 0, width, height);
		}
	}

	/** Nothing is focused at first, so Right Shift closes again straight away. */
	@Override
	protected void initialFocus(boolean keyboard) {
	}

	@Override
	protected void hidden() {
		super.hidden();
		save();
		try {
			// Minecraft's video options changed on the Graphics tab: textures reloaded if needed, options.txt written.
			com.flintclient.client.platform.MinecraftOptions.menuClosed();
		} catch (RuntimeException | LinkageError e) {
			FlintLog.LOGGER.warn("Flint couldn't apply Minecraft's video options", e);
		}
	}

	@Override
	protected void update() {
		if (failed || closing && Clock.millis() - closingSince > CLOSE_TIMEOUT_MILLIS) {
			finishClose();
		}
		rescaleIfChanged();
		saveSoon();
	}

	/** General > UI Scaling resizes the open menu once its slider is let go (not under the cursor while it is dragged). */
	private void rescaleIfChanged() {
		float now;
		try {
			now = settings().game(GameOptions.MENU_SCALE);
		} catch (RuntimeException | LinkageError e) {
			return;
		}
		if (Float.isNaN(zoom)) {
			zoom = now;
		} else if (now != zoom && captured() == null) {
			zoom = now;
			refreshScale();
		}
	}

	/** Writes changes a second after the first unsaved one, so a dragged slider is written about once a second, never per frame. */
	private void saveSoon() {
		boolean dirty;
		try {
			dirty = ControlState.layouts().dirty() || ControlState.settings().dirty();
		} catch (RuntimeException | LinkageError e) {
			broken(e);
			return;
		}
		if (!dirty) {
			dirtySince = -1;
			return;
		}
		long now = Clock.millis();
		if (dirtySince < 0) {
			dirtySince = now;
		} else if (now - dirtySince >= SAVE_AFTER_MILLIS) {
			dirtySince = -1;
			save();
		}
	}

	private static void save() {
		try {
			ControlState.saveAll();
		} catch (RuntimeException | LinkageError e) {
			FlintLog.LOGGER.warn("Flint couldn't save the module settings", e);
		}
	}

	// ---------- opening and closing ----------

	@Override
	protected void closeRequested() {
		if (closing) {
			return;
		}
		closing = true;
		closingSince = Clock.millis();
		motion.close();
		try {
			if (settings().soundsEnabled()) {
				MenuHost.playCloseSound(settings().soundVolume());
			}
		} catch (RuntimeException | LinkageError e) {
			broken(e);
		}
	}

	private void reopen() {
		closing = false;
		closeToGame = false;
		closed = false;
		motion.open();
	}

	private void finishClose() {
		closing = false;
		closed = false;
		if (hostIsShown()) {
			// Right Shift leaves Flint altogether; Escape and Close go back to where the window was opened from.
			hostShow(closeToGame && parent instanceof MenuParent page ? page.returnTo() : parent);
		}
	}

	private void broken(Throwable error) {
		if (!failed) {
			failed = true;
			MenuHost.screenFailed(error);
		}
	}

	/** Actions that threw so far (one click or key; the menu goes on). */
	private int actionFailures;

	/**
	 * A click, key, wheel or drag threw: that action didn't happen, and the menu goes on (only building or drawing it turns it
	 * off, since a screen that can't draw fails every frame). Reported in full once, then counted.
	 */
	private void actionFailed(Throwable error) {
		actionFailures++;
		if (actionFailures == 1) {
			FlintLog.LOGGER.warn("Flint: a Right Shift menu action failed; the menu stays open", error);
		} else {
			FlintLog.LOGGER.warn("Flint: a Right Shift menu action failed ({} so far): {}", actionFailures, String.valueOf(error));
		}
	}

	@Override
	protected boolean acceptsInput() {
		return !failed && motion.opening();
	}

	// ---------- drawing ----------

	@Override
	protected void paintBackground(Frame frame) {
		try {
			super.paintBackground(frame);
		} catch (RuntimeException | LinkageError e) {
			broken(e);
		}
	}

	/** The game (or Flint's artwork on the title screen) behind the window: blurred when that's on, and dimmed with the fade. */
	@Override
	protected void paintBackdrop(Canvas c) {
		try {
			boolean first = !framed;
			framed = true;
			MenuStyle.applyTheme(settings().menuTheme());
			if (motion.advance(first ? 0 : Motion.frameSeconds())) {
				closed = true;
			}
			float shown = motion.shown();
			setOpacity(shown);
			setContentShift(motion.offset(MenuStyle.r(RISE), MenuStyle.r(DROP)));
			setContentZoom(motion.zoom(0.97F, 0.985F));
			if (!hostInWorld()) {
				hostPaintMenuBackdrop(c);
				c.frame().nextLayer();
			}
			if (settings().transitionBlur() && MenuHost.menuBlurOn()) {
				// False when something else blurred this frame already.
				c.frame().blurBehind();
			}
			float previous = c.opacity();
			c.setOpacity(previous * shown);
			c.fill(0, 0, c.px(hostGuiWidth()), c.px(hostGuiHeight()), MenuStyle.SCRIM);
			c.setOpacity(previous);
		} catch (RuntimeException | LinkageError e) {
			broken(e);
		}
	}

	@Override
	protected void paint(Frame frame) {
		if (closed || failed) {
			FlintFrame.end();
			finishClose();
			return;
		}
		try {
			super.paint(frame);
		} catch (RuntimeException | LinkageError e) {
			FlintFrame.end();
			broken(e);
		}
	}

	// ---------- input ----------

	@Override
	protected boolean keyDown(KeyInput event) {
		if (failed || window == null) {
			return true;
		}
		try {
			if (closing) {
				if (toggleKey.keyPressed(MenuHost.isOpenKey(event), false, false)) {
					reopen();
				}
				return true;
			}
			if (!acceptsInput()) {
				return true;
			}
			// While typing (search, a name, a value), Right Shift is just a shift key.
			if (toggleKey.keyPressed(MenuHost.isOpenKey(event), typing() || hasModal(), capturingKeys())) {
				closeToGame = true;
				closeRequested();
				return true;
			}
			return super.keyDown(event);
		} catch (RuntimeException | LinkageError e) {
			actionFailed(e);
			return true;
		}
	}

	@Override
	protected boolean keyUp(KeyInput event) {
		if (MenuHost.isOpenKeyRelease(event)) {
			toggleKey.keyReleased(true);
		}
		return super.keyUp(event);
	}

	@Override
	protected boolean pointerDown(double x, double y, int button) {
		try {
			// The open key bound to a mouse button (not the left one, which works the window) closes it too.
			if (button != Kit.LEFT_BUTTON && toggleKey.keyPressed(MenuHost.isOpenButton(x, y, button), typing() || hasModal(), capturingKeys())) {
				if (closing) {
					reopen();
				} else if (acceptsInput()) {
					closeToGame = true;
					closeRequested();
				}
				return true;
			}
			return super.pointerDown(x, y, button);
		} catch (RuntimeException | LinkageError e) {
			actionFailed(e);
			return true;
		}
	}

	@Override
	protected boolean pointerUp(double x, double y, int button) {
		if (MenuHost.isOpenButtonRelease(x, y, button)) {
			toggleKey.keyReleased(true);
		}
		try {
			return super.pointerUp(x, y, button);
		} catch (RuntimeException | LinkageError e) {
			actionFailed(e);
			return true;
		}
	}

	@Override
	protected boolean wheel(double x, double y, double amount) {
		try {
			return super.wheel(x, y, amount);
		} catch (RuntimeException | LinkageError e) {
			actionFailed(e);
			return true;
		}
	}

	@Override
	protected boolean pointerDragged(double x, double y, int button) {
		try {
			return super.pointerDragged(x, y, button);
		} catch (RuntimeException | LinkageError e) {
			actionFailed(e);
			return true;
		}
	}

	/**
	 * Escape: an open picker or a question inside the window first (one press each); then Flint's menu closes, as Right Shift
	 * closes it. A mod's page, a settings tab or search text don't hold it back: the next opening starts afresh anyway.
	 */
	@Override
	protected void onEscape() {
		if (window != null && window.escape()) {
			return;
		}
		if (hostClosesOnEscape()) {
			closeToGame = true;
			closeRequested();
		}
	}
}
