package com.flintclient.client.control.platform;

/**
 * The version seam for what the Control Center needs from Minecraft that isn't drawing: input state, key
 * names, interface sounds and the narrator. The interface stays free of Minecraft types so the code that
 * uses it can move to a shared module; {@link Mc263ControlPlatform} is Minecraft 26.3's side, and also
 * offers the helpers that need Minecraft's own types (key mappings, text input focus). Render thread only.
 */
public interface ControlPlatform {
	/** Whether gameplay has the input: a world is loaded and no screen or overlay is open. */
	boolean gameplayHasInput();

	/** Whether a world is loaded. */
	boolean inWorld();

	/** Whether the player has hidden the HUD (F1). */
	boolean hudHidden();

	/** Whether the F3 debug screen is showing. */
	boolean debugScreenShown();

	/**
	 * The name of the key that opens the Control Center as Minecraft shows it, for example "Right Shift", or
	 * Minecraft's "Not Bound" when it has none (or Flint's key bindings couldn't be created).
	 */
	String controlCenterKeyName();

	/** The name of the key that opens the HUD editor, or Minecraft's "Not Bound" when it has none. */
	String hudEditorKeyName();

	/**
	 * Plays the open sound at {@code volume} (0 to 1); Minecraft also applies its master and UI volumes.
	 * Screens use {@code ControlCenter.playOpenSound}, which turns the sounds off if this fails.
	 */
	void playOpenSound(float volume);

	/** Plays the close sound, as {@link #playOpenSound} does the open one ({@code ControlCenter.playCloseSound}). */
	void playCloseSound(float volume);

	/** Whether the narrator is on and reads interface (system) messages. */
	boolean narratorOn();

	/** Speaks {@code text} now, interrupting anything being read, when the narrator is on. */
	void narrate(String text);
}
