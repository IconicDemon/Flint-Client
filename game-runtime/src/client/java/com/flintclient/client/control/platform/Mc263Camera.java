package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.feature.CameraDistance;
import com.flintclient.client.control.feature.CameraViews;
import com.flintclient.client.control.feature.Features;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.feature.ZoomState;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.CameraMods;
import java.util.Locale;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 26.3's side of Snaplook, Auto Perspective and the Camera module. The views are polled each frame after
 * Freelook (which they never interrupt); the Camera module's numbers are read by {@code CameraMixin} as the third-person
 * camera is placed. A distance changed with the keys or the wheel is written to the profile a second after the last change.
 * Fault-isolated like every feature hook; render thread only, allocation-free per frame.
 */
public final class Mc263Camera {
	private static final int SNAPLOOK = Mods.index(CameraMods.Snaplook.ID);
	private static final int AUTO = Mods.index(CameraMods.AutoPerspective.ID);
	private static final int CAMERA = Mods.index(CameraMods.Camera.ID);
	private static final CameraType[] VIEWS = CameraType.values();
	private static final HookGuard GUARD = new HookGuard("camera views",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final ThirdPerson THIRD = new ThirdPerson();
	private static boolean upWas;
	private static boolean downWas;
	/** When to save a distance the keys or wheel changed (nanoTime), or 0. */
	private static long saveAt;

	private Mc263Camera() {
	}

	/** The Camera module's third-person adjustments for this frame. One instance, overwritten each frame. */
	public static final class ThirdPerson {
		/** Degrees added to the camera's pitch and yaw around you. */
		public float pitch;
		public float yaw;
		/** Blocks to the camera's right and up. */
		public float right;
		public float up;
		/** Times Minecraft's distance. */
		public float scale;
	}

	/** Once per frame, after Freelook: the view Snaplook or Auto Perspective wants, applied (or given back). */
	static void pollViews(boolean freelook) {
		if (!GUARD.on() || freelook) {
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			LocalPlayer player = mc.player;
			int current = mc.options.getCameraType().ordinal();
			if (player == null) {
				apply(mc, Features.VIEWS.release(current));
				return;
			}
			int wanted = CameraViews.NONE;
			if (Mods.on(SNAPLOOK)) {
				ModuleState snap = Mods.state(SNAPLOOK);
				boolean hold = snap.bool(CameraMods.Snaplook.HOLD);
				// Presses count while playing; a held view lasts through a screen opened while holding.
				boolean live = Mc263Features.gameplay() || hold && Features.VIEWS.holding();
				wanted = Features.VIEWS.snaplook(hold, live && Mc263Features.keyHeld(snap.text(CameraMods.Snaplook.KEY)),
					snap.option(CameraMods.Snaplook.VIEW) + 1, live && Mc263Features.keyHeld(snap.text(CameraMods.Snaplook.SECOND_KEY)),
					snap.option(CameraMods.Snaplook.SECOND_VIEW) + 1);
			} else {
				Features.VIEWS.snaplook(true, false, 0, false, 0);
			}
			if (wanted == CameraViews.NONE && Mods.on(AUTO)) {
				wanted = autoView(player, Mods.state(AUTO));
			}
			apply(mc, Features.VIEWS.update(wanted, current));
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	private static int autoView(LocalPlayer player, ModuleState auto) {
		if (auto.bool(CameraMods.AutoPerspective.ELYTRA) && player.isFallFlying()) {
			return auto.option(CameraMods.AutoPerspective.ELYTRA_VIEW);
		}
		if (auto.bool(CameraMods.AutoPerspective.VEHICLE) && player.isPassenger()) {
			return auto.option(CameraMods.AutoPerspective.VEHICLE_VIEW);
		}
		if (auto.bool(CameraMods.AutoPerspective.SWIMMING) && player.isSwimming()) {
			return auto.option(CameraMods.AutoPerspective.SWIMMING_VIEW);
		}
		return CameraViews.NONE;
	}

	private static void apply(Minecraft mc, int view) {
		if (view >= 0 && view < VIEWS.length) {
			mc.options.setCameraType(VIEWS[view]);
		}
	}

	/** Each client tick: the distance keys, and saving a changed distance once it settles. */
	static void tick() {
		if (!GUARD.on()) {
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			boolean play = Mods.on(CAMERA) && mc.player != null && Mc263Features.gameplay();
			ModuleState camera = Mods.state(CAMERA);
			boolean up = play && Mc263Features.keyHeld(camera.text(CameraMods.Camera.DISTANCE_UP));
			boolean down = play && Mc263Features.keyHeld(camera.text(CameraMods.Camera.DISTANCE_DOWN));
			if (up && !upWas) {
				nudge(mc, camera, 1);
			} else if (down && !downWas) {
				nudge(mc, camera, -1);
			}
			upWas = up;
			downWas = down;
			if (saveAt != 0 && System.nanoTime() - saveAt >= 0) {
				saveAt = 0;
				ControlState.saveAll();
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** A wheel notch while playing: true when the Camera module took it (its scroll key held, in third person). */
	static boolean scroll(double amount) {
		if (!GUARD.on() || amount == 0 || !Mods.on(CAMERA)) {
			return false;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			ModuleState camera = Mods.state(CAMERA);
			if (mc.options.getCameraType().isFirstPerson() || !Mc263Features.keyHeld(camera.text(CameraMods.Camera.SCROLL_KEY))) {
				return false;
			}
			// Wheel up brings the camera closer, as zooming in.
			nudge(mc, camera, amount > 0 ? -1 : 1);
			return true;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return false;
		}
	}

	private static void nudge(Minecraft mc, ModuleState camera, int steps) {
		float next = CameraDistance.stepped(camera.decimal(CameraMods.Camera.DISTANCE), camera.decimal(CameraMods.Camera.INCREMENT), steps,
			CameraMods.Camera.DISTANCE.min(), CameraMods.Camera.DISTANCE.max());
		if (camera.set(CameraMods.Camera.DISTANCE, next)) {
			ControlState.settings().recordChange(camera.id());
			saveAt = System.nanoTime() + 1_000_000_000L;
			if (saveAt == 0) {
				saveAt = 1;
			}
		}
		com.flintclient.client.compat.McCompat.hud().setOverlayMessage(Component.literal(String.format(Locale.ROOT, "Camera distance %.2f",
			camera.decimal(CameraMods.Camera.DISTANCE))), false);
	}

	/** From {@code CameraMixin} as the third-person camera is placed: the adjustments, or null when the module is off. */
	public static @Nullable ThirdPerson thirdPerson() {
		if (!GUARD.on() || !Mods.on(CAMERA)) {
			return null;
		}
		try {
			ModuleState camera = Mods.state(CAMERA);
			boolean smooth = camera.bool(CameraMods.Camera.SMOOTH) && !com.flintclient.client.ui.kit.Kit.reduced();
			float distance = Features.CAMERA_DISTANCE.at(camera.decimal(CameraMods.Camera.DISTANCE), System.nanoTime(), smooth,
				ZoomState.Easing.of(camera.option(CameraMods.Camera.EASING)));
			// Minecraft's own distance is 4 blocks (times your size and the camera-distance attribute, which this keeps).
			THIRD.scale = distance / 4F;
			THIRD.pitch = camera.decimal(CameraMods.Camera.X_ROTATION);
			THIRD.yaw = camera.decimal(CameraMods.Camera.Y_ROTATION);
			THIRD.right = camera.decimal(CameraMods.Camera.X_OFFSET);
			THIRD.up = camera.decimal(CameraMods.Camera.Y_OFFSET);
			return THIRD;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return null;
		}
	}
}
