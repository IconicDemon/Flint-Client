package com.flintclient.client.ui.screen;

import com.flintclient.client.FlintClient;
import com.flintclient.client.ui.Motion;
import com.flintclient.client.ui.widget.MenuScaled;
import com.flintclient.client.ui.widget.MenuToggle;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The blend between Flint's main menu and Minecraft's title screen, both ways: the menu picked is shown at once and the one
 * left behind is drawn with it for a moment, fading away, so the two really cross (Minecraft's panorama shows through Flint's
 * artwork as it goes, Flint's buttons drift forward and dissolve, or settle in as Minecraft's fade). What both menus have in
 * the same place (the menu switch, Daily rewards and What's new) stays put and sharp throughout. Flint's side can be drawn
 * at any opacity, so it is always the part that fades: over Minecraft's screen going to Vanilla, under its own drawing (with
 * Minecraft's screen beneath) coming back. Render thread.
 */
public final class MenuBlend {
	/** How long the blend takes. */
	static final long MILLIS = 520;
	/**
	 * The share of it Flint's controls take to go (at its start) or come (at its end), while the artwork behind them blends
	 * all the way through: the two menus' buttons barely overlap.
	 */
	static final float CONTROLS = 0.55F;
	/** How far Flint's menu drifts towards you as it dissolves (and settles from as it comes in). */
	static final float DRIFT = 0.035F;

	/** Going to Vanilla: Flint's menu, drawn over Minecraft's title screen while it fades. */
	private static @Nullable FlintTitleScreen leavingFlint;
	/** Coming back to Flint: Minecraft's title screen, drawn under Flint's menu while it fades in. */
	private static @Nullable Screen leavingVanilla;
	private static long start;
	/** True while a menu left behind is being drawn (its own blend hooks do nothing then). */
	private static boolean drawingGhost;

	private MenuBlend() {
	}

	/** Flint's switch picked Vanilla: Minecraft's title screen is shown at once and Flint's menu dissolves over it. */
	static void toVanilla(FlintTitleScreen from) {
		finish();
		MenuToggle.slideTo(true);
		FlintTitleScreen.remember(true);
		if (!Motion.reduced()) {
			leavingFlint = from;
			start = Util.getMillis();
		}
		Minecraft.getInstance().gui.setScreen(new TitleScreen());
	}

	/** The switch on Minecraft's title screen picked Flint: Flint's menu is shown at once and fades in over it. */
	public static void toFlint(Screen from) {
		finish();
		MenuToggle.slideTo(false);
		FlintTitleScreen.remember(false);
		if (!Motion.reduced()) {
			leavingVanilla = from;
			start = Util.getMillis();
		}
		// Gui.setScreen turns this into Flint's menu (FlintTitleScreen.titleFor).
		Minecraft.getInstance().gui.setScreen(new TitleScreen());
	}

	/** True while a menu is being drawn as the one left behind. */
	public static boolean drawingGhost() {
		return drawingGhost;
	}

	/** True while {@code screen} is Flint's menu fading away (it keeps what it draws until the blend ends). */
	static boolean leaving(FlintTitleScreen screen) {
		return leavingFlint == screen;
	}

	/** True while Flint's menu dissolves over Minecraft's title screen (the switch and cards there are drawn after it). */
	public static boolean coveringVanilla() {
		return leavingFlint != null;
	}

	/** How far the blend is (0..1, linear). */
	private static float raw() {
		return Math.min(1.0F, Math.max(0.0F, (Util.getMillis() - start) / (float) MILLIS));
	}

	private static boolean done() {
		return Util.getMillis() - start >= MILLIS;
	}

	/** Ends the blend: the menu left behind is let go. */
	static void finish() {
		FlintTitleScreen flint = leavingFlint;
		leavingFlint = null;
		leavingVanilla = null;
		if (flint != null) {
			flint.ghostDone();
		}
	}

	/**
	 * From the end of Minecraft's title screen's drawing: Flint's menu dissolving over it while the blend runs, then the
	 * screen's Flint parts ({@code extras}: the switch and the cards), which stay sharp on top.
	 */
	public static void paintOverVanilla(GuiGraphicsExtractor graphics, List<MenuScaled> extras, int mouseX, int mouseY, float partialTick) {
		FlintTitleScreen ghost = leavingFlint;
		if (drawingGhost || ghost == null) {
			return;
		}
		float t = raw();
		if (t < 1.0F) {
			graphics.nextStratum();
			drawingGhost = true;
			try {
				float controls = Motion.smooth(Math.min(1.0F, t / CONTROLS));
				ghost.paintGhost(graphics, 1.0F - Motion.smooth(t), 1.0F - controls, 1.0F + DRIFT * controls);
			} catch (RuntimeException | LinkageError e) {
				FlintClient.LOGGER.warn("Flint: the main menu blend stopped", e);
				t = 1.0F;
			} finally {
				drawingGhost = false;
			}
		}
		graphics.nextStratum();
		for (MenuScaled extra : extras) {
			if (extra.visible) {
				extra.paintInner(graphics, mouseX, mouseY, partialTick);
			}
		}
		if (t >= 1.0F || done()) {
			finish();
		}
	}

	/**
	 * From the start of Flint's menu's background: Minecraft's title screen as it was, under Flint's menu fading in. False
	 * once there is nothing to draw (no blend, or it is over).
	 */
	static boolean paintVanillaUnder(GuiGraphicsExtractor graphics, float partialTick) {
		Screen ghost = leavingVanilla;
		if (ghost == null || drawingGhost) {
			return false;
		}
		if (done()) {
			finish();
			return false;
		}
		drawingGhost = true;
		try {
			ghost.extractBackground(graphics, -1, -1, partialTick);
			graphics.nextStratum();
			ghost.extractRenderState(graphics, -1, -1, partialTick);
		} catch (RuntimeException | LinkageError e) {
			FlintClient.LOGGER.warn("Flint: the main menu blend stopped", e);
			finish();
			return false;
		} finally {
			drawingGhost = false;
		}
		graphics.nextStratum();
		return true;
	}

	/** How much of Flint's artwork shows while it comes in over Minecraft's menu (1 when it isn't blending). */
	static float flintShown() {
		return leavingVanilla == null ? 1.0F : Motion.smooth(raw());
	}

	/** How much of Flint's controls show while its menu comes in: they come last (1 when it isn't blending). */
	static float flintControlsShown() {
		return leavingVanilla == null ? 1.0F : Motion.smooth(Math.max(0.0F, (raw() - (1.0F - CONTROLS)) / CONTROLS));
	}
}
