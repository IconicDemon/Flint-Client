package com.flintclient.client.ui.screen;

import com.flintclient.client.FlintClient;
import com.flintclient.client.mc.GuiGraphicsExtractor;
import com.flintclient.client.mc.TitleScreen;
import com.flintclient.client.ui.Motion;
import com.flintclient.client.ui.widget.MenuScaled;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The blend between Flint's main menu and Minecraft's title screen, both ways, on Minecraft 1.8.9: the same blend as on every
 * line (the menu picked is shown at once and the one left behind is drawn with it for a moment, fading away; the switch and
 * the cards both menus have stay put and sharp), where Minecraft's title screen is 1.8.9's own (drawn through its own
 * {@code render}) instead of newer Minecraft's. Render thread.
 */
public final class MenuBlend {
	/** How long the blend takes. */
	static final long MILLIS = 520;
	/** The share of it Flint's controls take to go (at its start) or come (at its end). */
	static final float CONTROLS = 0.55F;
	/** How far Flint's menu drifts towards you as it dissolves (and settles from as it comes in). */
	static final float DRIFT = 0.035F;

	/** Going to Vanilla: Flint's menu, drawn over Minecraft's title screen while it fades. */
	private static @Nullable FlintTitleScreen leavingFlint;
	/** Coming back to Flint: Minecraft's title screen, drawn under Flint's menu while it fades in. */
	private static net.minecraft.client.gui.screen.@Nullable Screen leavingVanilla;
	private static long start;
	private static boolean drawingGhost;

	private MenuBlend() {
	}

	private static long now() {
		return System.nanoTime() / 1_000_000L;
	}

	/** Flint's switch picked Vanilla: Minecraft's title screen is shown at once and Flint's menu dissolves over it. */
	static void toVanilla(FlintTitleScreen from) {
		finish();
		com.flintclient.client.ui.widget.MenuToggle.slideTo(true);
		FlintTitleScreen.remember(true);
		if (!Motion.reduced()) {
			leavingFlint = from;
			start = now();
		}
		com.flintclient.client.mc.Minecraft.getInstance().gui.setScreen(new TitleScreen());
	}

	/** The switch on Minecraft's title screen picked Flint: Flint's menu is shown at once and fades in over it. */
	public static void toFlint(net.minecraft.client.gui.screen.Screen from) {
		finish();
		com.flintclient.client.ui.widget.MenuToggle.slideTo(false);
		FlintTitleScreen.remember(false);
		if (!Motion.reduced()) {
			leavingVanilla = from;
			start = now();
		}
		// Minecraft's title screen asked for again: the 1.8.9 MinecraftMixin shows Flint's menu in its place now.
		com.flintclient.client.mc.Minecraft.getInstance().gui.setScreen(new TitleScreen());
	}

	public static boolean drawingGhost() {
		return drawingGhost;
	}

	static boolean leaving(FlintTitleScreen screen) {
		return leavingFlint == screen;
	}

	public static boolean coveringVanilla() {
		return leavingFlint != null;
	}

	private static float raw() {
		return Math.min(1.0F, Math.max(0.0F, (now() - start) / (float) MILLIS));
	}

	private static boolean done() {
		return now() - start >= MILLIS;
	}

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
	 * screen's Flint parts ({@code extras}: the switch and the cards), which stay sharp on top. The pointer is in GUI units.
	 */
	public static void paintOverVanilla(GuiGraphicsExtractor graphics, List<MenuScaled> extras, int mouseX, int mouseY, float partialTick) {
		FlintTitleScreen ghost = leavingFlint;
		if (drawingGhost) {
			return;
		}
		float t = ghost == null ? 1.0F : raw();
		if (ghost != null && t < 1.0F) {
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
		if (ghost != null && (t >= 1.0F || done())) {
			finish();
		}
	}

	/** From the start of Flint's menu's background: Minecraft's title screen as it was, under Flint's menu fading in. */
	static boolean paintVanillaUnder(GuiGraphicsExtractor graphics, float partialTick) {
		net.minecraft.client.gui.screen.Screen ghost = leavingVanilla;
		if (ghost == null || drawingGhost) {
			return false;
		}
		if (done()) {
			finish();
			return false;
		}
		drawingGhost = true;
		try {
			ghost.render(-1, -1, partialTick);
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

	static float flintShown() {
		return leavingVanilla == null ? 1.0F : Motion.smooth(raw());
	}

	static float flintControlsShown() {
		return leavingVanilla == null ? 1.0F : Motion.smooth(Math.max(0.0F, (raw() - (1.0F - CONTROLS)) / CONTROLS));
	}
}
