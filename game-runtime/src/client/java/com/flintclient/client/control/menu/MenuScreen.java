package com.flintclient.client.control.menu;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.ControlCenter;
import com.flintclient.client.control.HudEditorScreen;
import com.flintclient.client.control.editor.HudEditor;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.model.ToggleKeyLogic;
import com.flintclient.client.control.platform.ControlKeys;
import com.flintclient.client.control.platform.Mc263ControlPlatform;
import com.flintclient.client.control.store.ControlSettings;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.ui.Backdrop;
import com.flintclient.client.ui.FlintFrame;
import com.flintclient.client.ui.Motion;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.KitScreen;
import com.flintclient.client.ui.kit.Node;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * Flint's Right Shift menu ({@link MenuWindow}): the game stays visible behind it, darkened and (with "Blur behind
 * Flint" on) blurred by Minecraft's own menu blur; on the title screen the owner's background picture stands in for the
 * world. It opens and closes with Right Shift (a fresh press, never while typing in the search), Escape or the close
 * button, fading in over 180 ms with a small rise and out over 120 ms ({@link MenuMotion}).
 *
 * <p>It changes nothing in the game or in any configuration: the cards' toggles, favourites, chip, search and scroll
 * are this session's menu state ({@link MenuState}). It doesn't pause the game. An exception while building, drawing or
 * handling input is logged once, the menu steps aside, and the open path stays off for the session, so a fault here
 * costs the menu, not the game. Render thread.
 */
public final class MenuScreen extends KitScreen {
	/** How long a close waits for its last frame before a tick finishes it. */
	private static final long CLOSE_TIMEOUT_MILLIS = 600;
	/** The rise in and the drop out, in R. */
	private static final float RISE = 12;
	private static final float DROP = 6;

	private final @Nullable Screen parent;
	private final MenuState state = MenuState.session();
	private final MenuMotion motion = new MenuMotion();
	private final ToggleKeyLogic toggleKey = new ToggleKeyLogic();
	private @Nullable MenuWindow window;
	/** Opened from the HUD editor's shortcut on a place other than the mods, so Escape there goes back to the editor. */
	private boolean fromShortcut;
	/** Right Shift (not Escape or the close button) closed the menu: it goes back to the game, past the HUD editor. */
	private boolean closeToGame;
	/** Where the next menu opens (null: where it was last time). */
	private static @Nullable Start nextStart;
	private boolean started;
	private boolean framed;
	private boolean restored;
	private boolean closing;
	private boolean closed;
	private boolean failed;
	private long closingSince;
	/** When the first unsaved change was seen (-1: none). */
	private long dirtySince = -1;
	private static final long SAVE_AFTER_MILLIS = 1000;

	public MenuScreen(@Nullable Screen parent) {
		super(Component.literal("Flint"), 320, 200);
		this.parent = parent;
	}

	/** Where the menu opens: the HUD editor's buttons and shortcuts each lead to one. */
	public enum Start {
		/** The module grid (or the page that was open last). */
		MODS,
		/** Client settings. */
		SETTINGS,
		/** The PROFILES section. */
		PROFILES,
		WAYPOINTS,
		PRESETS,
		/** The menu's and the HUD's look. */
		THEMES,
		/** Flint Music's page. */
		MUSIC
	}

	/** Opens the menu on {@code start}; closing it returns to {@code parent} (the HUD editor, say). */
	public static void open(@Nullable Screen parent, Start start) {
		nextStart = start;
		ControlCenter.openMenu(parent);
	}

	/** Makes the next menu open on {@code moduleId}'s settings page (when that module has one; else the grid). */
	public static void showModule(String moduleId) {
		for (MenuCatalog.Entry entry : MenuCatalog.ENTRIES) {
			if (moduleId.equals(MenuCatalog.moduleOf(entry))) {
				MenuState.session().page = moduleId;
				nextStart = null;
				return;
			}
		}
	}

	// ---------- where it opens ----------

	/** The menu's start place, from {@link #open}; MODS keeps the page that was open last. */
	private void showStart(@Nullable Start start) {
		MenuWindow shown = window;
		if (shown == null || start == null) {
			return;
		}
		fromShortcut = start != Start.MODS && parent instanceof HudEditorScreen;
		switch (start) {
			case MODS -> {
				if (state.tab != MenuState.Tab.MODS) {
					shown.selectTab(MenuState.Tab.MODS);
				}
			}
			case SETTINGS -> shown.selectTab(MenuState.Tab.SETTINGS);
			case WAYPOINTS -> shown.selectTab(MenuState.Tab.WAYPOINTS);
			case PRESETS -> shown.selectTab(MenuState.Tab.PRESETS);
			case THEMES -> shown.selectTab(MenuState.Tab.THEMES);
			case PROFILES -> shown.openProfiles();
			case MUSIC -> shown.openPage("flint_music");
		}
	}

	/** EDIT HUD LAYOUT: back to the HUD editor (the one this menu was opened from, or a new one). */
	void editHud() {
		if (!ControlState.inWorld() || !HudEditor.available()) {
			return;
		}
		save();
		minecraft.gui.setScreen(parent instanceof HudEditorScreen ? parent : new HudEditorScreen(parent));
	}

