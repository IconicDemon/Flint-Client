package com.flintclient.client.control.hud;

/**
 * The order of the FPS counter's parts: the number and the "FPS" label, the label after the number ("80 FPS") or,
 * reversed, before it ("FPS 80"), or left out ("80"); the Brackets display mode puts the whole line between
 * brackets ("[80 FPS]", "[FPS 80]"). The HUD draws its parts in exactly this order; {@link #compose} writes the line as
 * a string (for narration and tests). Plain logic, no Minecraft types.
 */
public final class FpsText {
	/** The label's text. */
	public static final String LABEL = "FPS";

	private FpsText() {
	}

	/** Whether the label goes before the number. */
	public static boolean labelFirst(boolean showLabel, boolean reversed) {
		return showLabel && reversed;
	}

	/** Whether the label goes after the number. */
	public static boolean labelAfter(boolean showLabel, boolean reversed) {
		return showLabel && !reversed;
	}

	/** The line for {@code number} (already formatted, or "—" while unknown). */
	public static String compose(String number, boolean showLabel, boolean reversed, boolean brackets) {
		String line = labelFirst(showLabel, reversed) ? LABEL + " " + number
			: labelAfter(showLabel, reversed) ? number + " " + LABEL : number;
		return brackets ? "[" + line + "]" : line;
	}
}
