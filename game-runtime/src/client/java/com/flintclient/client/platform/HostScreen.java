package com.flintclient.client.platform;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.ui.Backdrop;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The engine's screen under a Flint kit screen ({@code ui.kit.KitScreen}): it is Minecraft's {@link Screen} here, so
 * Minecraft opens, draws, sizes and feeds it like any other, and it hands every event on to the kit in the kit's own terms
 * (GUI units, {@link KeyInput}, a {@link Frame}). The kit implements the abstract hooks and asks the {@code host*} questions;
 * it never touches Minecraft's screen API itself. Each engine has its own (1.8.9's forwards LWJGL 2's mouse, wheel and
 * keyboard), so a kit screen is the same class on every line. Platform class (see {@link Clock}). Render thread.
 */
public abstract class HostScreen extends Screen {
	/** A step of the screen threw: it draws and takes nothing more and closes (see {@link #broke}). */
	private boolean broken;

	protected HostScreen(String title) {
		super(Component.literal(title));
	}

	/**
	 * A Flint screen that throws closes instead of taking the game down: Minecraft turns an exception from a screen's
	 * drawing, ticking, input or opening and closing into a crash report. The first failure is logged with its stack; the
	 * screen closes at its next tick (back to the game, or to the title screen when no world is loaded), never in the middle
	 * of the draw or event that failed.
	 */
	private void broke(String step, Throwable error) {
		if (broken) {
			return;
		}
		broken = true;
		com.flintclient.client.FlintClient.LOGGER.error("Flint: the {} screen failed while {}; it closes instead of crashing the game",
			getClass().getSimpleName(), step, error);
	}

	/** Closes a screen that broke, if it is still the one shown. */
	private void closeBroken() {
		if (minecraft != null && minecraft.gui.screen() == this) {
			minecraft.gui.setScreen(null);
		}
	}

	// ---------- what the kit screen implements ----------

	/** The screen is being opened (Minecraft set it up for the window). */
	protected abstract void opened();

	/** The window changed size while the screen was open. */
	protected abstract void resized();

	/** The screen is opening; {@code keyboard} when the last input was from the keyboard (the first control may take focus). */
	protected abstract void initialFocus(boolean keyboard);

	/** The screen was shown (opened, or returned to from another screen). */
	protected abstract void shown();

	/** The screen was removed (closed, or another screen replaced it). */
	protected abstract void hidden();

	/** Once per game tick while shown. */
	protected abstract void update();

	/** The player asked to close the screen (Escape outside the kit, or the game closing it). */
	protected abstract void closeRequested();

	/** Draws what is behind the screen. */
	protected abstract void paintBackground(Frame frame);

	/** Draws the screen. */
	protected abstract void paint(Frame frame);

	/** A mouse button went down at ({@code x}, {@code y}) GUI units; true when it was used. */
	protected abstract boolean pointerDown(double x, double y, int button);

	protected abstract boolean pointerUp(double x, double y, int button);

	protected abstract boolean pointerDragged(double x, double y, int button);

	protected abstract boolean wheel(double x, double y, double amount);

	protected abstract void pointerMoved(double x, double y);

	protected abstract boolean keyDown(KeyInput key);

	protected abstract boolean keyUp(KeyInput key);

	/** A character was typed (one Minecraft allows in text); true when it was used. */
	protected abstract boolean typed(int codepoint);

	/** True while the screen takes keys Minecraft would otherwise act on (typing, binding a key). */
	protected abstract boolean holdsInput();

	/** Whether the game pauses behind it in singleplayer. */
	protected abstract boolean pausesGame();

	// ---------- what the kit asks of the engine ----------

	/** The window's size in pixels. */
	protected final int hostWindowWidth() {
		return minecraft.getWindow().getWidth();
	}

	protected final int hostWindowHeight() {
		return minecraft.getWindow().getHeight();
	}

	/** The screen's size in GUI units. */
	protected final int hostGuiWidth() {
		return width;
	}

	protected final int hostGuiHeight() {
		return height;
	}

	/** The window's GUI scale (pixels per GUI unit). */
	protected final int hostGuiScale() {
		return Math.max(1, minecraft.getWindow().getGuiScale());
	}

	/** The window's size in GUI units as the mouse is scaled (Minecraft's scaled width and height). */
	protected final int hostScaledWidth() {
		return Math.max(1, minecraft.getWindow().getGuiScaledWidth());
	}

	protected final int hostScaledHeight() {
		return Math.max(1, minecraft.getWindow().getGuiScaledHeight());
	}

	/** The pointer in GUI units, exactly (the draw call's arguments are whole units). */
	protected final double hostMouseX() {
		Window window = minecraft.getWindow();
		return minecraft.mouseHandler.getScaledXPos(window);
	}

	protected final double hostMouseY() {
		Window window = minecraft.getWindow();
		return minecraft.mouseHandler.getScaledYPos(window);
	}

	/** Starts or stops text input for this screen ({@code Minecraft.onTextInputFocusChange}, as {@code EditBox.setFocused} does). */
	protected final void hostTextInput(boolean on) {
		minecraft.onTextInputFocusChange(this, on);
	}

	/** Where the IME's candidate window goes (the caret), in GUI units. */
	protected final void hostTextInputArea(int x0, int y0, int x1, int y1) {
		minecraft.textInputManager().setTextInputArea(x0, y0, x1, y1);
	}