	/** Whether the menu was opened on a shortcut's place from the HUD editor (Escape there leads back to it). */
	boolean cameFromShortcut() {
		return fromShortcut;
	}

	/** The open key's name ("Right Shift"). */
	public static String openKeyName() {
		refreshKeyName();
		return keyName;
	}

	/** {@link #openKeyName()} in capitals ("RIGHT SHIFT"), for the hints under the window and in the editor. */
	public static String openKeyNameUpper() {
		refreshKeyName();
		return keyNameUpper;
	}

	private static @Nullable Object keyNameFor;
	private static String keyName = "Right Shift";
	private static String keyNameUpper = "RIGHT SHIFT";

	/** The names are made again only when the key changes, so drawing the hints each frame makes nothing new. */
	private static void refreshKeyName() {
		ControlKeys keys = ControlCenter.keys();
		Object bound = keys == null ? null : Mc263ControlPlatform.boundKey(keys.controlCenter());
		if (bound == keyNameFor) {
			return;
		}
		keyNameFor = bound;
		keyName = keys == null ? "Right Shift" : Mc263ControlPlatform.keyName(keys.controlCenter()).getString();
		keyNameUpper = keyName.toUpperCase(java.util.Locale.ROOT);
	}

	private static ControlSettings settings() {
		return ControlState.settings();
	}

	// ---------- lifecycle ----------

	@Override
	public void added() {
		super.added();
		if (started) {
			return;
		}
		started = true;
		try {
			ControlKeys keys = ControlCenter.keys();
			toggleKey.opened(keys != null && Mc263ControlPlatform.physicallyDown(keys.controlCenter()));
			motion.open();
			playOpen();
		} catch (RuntimeException e) {
			broken(e);
		}
	}

	@Override
	protected void build(Node root) {
		try {
			window = root.add(new MenuWindow(this, state));
			Start start = nextStart;
			nextStart = null;
			showStart(start);
		} catch (RuntimeException e) {
			broken(e);
		}
	}

	/** The reference's proportions at this window size ({@link MenuStyle#apply}). */
	@Override
	protected int computeUiScale(int windowW, int windowH) {
		return MenuStyle.apply(windowW, windowH);
	}

	@Override
	protected void layoutContent(float width, float height) {
		MenuWindow shown = window;
		if (shown != null) {
			shown.setBounds(0, 0, width, height);
		}
	}

	/** Nothing is focused at first, so Right Shift closes again straight away. */
	@Override
	protected void setInitialFocus() {
	}

	/** Every way the menu goes (closed, the HUD editor, another screen): the scroll is kept, changes written, the preview freed. */
	@Override
	public void removed() {
		super.removed();
		MenuWindow shown = window;
		if (shown != null) {
			shown.rememberScroll();
		}
		save();
		ModulePage.PICTURE.release();
	}

	@Override
	public void tick() {
		if (failed || closing && Util.getMillis() - closingSince > CLOSE_TIMEOUT_MILLIS) {
			finishClose();
		}
		saveSoon();
	}

