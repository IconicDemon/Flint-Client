package com.flintclient.client.control.rshift;

/**
 * A Right Shift screen's closing fade (the HUD page, the window, Flint's own windows), as far as input goes. Pure logic, no
 * allocation; render thread.
 *
 * <p>While it fades out the screen is still the one Minecraft shows, so every key goes to it. Swallowing them lost the
 * player's next press: Esc tapped right after Right Shift or Esc closed the menu didn't open the pause menu, E didn't open
 * the inventory, until pressed again. So a key other than the menu's own ends the fade at once and is handed back
 * ({@link Press#LEAVE_NOW}: the screen leaves and answers that it didn't use the key, so Minecraft gives it to the game as if
 * the menu had already gone). The menu's own key turns the close round where the screen can reopen, and otherwise does
 * nothing more. The fade also ends by itself once it is {@link #overdue}: a screen isn't drawn while a resource reload's
 * overlay is up, and a fade only advances as frames are drawn, so without this a menu could stay on, invisible, holding
 * every key and the mouse.
 */
final class MenuClose {
	/** The longest a closing fade may last (it takes 120 ms when frames come). */
	static final long TIMEOUT_MILLIS = 600;

	/** What a key pressed during the fade does. */
	enum Press {
		/** The menu's own key, where the screen can reopen: the close turns round. */
		REOPEN,
		/** The menu's own key otherwise (or its repeats): nothing more, the fade goes on. */
		IGNORE,
		/** Any other key: the screen leaves now and hands the key on. */
		LEAVE_NOW
	}

	private boolean closing;
	private long since;

	/** The fade begins at {@code now} (milliseconds); a fade already under way keeps its start. */
	void begin(long now) {
		if (!closing) {
			closing = true;
			since = now;
		}
	}

	/** The fade is over (the screen left), or it turned round. */
	void end() {
		closing = false;
	}

	boolean closing() {
		return closing;
	}

	/** Whether the fade has run longer than it ever should at {@code now}: the screen should leave without waiting for a frame. */
	boolean overdue(long now) {
		return closing && now - since > TIMEOUT_MILLIS;
	}

	/**
	 * A key press during the fade. {@code openKey}: the key is the menu's own (pressed afresh or held); {@code reopens}: that
	 * press should turn the close round (a fresh press, on a screen that can reopen).
	 */
	static Press press(boolean openKey, boolean reopens) {
		if (!openKey) {
			return Press.LEAVE_NOW;
		}
		return reopens ? Press.REOPEN : Press.IGNORE;
	}
}