	/** Speaks {@code text} when Minecraft's narrator is on. */
	protected final void hostNarrate(String text) {
		if (minecraft.options.narrator().get().shouldNarrateSystem() && minecraft.getNarrator().isActive()) {
			minecraft.getNarrator().saySystemNow(text);
		}
	}

	/** A world is loaded (the screen is over the game, not the title screen). */
	protected final boolean hostInWorld() {
		return minecraft.level != null;
	}

	/** This screen is the one shown. */
	protected final boolean hostIsShown() {
		return minecraft.gui.screen() == this;
	}

	/** Shows {@code screen} instead (an engine screen object, or null for the game). */
	protected final void hostShow(@Nullable Object screen) {
		minecraft.gui.setScreen((Screen) screen);
	}

	/** Closes the screen as the engine does (back to the game). */
	protected final void hostCloseNow() {
		super.onClose();
	}

	/** Whether Escape closes this screen (Minecraft's own rule for it). */
	protected final boolean hostClosesOnEscape() {
		return shouldCloseOnEsc();
	}

	/** Behind a menu opened from the title screen: Flint's menu artwork, veiled as Flint's pages are. */
	protected final void hostPaintMenuBackdrop(Canvas c) {
		Backdrop.paint(c, width, height, Backdrop.PAGE);
	}

	/** The engine's own screen object behind {@code screen} (for screens that remember where they were opened from). */
	public static @Nullable Object current() {
		return net.minecraft.client.Minecraft.getInstance().gui.screen();
	}

	// ---------- Minecraft's screen API, handed to the kit ----------

	@Override
	protected final void init() {
		try {
			opened();
		} catch (RuntimeException | LinkageError e) {
			broke("opening", e);
		}
	}

	@Override
	protected final void repositionElements() {
		if (broken) {
			return;
		}
		try {
			resized();
		} catch (RuntimeException | LinkageError e) {
			broke("resizing", e);
		}
	}

	@Override
	protected final void setInitialFocus() {
		if (broken) {
			return;
		}
		try {
			initialFocus(minecraft.getLastInputType().isKeyboard());
		} catch (RuntimeException | LinkageError e) {
			broke("opening", e);
		}
	}

	@Override
	public final void added() {
		try {
			shown();
		} catch (RuntimeException | LinkageError e) {
			broke("opening", e);
		}
	}

	@Override
	public final void removed() {
		// Always asked (it lets go of what the screen holds), and never allowed to stop the screen that replaces it.
		try {
			hidden();
		} catch (RuntimeException | LinkageError e) {
			broke("closing", e);
		}
	}

	@Override
	public final void tick() {
		if (broken) {
			closeBroken();
			return;
		}
		try {
			update();
		} catch (RuntimeException | LinkageError e) {
			broke("ticking", e);
		}
	}

	@Override
	public final void onClose() {
		if (broken) {
			closeBroken();
			return;
		}
		try {
			closeRequested();
		} catch (RuntimeException | LinkageError e) {
			broke("closing", e);
			closeBroken();
		}
	}

	@Override
	public final void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (!broken) {
			try {
				paintBackground(Frame.of(graphics));
			} catch (RuntimeException | LinkageError e) {
				broke("drawing its background", e);
			}
		}
		// As Screen.extractBackground: subtitles stay readable under the screen.
		com.flintclient.client.compat.McCompat.hud().extractDeferredSubtitles();
	}

	@Override
	public final void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (broken) {
			return;
		}
		try {
			paint(Frame.of(graphics));
		} catch (RuntimeException | LinkageError e) {
			broke("drawing", e);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		try {
			return !broken && pointerDown(event.x(), event.y(), event.button());
		} catch (RuntimeException | LinkageError e) {
			broke("handling a click", e);
			return true;
		}
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		try {
			return !broken && pointerUp(event.x(), event.y(), event.button());
		} catch (RuntimeException | LinkageError e) {
			broke("handling a click", e);
			return true;
		}
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		try {
			return !broken && pointerDragged(event.x(), event.y(), event.button());
		} catch (RuntimeException | LinkageError e) {
			broke("handling a drag", e);
			return true;
		}
	}

	@Override
	public final boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		try {
			return !broken && wheel(x, y, scrollY);
		} catch (RuntimeException | LinkageError e) {
			broke("handling the wheel", e);
			return true;
		}
	}

	@Override
	public final void mouseMoved(double x, double y) {
		if (broken) {
			return;
		}
		try {
			pointerMoved(x, y);
		} catch (RuntimeException | LinkageError e) {
			broke("following the pointer", e);
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (broken) {
			// It is closing (broke() asked for it): nothing reaches the kit any more.
			return false;
		}
		try {
			return keyDown(KeyInput.of(event));
		} catch (RuntimeException | LinkageError e) {
			broke("handling a key", e);
			return true;
		}
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		try {
			return !broken && keyUp(KeyInput.of(event));
		} catch (RuntimeException | LinkageError e) {
			broke("handling a key", e);
			return true;
		}
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		try {
			return !broken && event.isAllowedChatCharacter() && typed(event.codepoint());
		} catch (RuntimeException | LinkageError e) {
			broke("handling typing", e);
			return true;
		}
	}

	/** Minecraft's hook from 26.3 on (older versions never ask). */
	public final boolean isInputCaptured() {
		return !broken && holdsInput();
	}

	@Override
	public final boolean isPauseScreen() {
		return pausesGame();
	}

	/** The screen's own narration says only its title; the kit narrates focus itself. */
	@Override
	protected final boolean shouldNarrateNavigation() {
		return false;
	}
}
