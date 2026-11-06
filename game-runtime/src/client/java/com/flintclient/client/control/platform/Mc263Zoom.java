/*
 * Adapted from Zoomify by isXander (https://github.com/isXander/Zoomify), licensed under the GNU Lesser General Public
 * License v3.0 (text in META-INF/licenses/LGPL-3.0-Zoomify.txt). Changed for Flint: settings from Flint's Zoom module, keys
 * read through Flint's key settings, presets applied from a setting.
 */
package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.feature.ZoomifyZoom;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.SettingDescriptor;
import com.flintclient.client.control.model.mods.PlayerMods.Zoom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;

/**
 * Zoom, as Zoomify does it: the zoom key (hold or toggle) eases the field of view in and out over set times along chosen
 * curves; the wheel zooms further in steps that glide; the mouse slows with the zoom (relative sensitivity) and can glide
 * (cinematic camera); view bobbing calms with the zoom; the spyglass combines with it, overrides it or is required for it,
 * with its overlay and sounds when asked; a separate secondary zoom toggles a slow zoom that hides the HUD. Ticked once a
 * client tick; the field of view is read every frame between ticks. Fault-isolated.
 */
public final class Mc263Zoom {
	private static final HookGuard GUARD = new HookGuard("zoom",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final int ZOOM = Mods.index(Zoom.ID);
	private static final ZoomifyZoom MAIN = new ZoomifyZoom(
		new ZoomifyZoom.Transitioned(() -> ZoomifyZoom.Transition.of(s().option(Zoom.IN_TRANSITION)),
			() -> ZoomifyZoom.Transition.of(s().option(Zoom.OUT_TRANSITION)), () -> s().decimal(Zoom.IN_TIME), () -> s().decimal(Zoom.OUT_TIME)),
		new ZoomifyZoom.Smooth(() -> 1.0 + s().integer(Zoom.SCROLL_SMOOTHNESS) / 100.0 * (0.1 - 1.0)),
		() -> s().integer(Zoom.LEVEL), () -> Math.round(s().decimal(Zoom.PER_STEP) * 100), () -> s().integer(Zoom.SCROLL_STEPS));
	private static final ZoomifyZoom SECONDARY = new ZoomifyZoom(
		new ZoomifyZoom.Timed(() -> s().decimal(Zoom.SECONDARY_IN), () -> s().decimal(Zoom.SECONDARY_OUT)), ZoomifyZoom.INSTANT,
		() -> s().integer(Zoom.SECONDARY_AMOUNT), () -> 100, () -> 0);
	private static boolean zooming;
	private static boolean secondaryZooming;
	private static int scrollSteps;
	private static double previousDivisor = 1;
	private static boolean shouldPlaySound;
	private static boolean keyWas;
	private static boolean secondaryWas;

	private Mc263Zoom() {
	}

	private static ModuleState s() {
		return Mods.state(ZOOM);
	}

	private static boolean on() {
		return GUARD.on() && Mods.on(ZOOM);
	}

	/** Whether the zoom key is zooming now (for other modules, and the dev tour). */
	public static boolean zooming() {
		return zooming;
	}

	/** Each client tick: the keys, the spyglass rules and sounds, then both zooms take a step. */
	static void tick(boolean playing) {
		if (!GUARD.on()) {
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			boolean on = Mods.on(ZOOM) && mc.player != null;
			ModuleState s = s();
			if (on) {
				applyPreset(s);
			}
			boolean prevZooming = zooming;
			boolean held = on && Mc263Features.keyHeld(s.text(Zoom.KEY));
			if (!on) {
				zooming = false;
			} else if (s.option(Zoom.MODE) == 0) {
				// Hold: as a key mapping, which reads nothing while a screen is open.
				zooming = held && playing;
			} else if (held && !keyWas && playing) {
				zooming = !zooming;
			}
			keyWas = held;
			boolean secondaryHeld = on && Mc263Features.keyHeld(s.text(Zoom.SECONDARY_KEY));
			if (!on) {
				secondaryZooming = false;
			} else if (secondaryHeld && !secondaryWas && playing) {
				secondaryZooming = !secondaryZooming;
			}
			secondaryWas = secondaryHeld;
			if (on) {
				spyglass(mc, s, prevZooming);
			}
			MAIN.tick(zooming, scrollSteps, 0.05);
			SECONDARY.tick(secondaryZooming, scrollSteps, 0.05);
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	private static void spyglass(Minecraft mc, ModuleState s, boolean prevZooming) {
		Entity camera = mc.getCameraEntity();
		if (!(camera instanceof AbstractClientPlayer player)) {
			return;
		}
		int behaviour = s.option(Zoom.SPYGLASS);
		switch (behaviour) {
			case 2 -> {
				if (!player.isHolding(Items.SPYGLASS)) {
					zooming = false;
				}
			}
			case 3 -> {
				if (!carrying(player)) {
					zooming = false;
				}
			}
			case 1 -> {
				if (player.isScoping()) {
					zooming = zooming && mc.options.getCameraType().isFirstPerson();
				}
			}
			default -> {
			}
		}
		boolean requiresSpyglass = behaviour != 0;
		if (requiresSpyglass && player.isScoping()) {
			zooming = true;
		}
		if (shouldPlaySound && !zooming && prevZooming) {
			player.playSound(SoundEvents.SPYGLASS_STOP_USING, 1.0F, 1.0F);
		}
		shouldPlaySound = switch (s.option(Zoom.SPYGLASS_SOUND)) {
			case 0 -> false;
			case 1 -> true;
			case 2 -> player.isScoping() || requiresSpyglass && zooming && player.isHolding(Items.SPYGLASS);
			default -> overlayShown(player, mc.options.getCameraType().isFirstPerson() && player.isScoping()) && requiresSpyglass;
		};
		if (shouldPlaySound && zooming && !prevZooming) {
			player.playSound(SoundEvents.SPYGLASS_USE, 1.0F, 1.0F);
		}
	}

	private static boolean carrying(AbstractClientPlayer player) {
		Inventory inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (inventory.getItem(i).is(Items.SPYGLASS)) {
				return true;
			}
		}
		return false;
	}

	private static boolean overlayShown(AbstractClientPlayer player, boolean usingSpyglass) {
		return switch (s().option(Zoom.SPYGLASS_OVERLAY)) {
			case 0 -> false;
			case 3 -> zooming;
			case 1 -> usingSpyglass || zooming && player.isHolding(Items.SPYGLASS) && s().option(Zoom.SPYGLASS) != 0;
			default -> zooming && carrying(player);
		};
	}

	/** Zoomify's presets: a choice applied at once, then the row goes back to "Choose…". */
	private static void applyPreset(ModuleState s) {
		int preset = s.option(Zoom.PRESET);
		if (preset == 0) {
			return;
		}
		for (SettingDescriptor<?> setting : s.descriptor().settings()) {
			if (setting != Zoom.KEY && setting != Zoom.SECONDARY_KEY) {
				s.resetSetting(setting);
			}
		}
		if (preset == 2) {
			s.set(Zoom.IN_TRANSITION, 0);
			s.set(Zoom.OUT_TRANSITION, 0);
			s.set(Zoom.SCROLL, false);
			s.set(Zoom.RELATIVE_SENSITIVITY, 0);
			s.set(Zoom.RELATIVE_BOBBING, false);
			s.set(Zoom.CINEMATIC, 100);
		} else if (preset == 3) {
			s.set(Zoom.IN_TIME, 0.25F);
			s.set(Zoom.OUT_TIME, 0.25F);
			s.set(Zoom.RELATIVE_SENSITIVITY, 50);
			s.set(Zoom.RELATIVE_BOBBING, false);
			s.set(Zoom.SCROLL_SMOOTHNESS, 25);
		}
		s.set(Zoom.PRESET, 0);
		ControlState.settings().recordChange(s.id());
		ControlState.saveAll();
		String name = preset == 2 ? "OptiFine" : preset == 3 ? "OK Zoomer" : "Default";
		com.flintclient.client.compat.McCompat.hud().setOverlayMessage(Component.literal("Zoom applied the " + name + " preset."), false);
	}

	/** The field of view divided by both zooms ({@code Camera.calculateFov}, or 1.21.x's world FOV). */
	public static float fov(float fov) {
		if (!GUARD.on()) {
			return fov;
		}
		try {
			float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
			if (!zooming) {
				if (!Mods.on(ZOOM) || !s().bool(Zoom.RETAIN)) {
					scrollSteps = 0;
				}
				MAIN.reset();
			}
			double main = MAIN.divisor(partial);
			previousDivisor = main;
			double divisor = main * SECONDARY.divisor(partial);
			return divisor == 1.0 ? fov : (float) (fov / divisor);
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return fov;
		}
	}

	/** The hand's field of view: zoomed too with Affect hand FOV on (as Zoomify's default). */
	public static float hudFov(float fov) {
		return on() && s().bool(Zoom.AFFECT_HAND) ? fov(fov) : fov;
	}

	/** A wheel step while zooming with scroll zoom: one zoom step instead of a hotbar slot. True when taken. */
	public static boolean scroll(double amount) {
		if (!on() || !zooming || amount == 0 || !s().bool(Zoom.SCROLL)) {
			return false;
		}
		scrollSteps = Math.clamp(scrollSteps + (amount > 0 ? 1 : -1), MAIN.minScrollTiers(), MAIN.maxScrollTiers());
		return true;
	}

	/** Mouse sensitivity (Minecraft's option, before its curve), lowered with the zoom: relative sensitivity. */
	public static double sensitivity(double value) {
		if (!on()) {
			return value;
		}
		return value / (1.0 + s().integer(Zoom.RELATIVE_SENSITIVITY) / 100.0 * (previousDivisor - 1.0));
	}

	/** Whether the camera glides: Minecraft's own cinematic camera, the secondary zoom, or zooming with a cinematic camera. */
	public static boolean smoothCamera(boolean original) {
		return original || on() && (secondaryZooming || zooming && s().integer(Zoom.CINEMATIC) > 0);
	}

	/**
	 * The cinematic camera's smoothing while zooming: more glide the higher the percentage. Minecraft's smoother moves the
	 * camera this share of the way still to go each frame; past 1 it overshoots and swings back, and past 2 each swing is
	 * bigger than the last, so the camera turns faster and faster on its own (a low percentage at a low frame rate and a
	 * high sensitivity got there). Below 100% the share is kept at most 1 (no glide at all), never past Minecraft's own.
	 */
	public static double cinematicSmoother(double smoother) {
		if (!on() || !zooming || s().integer(Zoom.CINEMATIC) <= 0) {
			return smoother;
		}
		return stableSmoother(smoother, s().integer(Zoom.CINEMATIC));
	}

	/** {@code smoother} for a cinematic percentage, kept where Minecraft's smoother settles instead of swinging (see above). */
	static double stableSmoother(double smoother, int percent) {
		double wanted = smoother / (percent / 100.0);
		return wanted <= smoother ? wanted : Math.min(wanted, Math.max(smoother, 1.0));
	}

	/** Whether the spyglass slows the mouse: only when the zooms combine. */
	public static boolean spyglassSensitivity(boolean scoping) {
		return on() && s().option(Zoom.SPYGLASS) != 0 ? false : scoping;
	}

	/** The spyglass's own field of view multiplier (0.1): none unless the zooms combine. */
	public static float spyglassFov(float multiplier) {
		return on() && s().option(Zoom.SPYGLASS) != 0 ? 1.0F : multiplier;
	}

	/** View bobbing's strength, calmer while zoomed with relative view bobbing. */
	public static float bob(float bob) {
		if (!on() || !s().bool(Zoom.RELATIVE_BOBBING)) {
			return bob;
		}
		return (float) (bob / (1.0 + 0.2 * (previousDivisor - 1.0)));
	}

	/** Whether the spyglass overlay is drawn ({@code usingSpyglass}: Minecraft would draw it). */
	public static boolean spyglassOverlay(boolean usingSpyglass) {
		Minecraft mc = Minecraft.getInstance();
		if (!on() || mc.player == null) {
			return usingSpyglass;
		}
		return overlayShown(mc.player, usingSpyglass);
	}

	/** Whether the spyglass plays its own zoom-in sound (Zoomify keeps it unless sounds are off or match a hidden overlay). */
	public static boolean spyglassSound() {
		if (!on()) {
			return true;
		}
		int sound = s().option(Zoom.SPYGLASS_SOUND);
		return sound != 0 && (sound != 3 || s().option(Zoom.SPYGLASS_OVERLAY) != 0);
	}

	/** Whether the HUD is drawn: the secondary zoom hides it while it zooms, when asked. */
	public static boolean hudShown() {
		return !(on() && secondaryZooming && s().bool(Zoom.SECONDARY_HIDE_HUD));
	}
}
