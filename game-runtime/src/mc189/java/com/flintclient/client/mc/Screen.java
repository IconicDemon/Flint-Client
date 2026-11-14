package com.flintclient.client.mc;

import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.jspecify.annotations.Nullable;
import org.lwjgl.input.Mouse;

/**
 * Newer Minecraft's screen as Flint's main menu and its pages are written against, on Minecraft 1.8.9 (see {@link Component}):
 * a real 1.8.9 screen that keeps newer Minecraft's widgets ({@link AbstractWidget}), focus and lifecycle ({@link #init},
 * {@link #added}, {@link #rebuildWidgets}, {@link #onClose}), and hands it 1.8.9's input (exact pointer positions from LWJGL,
 * the wheel, Tab and arrows to move the focus) and drawing ({@link #extractBackground}, {@link #extractRenderState}) in newer
 * Minecraft's terms. A step that throws is logged once and the screen gives way (Minecraft 1.8.9 would turn it into a crash):
 * to Minecraft's own title screen without a world, else to the game. Render thread.
 */
public abstract class Screen extends net.minecraft.client.gui.screen.Screen {
	/** The player last used the keyboard in a menu (focus rings show), not the mouse. */
	static boolean keyboardInput;
	private static boolean failed;
	/** Flint's main menu failed this session: Minecraft's own title screen is shown from then on. */
	private static boolean titleBroken;

	protected final Component title;
	/** Newer Minecraft's game object (it hides 1.8.9's field of the same name for the menu's code). */
	protected Minecraft minecraft = Minecraft.getInstance();
	private final List<AbstractWidget> children = new ArrayList<>();
	private @Nullable AbstractWidget focused;
	private @Nullable AbstractWidget initialFocus;
	private boolean initialized;
	private boolean resizing;
	private boolean dragging;
	private int heldButton = -1;
	private int lastX = Integer.MIN_VALUE;
	private int lastY = Integer.MIN_VALUE;

	protected Screen(Component title) {
		this.title = title;
	}

	/** Whether Flint's main menu failed this session (Minecraft's own title screen stays then). */
	public static boolean titleBroken() {
		return titleBroken;
	}

	public Component getTitle() {
		return title;
	}

	// ---------- newer Minecraft's lifecycle ----------

	/** Adds the screen's widgets (from 1.8.9's {@code init}, which this overrides: public there). */
	@Override
	public void init() {
	}

	/** The screen is about to be shown (before {@link #init}). */
	public void added() {
	}

	/** Its widgets made again for the current size. */
	protected void rebuildWidgets() {
		int font = minecraftFont();
		try {
			clearWidgets();
			setFocused(null);
			init();
		} finally {
			com.flintclient.client.render.PixelFace.end(font);
		}
	}

	/**
	 * Every face in Minecraft's font, at the whole scale nearest its size, until {@code PixelFace.end}: 1.8.9 has no other font,
	 * and laying out and drawing must measure alike (a bundled face drawn in Minecraft's font would stay at one pixel a font
	 * pixel whatever its size). Returns the mode to go back to.
	 */
	public static int minecraftFont() {
		return com.flintclient.client.render.PixelFace.begin(com.flintclient.client.render.PixelFace.BY_SIZE);
	}

	/** The window changed size: the screen is laid out again. */
	@Override
	public void resize(int width, int height) {
		this.width = width;
		this.height = height;
		rebuildWidgets();
	}

	protected void clearWidgets() {
		children.clear();
	}

	protected <T extends AbstractWidget> T addRenderableWidget(T widget) {
		children.add(widget);
		return widget;
	}

	protected <T extends AbstractWidget> T addWidget(T widget) {
		children.add(widget);
		return widget;
	}

	protected void removeWidget(AbstractWidget widget) {
		children.remove(widget);
		if (focused == widget) {
			setFocused(null);
		}
	}

	/** The widgets, in the order they were added (the order clicks reach them). */
	public List<AbstractWidget> children() {
		return children;
	}

	public void setFocused(@Nullable AbstractWidget widget) {
		if (focused != null && focused != widget) {
			focused.setFocused(false);
		}
		focused = widget;
		if (widget != null) {
			widget.setFocused(true);
		}
	}

	public @Nullable AbstractWidget getFocused() {
		return focused;
	}