	/**
	 * Writes module changes a moment after they are made ({@link #SAVE_AFTER_MILLIS} after the first unsaved one), so a
	 * slider being dragged is written at most about once a second and never per frame; closing the menu writes the rest.
	 */
	private void saveSoon() {
		boolean dirty;
		try {
			dirty = ControlState.layouts().dirty() || ControlState.settings().dirty();
		} catch (RuntimeException e) {
			broken(e);
			return;
		}
		if (!dirty) {
			dirtySince = -1;
			return;
		}
		long now = Util.getMillis();
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
		} catch (RuntimeException e) {
			FlintClient.LOGGER.warn("Flint couldn't save the module settings", e);
		}
	}

	/** Back to the HUD editor with {@code moduleId} selected (the editor this menu came from, or a new one). */
	void openEditor(String moduleId) {
		if (!ControlState.inWorld() || !HudEditor.available()) {
			return;
		}
		save();
		if (parent instanceof HudEditorScreen editor) {
			editor.selectWhenShown(moduleId);
			minecraft.gui.setScreen(editor);
		} else {
			minecraft.gui.setScreen(new HudEditorScreen(parent, moduleId));
		}
	}

	// ---------- opening and closing ----------

	/** Starts closing: Right Shift, Escape and the close button come here. */
	@Override
	public void onClose() {
		if (closing) {
			return;
		}
		closing = true;
		closingSince = Util.getMillis();
		motion.close();
		try {
			if (settings().soundsEnabled()) {
				ControlCenter.playCloseSound(settings().soundVolume());
			}
		} catch (RuntimeException e) {
			broken(e);
		}
	}

	/** Its key pressed again while it closes: it turns round from where it is. */
	private void reopen() {
		closing = false;
		closeToGame = false;
		closed = false;
		motion.open();
		try {
			playOpen();
		} catch (RuntimeException e) {
			broken(e);
		}
	}

	private void playOpen() {
		if (settings().soundsEnabled()) {
			ControlCenter.playOpenSound(settings().soundVolume());
		}
	}

	private void finishClose() {
		closing = false;
		closed = false;
		if (minecraft.gui.screen() == this) {
			// Right Shift closes Flint altogether; Escape and the close button go back to where the menu was opened from.
			minecraft.gui.setScreen(closeToGame && parent instanceof HudEditorScreen editor ? editor.returnTo() : parent);
		}
	}

	private void broken(RuntimeException error) {
		if (!failed) {
			failed = true;
			ControlCenter.screenFailed(error);
		}
	}

	@Override
	protected boolean acceptsInput() {
		return !failed && motion.opening();
	}

	// ---------- drawing ----------

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		try {
			super.extractBackground(graphics, mouseX, mouseY, partialTick);
		} catch (RuntimeException e) {
			broken(e);
		}
	}

	/**
	 * Each frame starts here: the motion moves on (the first frame by nothing), then what lies behind the window. In a
	 * world, the menu blur (when on) and the dim; on the title screen, Flint's menu artwork, blurred, and the dim. The
	 * dim fades with the window; the blur (which can't fade) stays until the close has finished.
	 */
	@Override
	protected void paintBackdrop(Canvas c) {
		try {
			boolean first = !framed;
			framed = true;
			// The theme can change while the menu is open (the Themes page): colours follow at once. No work when unchanged.
			MenuStyle.applyTheme(settings().menuTheme());
			if (motion.advance(first ? 0 : Motion.frameSeconds())) {
				closed = true;
			}
			float shown = motion.shown();
			setOpacity(shown);
			setContentShift(motion.offset(MenuStyle.r(RISE), MenuStyle.r(DROP)));
			if (minecraft.level == null) {
				Backdrop.paint(c, width, height, Backdrop.PAGE);
				c.graphics().nextStratum();
			}
			if (settings().transitionBlur() && minecraft.options.getMenuBackgroundBlurriness() >= 1.0F) {
				try {
					c.graphics().blurBeforeThisStratum();
				} catch (IllegalStateException alreadyBlurred) {
					// Something else blurred this frame already.
				}
			}
			float previous = c.opacity();
			c.setOpacity(previous * shown);
			c.fill(0, 0, c.px(width), c.px(height), MenuStyle.SCRIM);
			c.setOpacity(previous);
		} catch (RuntimeException e) {
			broken(e);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (closed || failed) {
			FlintFrame.end();
			finishClose();
			return;
		}
		try {
			super.extractRenderState(graphics, mouseX, mouseY, partialTick);
			MenuWindow shown = window;
			if (!restored && shown != null) {
				// The grid's scroll from last time, once the first layout has sized it.
				restored = true;
				shown.restoreScroll();
			}
		} catch (RuntimeException e) {
			FlintFrame.end();
			broken(e);
		}
	}

	// ---------- input ----------

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (failed || window == null) {
			return true;
		}
		try {
			if (closing) {
				if (toggleKey.keyPressed(ControlCenter.isOpenKey(event), false, false)) {
					reopen();
				}
				return true;
			}
			if (!acceptsInput()) {
				return true;
			}
			// While typing in the search, Right Shift is just a shift key.
			if (toggleKey.keyPressed(ControlCenter.isOpenKey(event), typing() || hasModal(), capturingKeys())) {
				closeToGame = true;
				onClose();
				return true;
			}
			return super.keyPressed(event);
		} catch (RuntimeException e) {
			broken(e);
			return true;
		}
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		ControlKeys keys = ControlCenter.keys();
		if (keys != null && keys.controlCenter().matches(event)) {
			toggleKey.keyReleased(true);
		}
		return super.keyReleased(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		try {
			// The open key bound to a mouse button (not the left one, which works the menu) closes it too.
			if (event.button() != Kit.LEFT_BUTTON && toggleKey.keyPressed(ControlCenter.isOpenKey(event), typing() || hasModal(), capturingKeys())) {
				if (closing) {
					reopen();
				} else if (acceptsInput()) {
					closeToGame = true;
					onClose();
				}
				return true;
			}
			return super.mouseClicked(event, doubleClick);
		} catch (RuntimeException e) {
			broken(e);
			return true;
		}
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		ControlKeys keys = ControlCenter.keys();
		if (keys != null && keys.controlCenter().matchesMouse(event)) {
			toggleKey.keyReleased(true);
		}
		try {
			return super.mouseReleased(event);
		} catch (RuntimeException e) {
			broken(e);
			return true;
		}
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		try {
			return super.mouseScrolled(x, y, scrollX, scrollY);
		} catch (RuntimeException e) {
			broken(e);
			return true;
		}
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		try {
			return super.mouseDragged(event, dx, dy);
		} catch (RuntimeException e) {
			broken(e);
			return true;
		}
	}

	/** Escape: a focused search lets go first, then the menu closes. */
	@Override
	protected void onEscape() {
		MenuWindow shown = window;
		if (shown == null || !shown.escape()) {
			super.onEscape();
		}
	}
}
