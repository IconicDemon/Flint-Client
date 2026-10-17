package com.flintclient.client.control.center;

/**
 * An interface preference the saved settings ({@code store.ControlSettings}) don't have a field for yet, kept in
 * memory for the session with its default: "Show Minecraft's HUD areas in the editor" (on). Persisting it needs one
 * field in {@code control-center.json}; until then a change lasts until Minecraft quits. Render thread.
 */
public final class CenterPrefs {
	private static boolean editorAreas = true;

	private CenterPrefs() {
	}

	/** Whether the HUD editor opens with Minecraft's own HUD areas outlined. */
	public static boolean editorAreas() {
		return editorAreas;
	}

	public static void setEditorAreas(boolean on) {
		editorAreas = on;
	}
}
