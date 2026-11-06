package com.flintclient.client.platform;

import com.flintclient.client.render.Canvas;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import org.jspecify.annotations.Nullable;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * The engine's screen under a Flint kit screen ({@code ui.kit.KitScreen}), on Minecraft 1.8.9: a 1.8.9 {@link Screen} that
 * reads LWJGL 2's mouse and keyboard events itself (exact pointer positions, the wheel, key releases) and hands them to the
 * kit in GUI units, with each lifecycle step and a {@link Frame} to paint. The same kit screens as on 26.x run on it
 * unchanged. Platform class (see {@link Clock}). Render thread.
 */
public abstract class HostScreen extends Screen {
	/** A pointer position the QA tour set (window pixels), standing in for the real mouse; -1 when there is none. */
	private static int qaX = -1;
	private static int qaY = -1;

	private final String title;
	private boolean open;
	/** A step of the screen threw: it draws and takes nothing more and closes (see {@link #broke}). */
	private boolean broken;
	private int heldButton = -1;
	private int lastX = Integer.MIN_VALUE;
	private int lastY = Integer.MIN_VALUE;

	protected HostScreen(String title) {
		this.title = title;
	}

	// ---------- what the kit screen implements ----------

	protected abstract void opened();

	protected abstract void resized();

	protected abstract void initialFocus(boolean keyboard);

	protected abstract void shown();

	protected abstract void hidden();

	protected abstract void update();

	protected abstract void closeRequested();

	protected abstract void paintBackground(Frame frame);

	protected abstract void paint(Frame frame);

	protected abstract boolean pointerDown(double x, double y, int button);

	protected abstract boolean pointerUp(double x, double y, int button);

	protected abstract boolean pointerDragged(double x, double y, int button);

	protected abstract boolean wheel(double x, double y, double amount);

	protected abstract void pointerMoved(double x, double y);

	protected abstract boolean keyDown(KeyInput key);

	protected abstract boolean keyUp(KeyInput key);

	protected abstract boolean typed(int codepoint);

	protected abstract boolean holdsInput();

	protected abstract boolean pausesGame();

	// ---------- what the kit asks of the engine ----------

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	protected final int hostWindowWidth() {
		return mc().width;
	}

	protected final int hostWindowHeight() {
		return mc().height;
	}

	protected final int hostGuiWidth() {
		return width;
	}

	protected final int hostGuiHeight() {
		return height;
	}

	protected final int hostGuiScale() {
		return Frame.windowGuiScale();
	}

	protected final int hostScaledWidth() {
		return Math.max(1, width);
	}

	protected final int hostScaledHeight() {
		return Math.max(1, height);
	}

	/** The pointer in GUI units, exactly. */
	protected final double hostMouseX() {
		int px = qaX >= 0 ? qaX : Mouse.getX();
		return px * (double) width / Math.max(1, mc().width);
	}

	protected final double hostMouseY() {
		int fromTop = qaY >= 0 ? qaY : mc().height - Mouse.getY() - 1;
		return fromTop * (double) height / Math.max(1, mc().height);
	}

	/** Text input needs nothing started on 1.8.9 (characters come with the key presses). */
	protected final void hostTextInput(boolean on) {
	}

	protected final void hostTextInputArea(int x0, int y0, int x1, int y1) {
	}

	/** 1.8.9 has no narrator. */
	protected final void hostNarrate(String text) {
	}

	protected final boolean hostInWorld() {
		return mc().world != null;
	}

	protected final boolean hostIsShown() {
		return mc().screen == this;
	}

	/** Shows {@code screen} instead (a 1.8.9 screen object, or null for the game). */
	protected final void hostShow(@Nullable Object screen) {
		Minecraft mc = mc();
		mc.openScreen((Screen) screen);
		if (mc.screen == null && mc.world != null) {
			mc.lockMouse();
		}
	}

	/** Closes the screen as the engine does (back to the game). */
	protected final void hostCloseNow() {
		hostShow(null);
	}

	protected final boolean hostClosesOnEscape() {
		return true;
	}

	/** Behind a menu opened without a world: Flint's black. */
	protected final void hostPaintMenuBackdrop(Canvas c) {
		c.fill(0, 0, c.px(width) + c.scale(), c.px(height) + c.scale(), 0xFF0D0F12);
	}

	/** The engine's own screen object shown now (for screens that remember where they were opened from). */
	public static @Nullable Object current() {
		return mc().screen;
	}

	/** The QA tour's pointer (window pixels; -1, -1 hands the pointer back to the mouse). */
	public static void qaPointer(int x, int y) {
		qaX = x;
		qaY = y;
	}

	public String title() {
		return title;
	}

	// ---------- 1.8.9's screen API, handed to the kit ----------

