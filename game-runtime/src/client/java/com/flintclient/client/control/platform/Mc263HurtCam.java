package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.KeySetting;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.PlayerMods.HurtCam;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * Hurt Cam, as BetterHurtCam by uku (MIT) does it: every turn of Minecraft's hurt camera ({@code GameRenderer.bobHurt}: the
 * death roll, the turn to the hit's direction, the tilt and the turn back) times the multiplier; Hurtcam enabled off skips
 * it all; the Old type tilts the same way whatever hit you; the hearts' blink can be turned off. Its three keys toggle and
 * step the multiplier, and say so above the hotbar as BetterHurtCam does. Fault-isolated; allocation-free per frame.
 */
public final class Mc263HurtCam {
	private static final HookGuard GUARD = new HookGuard("hurt cam",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final int HURT_CAM = Mods.index(HurtCam.ID);
	private static boolean toggleHeld;
	private static boolean plusHeld;
	private static boolean minusHeld;

	private Mc263HurtCam() {
	}

	private static boolean on() {
		return GUARD.on() && Mods.on(HURT_CAM);
	}

	/** {@code bobHurt}'s start: false skips it all (Hurtcam enabled off). */
	public static boolean bobHurt() {
		return !on() || Mods.state(HURT_CAM).bool(HurtCam.ENABLED);
	}

	/** One of {@code bobHurt}'s four turns, in degrees, times the multiplier. */
	public static float angle(float degrees) {
		return on() ? degrees * Mods.state(HURT_CAM).decimal(HurtCam.MULTIPLIER) : degrees;
	}

	/** The hit's direction as {@code bobHurt} reads it: Animations' rule first, then the Old type's 0. */
	public static float hurtDir(float direction) {
		float dir = Mc263Tools.hitDirection(direction);
		return on() && Mods.state(HURT_CAM).option(HurtCam.TYPE) == 0 ? 0 : dir;
	}

	/** Whether the hearts blink (Health bar blinking off: never). */
	public static boolean heartBlink(boolean blinking) {
		return blinking && (!on() || Mods.state(HURT_CAM).bool(HurtCam.HEART_BLINK));
	}

	/** Each client tick: the three keys, while you play. */
	static void tick(boolean playing) {
		if (!GUARD.on()) {
			return;
		}
		try {
			boolean active = playing && Mods.on(HURT_CAM);
			ModuleState s = Mods.state(HURT_CAM);
			boolean toggle = active && held(s, HurtCam.TOGGLE);
			if (toggle && !toggleHeld) {
				boolean value = !s.bool(HurtCam.ENABLED);
				if (s.set(HurtCam.ENABLED, value)) {
					save(s);
				}
				say(Component.literal("Hurtcam ").append(value ? Component.literal("ON").withStyle(ChatFormatting.BOLD, ChatFormatting.GREEN)
					: Component.literal("OFF").withStyle(ChatFormatting.BOLD, ChatFormatting.RED)));
			}
			toggleHeld = toggle;
			boolean plus = active && held(s, HurtCam.PLUS);
			if (plus && !plusHeld) {
				step(s, 0.1F);
			}
			plusHeld = plus;
			boolean minus = active && held(s, HurtCam.MINUS);
			if (minus && !minusHeld) {
				step(s, -0.1F);
			}
			minusHeld = minus;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	private static void step(ModuleState s, float by) {
		float value = HurtCam.MULTIPLIER.clampFloat(s.decimal(HurtCam.MULTIPLIER) + by);
		if (s.set(HurtCam.MULTIPLIER, value)) {
			save(s);
		}
		say(Component.literal("Hurtcam multiplier modified to ").append(Component.literal(String.format(Locale.ROOT, "%.2f",
			s.decimal(HurtCam.MULTIPLIER))).withStyle(ChatFormatting.BOLD, ChatFormatting.DARK_AQUA)));
	}

	private static boolean held(ModuleState s, KeySetting key) {
		String name = s.text(key);
		return !name.isEmpty() && Mc263Features.keyHeld(name);
	}

	private static void save(ModuleState s) {
		ControlState.settings().recordChange(s.id());
		ControlState.saveAll();
	}

	private static void say(Component text) {
		com.flintclient.client.compat.McCompat.hud().setOverlayMessage(text, false);
	}
}
