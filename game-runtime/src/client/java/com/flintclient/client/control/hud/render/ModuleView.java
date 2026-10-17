package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.model.ModuleRegistry;
import com.flintclient.client.control.model.ModuleState;
import org.jspecify.annotations.Nullable;

/**
 * Draws one HUD module. Each frame it is drawn: {@link #update} reads the data to show (throttled where
 * reading is expensive), {@link #measure} lays the content out for the pen's style and sets
 * {@link #width} × {@link #height} in pixels (0 × 0 when there is nothing to show), then {@link #draw} draws it
 * at a position. The renderer adds the style's panel and padding around it.
 *
 * <p>A view keeps per-surface state (eased key presses, cached key names), so the HUD and the previews each
 * have their own set of views. A view never holds the player or its level strongly from one frame to the next
 * ({@link #forget} drops what it read once there is no world). Allocation-free once warmed up. Render thread.
 */
abstract class ModuleView {
	/** In the world: live data; a module with nothing to show (no effects, no latency yet) is left out. */
	static final int HUD = 0;
	/** In the Control Center and setup: live data where there is some, em dashes where there isn't. */
	static final int PREVIEW = 1;
	/** Em dashes for everything: the size of a module the HUD editor shows as an outline. */
	static final int PLACEHOLDER = 2;
	/**
	 * A module's settings preview: fixed sample numbers (FPS shows {@code 80}), so its look can be judged steadily and is
	 * never mistaken for a measurement. Modules without sample numbers show what {@link #PREVIEW} shows.
	 */
	static final int SAMPLE = 3;
	/** Between the parts of one line ("144 FPS · 6.9 ms"). */
	static final HudText DOT = new HudText("·");

	final ModuleDescriptor descriptor;
	final int surface;
	int width;
	int height;

	ModuleView(String moduleId, int surface) {
		this.descriptor = ModuleRegistry.get(moduleId);
		this.surface = surface;
	}

	/** Reads what this draw shows; {@code mode} is {@link #HUD}, {@link #PREVIEW}, {@link #PLACEHOLDER} or {@link #SAMPLE}. */
	abstract void update(ModuleState state, int mode, long nowNanos);

	/** Lays the content out in {@code p}'s style and sets {@link #width} and {@link #height}. */
	abstract void measure(HudPen p, ModuleState state);

	/** Draws the content with its top-left corner at {@code (x, y)} (after {@link #measure} in the same frame). */
	abstract void draw(HudPen p, ModuleState state, int x, int y);

	/**
	 * Whether the module sets its whole look itself (colours, box, shadow) rather than following the shared style: FPS.
	 * Its look is then edited on its own settings page, not with the style controls of the HUD editor.
	 */
	boolean ownLook() {
		return false;
	}

	/** Whether the style's panel and padding go around the content (Keystrokes draws a panel per key instead). */
	boolean panelled() {
		return true;
	}

	/** Drops whatever was read from the world (the world was left), so nothing keeps it in memory. */
	void forget() {
	}

	/**
	 * Why "Show label" changes nothing for this module in {@code theme}, or null when it does (the inspector
	 * disables the switch with this reason rather than offering a control that does nothing).
	 */
	@Nullable String noLabelReason(HudThemeId theme) {
		return null;
	}

	/** Why the padding setting changes nothing for this module, or null when it does. */
	@Nullable String noPaddingReason() {
		return null;
	}

	/** Why the accent colour changes nothing for this module (it has no labels or highlights), or null when it does. */
	@Nullable String noAccentReason() {
		return null;
	}

	/**
	 * Writes the module list's one-line summary of what this module shows now ("144 FPS", "South") into
	 * {@code out} and returns its length, or -1 when there is no live data (no world, not on a server). Reads the
	 * game directly, not this view's per-frame state, and allocates nothing. {@code nowNanos} is the frame's time.
	 */
	int summary(ModuleState state, char[] out, long nowNanos) {
		return -1;
	}

	/** " · " between two parts of a line, the dot muted; returns where the next part starts. */
	static int separator(HudPen p, boolean draw, int x, int baseline) {
		int at = x + p.s.space;
		at = draw ? p.text(DOT, HudFaces.VALUE, at, baseline, p.s.muted) : at + p.width(DOT, HudFaces.VALUE);
		return at + p.s.space;
	}

	/** Why the module shows nothing right now although it is available (the HUD editor's outline label). */
	String emptyReason() {
		return "Nothing to show yet";
	}

	/** The height of {@code lines} lines of text in the pen's style. */
	static int linesHeight(HudPen p, int lines) {
		return lines <= 0 ? 0 : lines * p.s.lineH + (lines - 1) * p.s.lineGap;
	}
}