	@Override
	public final void init() {
		try {
			if (!open) {
				open = true;
				Keyboard.enableRepeatEvents(true);
				shown();
				opened();
				initialFocus(false);
			} else if (!broken) {
				resized();
			}
		} catch (RuntimeException | LinkageError e) {
			broke(e);
		}
	}

	@Override
	public final void removed() {
		if (open) {
			open = false;
			Keyboard.enableRepeatEvents(false);
			heldButton = -1;
			// Never allowed to stop the screen that replaces it.
			try {
				hidden();
			} catch (RuntimeException | LinkageError e) {
				broke(e);
			}
		}
	}

	/**
	 * A Flint screen that throws closes instead of taking the game down (Minecraft turns an exception from a screen into a
	 * crash): logged once (MenuHost.screenFailed), and shut at the end of the frame or tick it failed in.
	 */
	private void broke(Throwable error) {
		if (broken) {
			return;
		}
		broken = true;
		MenuHost.screenFailed(error);
	}

	/** After a step: a screen that broke closes now that the step is over. */
	private void closeIfBroken() {
		if (broken && mc().screen == this) {
			hostShow(null);
		}
	}

	@Override
	public final void tick() {
		if (broken) {
			closeIfBroken();
			return;
		}
		try {
			update();
		} catch (RuntimeException | LinkageError e) {
			broke(e);
		}
		closeIfBroken();
	}

	@Override
	public final void render(int mouseX, int mouseY, float tickDelta) {
		if (broken) {
			return;
		}
		try {
			Frame frame = Frame.get();
			paintBackground(frame);
			paint(frame);
		} catch (RuntimeException | LinkageError e) {
			broke(e);
		}
	}

	@Override
	public final boolean shouldPauseGame() {
		return pausesGame();
	}

	@Override
	public void handleMouse() {
		if (broken) {
			closeIfBroken();
			return;
		}
		try {
			mouse();
		} catch (RuntimeException | LinkageError e) {
			broke(e);
		}
		closeIfBroken();
	}

	private void mouse() {
		Minecraft mc = mc();
		int ex = Mouse.getEventX();
		int ey = Mouse.getEventY();
		double x = ex * (double) width / Math.max(1, mc.width);
		double y = (mc.height - ey - 1) * (double) height / Math.max(1, mc.height);
		int button = Mouse.getEventButton();
		if (button >= 0) {
			if (Mouse.getEventButtonState()) {
				if (heldButton < 0) {
					heldButton = button;
				}
				pointerDown(x, y, button);
			} else {
				if (button == heldButton) {
					heldButton = -1;
				}
				pointerUp(x, y, button);
			}
		} else if (ex != lastX || ey != lastY) {
			if (heldButton >= 0) {
				pointerDragged(x, y, heldButton);
			} else {
				pointerMoved(x, y);
			}
		}
		lastX = ex;
		lastY = ey;
		int dWheel = Mouse.getEventDWheel();
		if (dWheel != 0) {
			wheel(x, y, Math.signum(dWheel));
		}
	}

	@Override
	public void handleKeyboard() {
		if (broken) {
			closeIfBroken();
			return;
		}
		try {
			keyboard();
		} catch (RuntimeException | LinkageError e) {
			broke(e);
		}
		closeIfBroken();
	}

	private void keyboard() {
		int key = Keyboard.getEventKey();
		if (Keyboard.getEventKeyState()) {
			keyPressed(Keyboard.getEventCharacter(), key);
		} else if (key != 0) {
			keyUp(KeyInput.of(key));
		}
		mc().handleGuiKeyBindings();
	}

	/** A key press with its character (1.8.9 sends both at once): the key to the kit, then the character as typing. */
	@Override
	protected void keyPressed(char chr, int key) {
		if (key != 0) {
			keyDown(KeyInput.of(key));
		}
		if (chr >= ' ' && SharedConstants.isValidChatChar(chr) && mc().screen == this) {
			typed(chr);
		}
	}

	/** A click in whole GUI units (Minecraft's own path, used by the QA tour; real input arrives through {@link #handleMouse}). */
	@Override
	protected void mouseClicked(int mouseX, int mouseY, int button) {
		pointerDown(mouseX + 0.5, mouseY + 0.5, button);
	}

	@Override
	protected void mouseReleased(int mouseX, int mouseY, int button) {
		pointerUp(mouseX + 0.5, mouseY + 0.5, button);
	}

	@Override
	protected void mouseDragged(int mouseX, int mouseY, int button, long time) {
		pointerDragged(mouseX + 0.5, mouseY + 0.5, button);
	}

	/** The wheel, as the QA tour turns it (notches; positive is up). */
	public void scrolled(int notches) {
		wheel(hostMouseX(), hostMouseY(), notches);
	}

	/** Whether the screen takes keys the game would otherwise act on (typing, binding a key). */
	public boolean capturesKeys() {
		return holdsInput();
	}
}
