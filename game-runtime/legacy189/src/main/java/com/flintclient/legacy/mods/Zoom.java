package com.flintclient.legacy.mods;

import com.flintclient.client.control.model.mods.PlayerMods;
import com.flintclient.legacy.Flint;
import com.flintclient.legacy.module.Module;
import com.flintclient.legacy.module.Setting;
import net.minecraft.client.Minecraft;

/**
 * Zoom your view while holding (or after tapping) the Zoom key (C by default, in Minecraft's Controls too): the field of view
 * narrows by the zoom level, smoothly if you like; the mouse wheel zooms further in or out while zoomed, and the mouse slows
 * down with the zoom so aiming stays steady. The 1.8.9 backend of Zoom: its key (kept in step with Minecraft's Controls), the key
 * behaviour, the starting zoom, the smooth transition, the wheel's step, the relative sensitivity and the cinematic camera are
 * the shared settings.
 */
public final class Zoom extends Module {
	public final Setting.Key key = key(PlayerMods.Zoom.KEY);
	public final Setting.Choice mode = option(PlayerMods.Zoom.MODE);
	public final Setting.Int level = integer(PlayerMods.Zoom.LEVEL);
	public final Setting.Choice transition = option(PlayerMods.Zoom.IN_TRANSITION);
	public final Setting.Bool scroll = bool(PlayerMods.Zoom.SCROLL);
	public final Setting.Decimal perStep = decimal(PlayerMods.Zoom.PER_STEP, 10, "×");
	public final Setting.Int relative = integer(PlayerMods.Zoom.RELATIVE_SENSITIVITY);
	public final Setting.Int cinematic = integer(PlayerMods.Zoom.CINEMATIC);

	private boolean toggled;
	private boolean keyWasDown;
	private float scrollLevel = -1;
	/** The zoom shown now (1 = none) and when it was last eased. */
	private float current = 1;
	private long lastFrame;
	private boolean cinematicSet;
	private boolean cinematicBefore;

	public Zoom() {
		super("zoom", "zoom");
	}

	/** Whether zoom is wanted now (the key held, or toggled on). */
	public boolean active() {
		Minecraft mc = Minecraft.getInstance();
		return enabled() && mc.world != null && (mode.index() == 1 ? toggled : mc.screen == null && Flint.physicallyDown(Flint.zoomKey()));
	}

	@Override
	public void tick() {
		Minecraft mc = Minecraft.getInstance();
		boolean down = Flint.physicallyDown(Flint.zoomKey()) && mc.screen == null;
		if (mode.index() == 1 && down && !keyWasDown) {
			toggled = !toggled;
		}
		if (mode.index() != 1) {
			toggled = false;
		}
		keyWasDown = down;
		boolean on = active();
		if (!on) {
			scrollLevel = -1;
		}
		if (on && cinematic.value() > 0 && !cinematicSet) {
			cinematicBefore = mc.options.smoothCamera;
			mc.options.smoothCamera = true;
			cinematicSet = true;
		} else if ((!on || cinematic.value() == 0) && cinematicSet) {
			mc.options.smoothCamera = cinematicBefore;
			cinematicSet = false;
		}
	}

	@Override
	protected void onDisable() {
		toggled = false;
		if (cinematicSet) {
			Minecraft.getInstance().options.smoothCamera = cinematicBefore;
			cinematicSet = false;
		}
	}

	/** A wheel notch while zoomed: true when the zoom took it (so the hotbar doesn't scroll). */
	public boolean scrolled(int amount) {
		if (!active() || !scroll.on() || amount == 0) {
			return false;
		}
		float base = scrollLevel < 0 ? level.value() : scrollLevel;
		float step = Math.max(1.05F, perStep.decimal());
		scrollLevel = Math.max(1F, Math.min(50F, amount > 0 ? base * step : base / step));
		return true;
	}

	private float target() {
		return active() ? (scrollLevel < 0 ? level.value() : scrollLevel) : 1;
	}

	/** The zoom for this frame, eased toward the target on real frame time. */
	public float factor() {
		long now = System.nanoTime();
		float dt = lastFrame == 0 ? 0.016F : Math.min(0.1F, (now - lastFrame) / 1e9F);
		lastFrame = now;
		float t = target();
		if (transition.index() == 0) {
			current = t;
		} else {
			// Exponential approach in log space: the same feel at every level.
			float k = 1 - (float) Math.exp(-dt / 0.06);
			current = (float) Math.exp(Math.log(current) + (Math.log(t) - Math.log(current)) * k);
			if (Math.abs(current - t) < 0.001F) {
				current = t;
			}
		}
		return current;
	}

	/** The mouse sensitivity to use (Minecraft's 0..1 slider value) while zoomed by {@code factor}. */
	public float sensitivity(float original, float factor) {
		if (relative.value() == 0 || factor <= 1.001F) {
			return original;
		}
		float f = (original * 0.6F + 0.2F) / (float) Math.cbrt(factor * factor);
		float slowed = (f - 0.2F) / 0.6F;
		// 100% slows the mouse with the zoom; 0% leaves it as it is.
		return original + (slowed - original) * relative.value() / 100F;
	}
}
