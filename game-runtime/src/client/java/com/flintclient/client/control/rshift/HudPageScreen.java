package com.flintclient.client.control.rshift;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.editor.HudEditor;
import com.flintclient.client.control.model.ToggleKeyLogic;
import com.flintclient.client.platform.KeyInput;
import com.flintclient.client.platform.MenuHost;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.KitScreen;
import com.flintclient.client.ui.kit.Node;
import org.jspecify.annotations.Nullable;

/**
 * What Right Shift opens in a world, as the reference's HUD page: the live game with every HUD mod editable (drag to move,
 * scroll to scale, right-drag to select several; see {@link HudEditor}), and over it Flint's logo, EDIT FLINT MODS (the
 * window), HUDS (the palette of HUD mods to add) and shortcut tiles, with the controls under "?". Right Shift again (a
 * fresh press, never while typing) saves and returns to the game, as Escape does once the palette, the controls panel and
 * the selection are closed. It doesn't pause the game; Flint's HUD is drawn by the editor while it is open. If drawing the
 * editor fails, it closes at the next tick (see {@link HudEditor}). Render thread.
 */
public final class HudPageScreen extends KitScreen implements MenuParent {
	private static final int MIN_WIDTH = 300;
	private static final int MIN_HEIGHT = 180;

	private final @Nullable Object parent;
	private final HudEditor editor;
	private final ToggleKeyLogic toggleKey = new ToggleKeyLogic();
	private final MenuMotion motion = new MenuMotion();
	private @Nullable HudPage page;
	private boolean opened;
	private boolean framed;
	/** The step that leaves, run once the closing fade ends (null while not closing). */
	private @Nullable Runnable leaveAfterFade;

	public HudPageScreen(@Nullable Object parent) {
		super("Flint HUD", MIN_WIDTH, MIN_HEIGHT);
		this.parent = parent;
		this.editor = new HudEditor(this, parent, ControlState.layout());
		editor.exitWith(this::fadeOut);
	}

	/** Leaving: the page fades out (with the close sound), then {@code leave} runs. */
	private void fadeOut(Runnable leave) {
		leaveAfterFade = leave;
		motion.close();
		if (ControlState.settings().soundsEnabled()) {
			MenuHost.playCloseSound(ControlState.settings().soundVolume());
		}
	}

	/** Where leaving goes (the game, when null). */
	@Override
	public @Nullable Object returnTo() {
		return parent;
	}

	HudEditor editor() {
		return editor;
	}

	/** EDIT FLINT MODS and the tiles that lead into the window: saves, then opens it; Escape or Close there come back here. */
	void openWindow() {
		editor.prepareToLeave();
		MenuHost.openWindow(this);
	}

	/** A tile that leads to another screen (Minecraft's settings, the screenshots): saves first, and that screen comes back here. */
	void openScreen(Object screen) {
		editor.prepareToLeave();
		hostShow(screen);
	}

	/** Shown, first or again (back from the window): the key that opened it closes it only once let go. */
	@Override
	protected void shown() {
		super.shown();
		toggleKey.opened(MenuHost.openKeyHeld());
		editor.shown();
		if (!opened) {
			// The first time (not back from the window): fade and rise in, with the open sound.
			opened = true;
			motion.open();
			if (ControlState.settings().soundsEnabled()) {
				MenuHost.playOpenSound(ControlState.settings().soundVolume());
			}
		}
	}

	/** The page is in Minecraft's font (its own type), and so are its tooltips; the HUD editor below keeps the HUD's own. */
	@Override
	protected boolean minecraftTooltips() {
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
	protected boolean acceptsInput() {
		return leaveAfterFade == null && super.acceptsInput();
	}

	@Override
	protected void build(Node root) {
		editor.build(root);
		page = root.add(new HudPage(this));
	}

	/**
	 * The kit's own scale (the editor's marks, handles and tooltips stay the size they always were), with the page's
	 * controls sized from the window's proportions at that scale.
	 */
	@Override
	protected int computeUiScale(int windowW, int windowH) {
		int scale = super.computeUiScale(windowW, windowH);
		MenuStyle.applyAt(windowW, windowH, scale);
		return scale;
	}

	@Override
	protected void layoutContent(float width, float height) {
		editor.layout(width, height);
		if (page != null) {
			page.setBounds(0, 0, width, height);
		}
	}

	/** Nothing is focused at first, so Right Shift closes again straight away. */
	@Override
	protected void initialFocus(boolean keyboard) {
	}

	/**
	 * The editing surface (the game dimmed, not blurred: Minecraft's own HUD is part of what is arranged here; the mods;
	 * the guides while dragging) in GUI units, fading with the page as it opens and closes.
	 */
	@Override
	protected void paintBackdrop(Canvas c) {
		MenuStyle.applyTheme(ControlState.settings().menuTheme());
		boolean first = !framed;
		framed = true;
		if (motion.advance(first ? 0 : com.flintclient.client.ui.Motion.frameSeconds()) && leaveAfterFade != null) {
			Runnable leave = leaveAfterFade;
			leaveAfterFade = null;
			leave.run();
			return;
		}
		float shown = motion.shown();
		setOpacity(shown);
		setContentShift(motion.offset(MenuStyle.r(RISE), MenuStyle.r(DROP)));
		float previous = c.opacity();
		c.setOpacity(previous * shown);
		try {
			editor.paint(c, hostGuiWidth(), hostGuiHeight());
		} finally {
			c.setOpacity(previous);
		}
	}

	/** Layout units the page rises from as it opens, and drops by as it closes (as the window). */
	private static final float RISE = 24;
	private static final float DROP = 12;

	@Override
	protected boolean keyDown(KeyInput event) {
		if (toggleKey.keyPressed(MenuHost.isOpenKey(event), typing() || hasModal(), capturingKeys())) {
			editor.saveAndClose();
			return true;
		}
		if (acceptsInput() && editor.keyPressed(event)) {
			return true;
		}
		return super.keyDown(event);
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
		// The open key bound to a mouse button other than the two the editor uses closes it too.
		if (button != Kit.LEFT_BUTTON && button != Kit.RIGHT_BUTTON
			&& toggleKey.keyPressed(MenuHost.isOpenButton(x, y, button), typing() || hasModal(), capturingKeys())) {
			editor.saveAndClose();
			return true;
		}
		return super.pointerDown(x, y, button);
	}

	@Override
	protected boolean pointerUp(double x, double y, int button) {
		if (MenuHost.isOpenButtonRelease(x, y, button)) {
			toggleKey.keyReleased(true);
		}
		return super.pointerUp(x, y, button);
	}

	/** Escape: the palette, then the controls panel, then the editor's own layers (a drag, the selection), then leave. */
	@Override
	protected void onEscape() {
		if (page == null || !page.escape()) {
			editor.requestLeave();
		}
	}

	/** Any other way of closing goes the editor's way out (as the old editor did). */
	@Override
	protected void closeRequested() {
		editor.requestLeave();
	}

	@Override
	protected void hidden() {
		super.hidden();
		editor.removed();
	}

	@Override
	protected void update() {
		super.update();
		editor.tick();
	}
}