	/** The widget focused first when the screen opens from the keyboard. */
	protected void setInitialFocus(@Nullable AbstractWidget widget) {
		initialFocus = widget;
		if (widget != null && keyboardInput) {
			setFocused(widget);
		}
	}

	public boolean shouldCloseOnEsc() {
		return true;
	}

	/** Escape, or a screen's own back button: back to the game (a page goes back to where it was opened from). */
	public void onClose() {
		minecraft.gui.setScreen(null);
	}

	public boolean isPauseScreen() {
		return true;
	}

	@Override
	public final boolean shouldPauseGame() {
		return isPauseScreen();
	}

	// ---------- newer Minecraft's input ----------

	public boolean keyPressed(KeyEvent event) {
		if (event.isEscape() && shouldCloseOnEsc()) {
			onClose();
			return true;
		}
		if (event.isCycleFocus()) {
			moveFocus(!event.hasShiftDown());
			return true;
		}
		if (event.isUp() || event.isLeft() || event.isDown() || event.isRight()) {
			moveFocus(event.isDown() || event.isRight());
			return true;
		}
		return focused != null && focused.keyPressed(event);
	}

	/** The focus to the next (or previous) widget that can take it, in the order they were added, wrapping around. */
	private void moveFocus(boolean forward) {
		int n = children.size();
		if (n == 0) {
			return;
		}
		int at = focused == null ? (forward ? -1 : n) : children.indexOf(focused);
		for (int step = 1; step <= n; step++) {
			int i = Math.floorMod(at + (forward ? step : -step), n);
			AbstractWidget candidate = children.get(i);
			if (candidate.isActive()) {
				setFocused(candidate);
				return;
			}
		}
	}

	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (AbstractWidget child : children) {
			if (child.isMouseOver(event.x(), event.y()) && child.mouseClicked(event, doubleClick)) {
				setFocused(child);
				if (event.button() == 0) {
					dragging = true;
				}
				return true;
			}
		}
		return false;
	}

	public boolean mouseReleased(MouseButtonEvent event) {
		if (event.button() == 0 && dragging) {
			dragging = false;
			if (focused != null) {
				return focused.mouseReleased(event);
			}
		}
		return false;
	}

	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		return dragging && focused != null && event.button() == 0 && focused.mouseDragged(event, dx, dy);
	}

	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		for (AbstractWidget child : children) {
			if (child.isMouseOver(x, y) && child.mouseScrolled(x, y, scrollX, scrollY)) {
				return true;
			}
		}
		return false;
	}

	public void mouseMoved(double x, double y) {
	}

	// ---------- newer Minecraft's drawing ----------

	/** Behind the screen: 1.8.9's own (the game dimmed, or its dirt). */
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		renderBackground();
	}

	/** The widgets, in order. */
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		for (int i = 0; i < children.size(); i++) {
			children.get(i).extractRenderState(graphics, mouseX, mouseY, partialTick);
		}
	}

	// ---------- Minecraft 1.8.9's screen, handed on ----------

	@Override
	public final void init(net.minecraft.client.Minecraft game, int width, int height) {
		try {
			// What 1.8.9's own init sets, then newer Minecraft's: the first time the screen's init, later a rebuild.
			super.minecraft = game;
			this.textRenderer = game.textRenderer;
			this.itemRenderer = game.getItemRenderer();
			this.width = width;
			this.height = height;
			this.buttons.clear();
			if (!resizing) {
				added();
			}
			if (!initialized) {
				initialized = true;
				int font = minecraftFont();
				try {
					init();
				} finally {
					com.flintclient.client.render.PixelFace.end(font);
				}
				if (initialFocus != null && keyboardInput) {
					setFocused(initialFocus);
				}
			} else {
				rebuildWidgets();
			}
		} catch (RuntimeException | LinkageError e) {
			fail(e);
		}
	}

	@Override
	public final void resize(net.minecraft.client.Minecraft game, int width, int height) {
		resizing = true;
		try {
			init(game, width, height);
		} finally {
			resizing = false;
		}
	}

	@Override
	public final void render(int mouseX, int mouseY, float tickDelta) {
		int font = minecraftFont();
		try {
			GuiGraphicsExtractor graphics = GuiGraphicsExtractor.get();
			extractBackground(graphics, mouseX, mouseY, tickDelta);
			extractRenderState(graphics, mouseX, mouseY, tickDelta);
		} catch (RuntimeException | LinkageError e) {
			fail(e);
		} finally {
			com.flintclient.client.render.PixelFace.end(font);
		}
	}

	/** A key press (1.8.9 hands the key and its character together): newer Minecraft's key press. */
	@Override
	protected final void keyPressed(char chr, int key) {
		if (key == 0) {
			return;
		}
		keyboardInput = true;
		try {
			keyPressed(KeyEvent.of(key));
		} catch (RuntimeException | LinkageError e) {
			fail(e);
		}
	}

	/** The mouse, from LWJGL's own event: exact positions in GUI units, presses, releases, drags, moves and the wheel. */
	@Override
	public final void handleMouse() {
		try {
			mouse();
		} catch (RuntimeException | LinkageError e) {
			fail(e);
		}
	}

	private void mouse() {
		net.minecraft.client.Minecraft game = Minecraft.game();
		int ex = Mouse.getEventX();
		int ey = Mouse.getEventY();
		// GUI units from the window's GUI scale, as Minecraft hands them to a screen (a menu screen's own size is in menu units).
		double scale = com.flintclient.client.platform.Frame.windowGuiScale();
		double x = ex / scale;
		double y = (game.height - ey - 1) / scale;
		int button = Mouse.getEventButton();
		if (button >= 0) {
			keyboardInput = false;
			if (Mouse.getEventButtonState()) {
				if (heldButton < 0) {
					heldButton = button;
				}
				mouseClicked(new MouseButtonEvent(x, y, button), false);
			} else {
				if (button == heldButton) {
					heldButton = -1;
				}
				mouseReleased(new MouseButtonEvent(x, y, button));
			}
		} else if (ex != lastX || ey != lastY) {
			if (lastX != Integer.MIN_VALUE) {
				keyboardInput = false;
			}
			double dx = lastX == Integer.MIN_VALUE ? 0 : (ex - lastX) / scale;
			double dy = lastY == Integer.MIN_VALUE ? 0 : (lastY - ey) / scale;
			if (heldButton >= 0) {
				mouseDragged(new MouseButtonEvent(x, y, heldButton), dx, dy);
			} else {
				mouseMoved(x, y);
			}
		}
		lastX = ex;
		lastY = ey;
		int wheel = Mouse.getEventDWheel();
		if (wheel != 0) {
			mouseScrolled(x, y, 0, Math.signum(wheel));
		}
	}

	/** A click in whole GUI units (Minecraft's own path, used by QA tours; real input arrives through {@link #handleMouse}). */
	@Override
	protected final void mouseClicked(int mouseX, int mouseY, int button) {
		keyboardInput = false;
		try {
			mouseClicked(new MouseButtonEvent(mouseX + 0.5, mouseY + 0.5, button), false);
		} catch (RuntimeException | LinkageError e) {
			fail(e);
		}
	}

	@Override
	protected final void mouseReleased(int mouseX, int mouseY, int button) {
		try {
			mouseReleased(new MouseButtonEvent(mouseX + 0.5, mouseY + 0.5, button));
		} catch (RuntimeException | LinkageError e) {
			fail(e);
		}
	}

	@Override
	protected final void mouseDragged(int mouseX, int mouseY, int button, long time) {
		try {
			mouseDragged(new MouseButtonEvent(mouseX + 0.5, mouseY + 0.5, button), 0, 0);
		} catch (RuntimeException | LinkageError e) {
			fail(e);
		}
	}

	/** A step threw: logged once, and the screen gives way (to Minecraft's title screen without a world, else to the game). */
	private void fail(Throwable error) {
		if (!failed) {
			failed = true;
			LogManager.getLogger("Flint").error("Flint: " + getClass().getSimpleName() + " failed; showing Minecraft's own screen", error);
		} else {
			LogManager.getLogger("Flint").warn("Flint: {} failed again: {}", getClass().getSimpleName(), error.toString());
		}
		if (this instanceof TitleScreen) {
			titleBroken = true;
		}
		net.minecraft.client.Minecraft game = Minecraft.game();
		if (game.screen == this) {
			game.openScreen(game.world == null ? new net.minecraft.client.gui.screen.menu.TitleScreen() : null);
		}
	}
}
