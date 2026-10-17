package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.feature.TooltipScroll;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.UtilityMods.ScrollableTooltips;
import com.flintclient.client.ui.kit.Kit;
import org.joml.Matrix3x2fStack;
import org.joml.Vector2i;
import org.joml.Vector2ic;

/**
 * Minecraft 26.3's side of Scrollable Tooltips: called from {@code GuiGraphicsExtractor.tooltip} (where every screen
 * tooltip is drawn) and from the mouse wheel while a screen is open. The offset and scale are the version-neutral
 * {@link TooltipScroll}'s. Render thread; fault-isolated.
 */
public final class Mc263Tooltips {
	private static final int MODULE = Mods.index(ScrollableTooltips.ID);
	private static final HookGuard GUARD = new HookGuard("scrollable tooltips",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	public static final TooltipScroll SCROLL = new TooltipScroll();

	/** Set for the tooltip being drawn now: where its scaled box starts, and its scale (1: no transform). */
	private static int drawX;
	private static int drawY;
	private static float drawScale = 1;

	private Mc263Tooltips() {
	}

	private static boolean on() {
		return GUARD.on() && Mods.on(MODULE);
	}

	/**
	 * Replaces the positioner's call for a tooltip of {@code w x h} units with {@code lines} lines: placed for its scaled
	 * size, then moved by the scroll offset. Returns null to let vanilla place it.
	 */
	public static Vector2ic place(Positioner vanilla, int screenW, int screenH, int mouseX, int mouseY, int w, int h, int lines) {
		drawScale = 1;
		if (!on()) {
			return null;
		}
		try {
			ModuleState state = Mods.state(MODULE);
			long now = System.nanoTime();
			SCROLL.begin(w, h, lines, now, state.bool(ScrollableTooltips.SNAP_START), state.integer(ScrollableTooltips.DEFAULT_SCALE) / 100F);
			float scale = SCROLL.scale();
			int sw = Math.max(1, Math.round(w * scale));
			int sh = Math.max(1, Math.round(h * scale));
			Vector2ic at = vanilla.position(screenW, screenH, mouseX, mouseY, sw, sh);
			SCROLL.place(at.x(), at.y(), sw, sh, screenW, screenH, state.bool(ScrollableTooltips.FREE), now,
				!state.bool(ScrollableTooltips.LINE_SHIFT) && !Kit.reduced());
			drawX = at.x() + SCROLL.offsetX();
			drawY = at.y() + SCROLL.offsetY();
			drawScale = scale;
			return new Vector2i(drawX, drawY);
		} catch (RuntimeException e) {
			GUARD.fail(e);
			drawScale = 1;
			return null;
		}
	}

	/** Right after the tooltip's pose is pushed: scales it about its top-left corner. */
	public static void transform(Matrix3x2fStack pose) {
		if (drawScale != 1) {
			pose.translate(drawX, drawY);
			pose.scale(drawScale, drawScale);
			pose.translate(-drawX, -drawY);
		}
	}

	/** A wheel movement in a screen: true when it moved or scaled the tooltip showing (the screen then doesn't scroll). */
	public static boolean scroll(double dx, double dy) {
		if (!on()) {
			return false;
		}
		try {
			if (!SCROLL.showing(System.nanoTime())) {
				return false;
			}
			ModuleState state = Mods.state(MODULE);
			boolean lineShift = state.bool(ScrollableTooltips.LINE_SHIFT);
			if (Mc263Features.keyHeld(state.text(ScrollableTooltips.SCALE_KEY))) {
				return SCROLL.zoom(dy != 0 ? dy : dx);
			}
			if (Mc263Features.keyHeld(state.text(ScrollableTooltips.HORIZONTAL_KEY))) {
				return SCROLL.scroll(false, dy != 0 ? dy : dx, lineShift);
			}
			boolean moved = false;
			if (dx != 0) {
				moved = SCROLL.scroll(false, -dx, lineShift);
			}
			String vertical = state.text(ScrollableTooltips.VERTICAL_KEY);
			if (dy != 0 && (vertical.isEmpty() || Mc263Features.keyHeld(vertical))) {
				moved |= SCROLL.scroll(true, dy, lineShift);
			}
			return moved;
		} catch (RuntimeException e) {
			GUARD.fail(e);
			return false;
		}
	}

	/** Vanilla's positioner, called with the scaled size. */
	@FunctionalInterface
	public interface Positioner {
		Vector2ic position(int screenW, int screenH, int x, int y, int w, int h);
	}
}
