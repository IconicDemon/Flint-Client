/*
 * Toast Control's rules, after ToastControl by Shadows_of_Fire (Brennan Ward) and its Fabric port by 1influence.
 * Copyright (c) 2019 Brennan Ward. MIT License: see META-INF/licenses/MIT-ToastControl.txt.
 */
package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.UtilityMods.ToastControl;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import net.minecraft.client.gui.components.toasts.RecipeToast;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.TutorialToast;
import org.joml.Matrix3x2fStack;

/**
 * Toast Control (ToastControl's rules), called from {@code ToastManagerMixin} and {@code ToastInstanceMixin}: which toasts
 * are blocked (by kind, all vanilla or all modded ones, by class, or every one), how many show at once, how long each must
 * stay, whether they slide, which side they come from (or down from the top, the newest first) and the offsets; the clear
 * key empties the screen and the queue. Flint adds the toast's own sound and a scale. The music "Now playing" toast is
 * Minecraft's own setting and is left alone. Fault-isolated.
 */
public final class Mc263Toasts {
	private static final int TOASTS = Mods.index(ToastControl.ID);
	private static final HookGuard GUARD = new HookGuard("toasts",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	/** ToastControl stacks toasts 32 units apart, whatever their height. */
	private static final int SLOT_HEIGHT = 32;
	/** The blocked classes as last read, and the text they were read from. */
	private static final List<Class<?>> BLOCKED = new ArrayList<>();
	private static String blockedFrom = "";
	private static boolean clearHeld;

	private Mc263Toasts() {
	}

	/** Minecraft's toast manager, by {@code ToastManagerMixin}: how many of the toasts showing came after {@code instance}. */
	public interface Showing {
		int flint$newer(Object instance);
	}

	private static boolean on() {
		return GUARD.on() && Mods.on(TOASTS);
	}

	/** Whether {@code toast} may be queued: ToastControl's checks, in its order. */
	public static boolean allow(Toast toast) {
		if (!on()) {
			return true;
		}
		try {
			ModuleState s = Mods.state(TOASTS);
			if (s.bool(ToastControl.PRINT_CLASSES)) {
				FlintClient.LOGGER.info("Toast class: {}", toast.getClass().getName());
			}
			if (s.bool(ToastControl.BLOCK_ALL)) {
				return false;
			}
			boolean vanilla = toast.getClass().getName().startsWith("net.minecraft.");
			if (s.bool(ToastControl.BLOCK_VANILLA) && vanilla || s.bool(ToastControl.BLOCK_MODDED) && !vanilla) {
				return false;
			}
			if (s.bool(ToastControl.BLOCK_ADVANCEMENTS) && toast instanceof AdvancementToast || s.bool(ToastControl.BLOCK_RECIPES) && toast instanceof RecipeToast
				|| s.bool(ToastControl.BLOCK_SYSTEM) && toast instanceof SystemToast || s.bool(ToastControl.BLOCK_TUTORIAL) && toast instanceof TutorialToast) {
				return false;
			}
			for (Class<?> blocked : blockedClasses(s.text(ToastControl.BLOCKED_CLASSES))) {
				if (blocked.isInstance(toast)) {
					return false;
				}
			}
			return true;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return true;
		}
	}

	/** The classes named in {@code text} (comma separated); a name that isn't a class is logged once and skipped. */
	private static List<Class<?>> blockedClasses(String text) {
		if (!text.equals(blockedFrom)) {
			blockedFrom = text;
			BLOCKED.clear();
			for (String name : text.split(",")) {
				String trimmed = name.strip();
				if (trimmed.isEmpty()) {
					continue;
				}
				try {
					BLOCKED.add(Class.forName(trimmed, false, Mc263Toasts.class.getClassLoader()));
				} catch (ClassNotFoundException | LinkageError e) {
					FlintClient.LOGGER.error("Invalid class string provided to toast control: {}", trimmed);
				}
			}
		}
		return BLOCKED;
	}

	/** Whether a toast that appears makes its own sound. */
	public static boolean sound() {
		return !on() || Mods.state(TOASTS).bool(ToastControl.SOUND);
	}

	/** How many toasts may show at once: Toast Count, or Minecraft's {@code vanilla}. */
	public static int slots(int vanilla) {
		return on() ? Mods.state(TOASTS).integer(ToastControl.TOAST_COUNT) : vanilla;
	}

	/** How long a toast is kept on screen whatever it wants, in milliseconds (Force Time's ticks); 0 for not at all. */
	public static long forcedMillis() {
		return on() ? Mods.state(TOASTS).integer(ToastControl.FORCE_TIME) * 50L : 0L;
	}

	/** Whether the toasts come down from the top, the newest first (each new one starting the others' slide again). */
	public static boolean topDown() {
		return on() && Mods.state(TOASTS).bool(ToastControl.TOP_DOWN);
	}

	/**
	 * Moves the pose for a toast the way ToastControl places it, in place of Minecraft's translate to
	 * {@code (vanillaX, vanillaY)}: from the right (or the left), sliding in unless No slide, at its slot 32 units apart, or
	 * down from the top at its place among the toasts showing ({@code newer} of them came after it); then the offsets, and
	 * Flint's scale about the screen's side. On Flint's main menu every toast starts below its top bar (the account and the
	 * icons at the top right, where toasts slide in).
	 */
	public static void place(Matrix3x2fStack pose, Toast toast, int slot, float portion, int screenWidth, int newer, float vanillaX, float vanillaY) {
		int inset = com.flintclient.client.ui.screen.FlintTitleScreen.toastInset();
		if (inset > 0) {
			pose.translate(0, inset);
		}
		if (!on()) {
			pose.translate(vanillaX, vanillaY);
			return;
		}
		try {
			ModuleState s = Mods.state(TOASTS);
			float visibility = s.bool(ToastControl.NO_SLIDE) ? 1.0F : portion;
			boolean left = s.bool(ToastControl.START_LEFT);
			int width = toast.width();
			float x;
			float y;
			if (s.bool(ToastControl.TOP_DOWN)) {
				x = left ? 0.0F : screenWidth - width;
				y = (newer - 1) * toast.height() + toast.height() * visibility;
			} else if (left) {
				x = -width + width * visibility;
				y = slot * SLOT_HEIGHT;
			} else {
				x = screenWidth - width * visibility;
				y = slot * SLOT_HEIGHT;
			}
			float scale = s.decimal(ToastControl.SCALE);
			float corner = left ? 0.0F : screenWidth;
			pose.translate(corner + s.integer(ToastControl.X_OFFSET), s.integer(ToastControl.Y_OFFSET));
			if (scale != 1.0F) {
				pose.scale(scale, scale);
			}
			pose.translate(x - corner, y);
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			pose.translate(vanillaX, vanillaY);
		}
	}

	/** Each client tick: the clear key, pressed while playing, removes every toast showing and waiting. */
	public static void tick() {
		if (!on()) {
			clearHeld = false;
			return;
		}
		try {
			boolean down = Mc263Features.gameplay() && Mc263Features.keyHeld(Mods.state(TOASTS).text(ToastControl.CLEAR_KEY));
			if (down && !clearHeld) {
				Minecraft.getInstance().gui.toastManager().clear();
			}
			clearHeld = down;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}
}
