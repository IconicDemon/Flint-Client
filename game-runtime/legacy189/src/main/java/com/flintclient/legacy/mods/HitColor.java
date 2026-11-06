package com.flintclient.legacy.mods;

import com.flintclient.client.control.model.mods.ToolMods;
import com.flintclient.legacy.module.Module;
import com.flintclient.legacy.module.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.living.LivingEntity;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * The colour a player or mob flashes when it is hurt: Minecraft's red at 30%, or any colour and strength you choose. Applied where
 * Minecraft sets its hurt tint, so it shows exactly when and where Minecraft's does. The 1.8.9 backend of Animations: of its
 * settings, 1.8.9 has the damage colour and Disable Camera Potion Particles (with its key) so far.
 */
public final class HitColor extends Module {
	public final Setting.Bool custom = bool(ToolMods.Animations.CUSTOM_DAMAGE);
	public final Setting.Color color = color(ToolMods.Animations.DAMAGE_COLOR);
	public final Setting.Int strength = integer(ToolMods.Animations.DAMAGE_STRENGTH);
	public final Setting.Bool noPotionParticles = bool(ToolMods.Animations.NO_POTION_PARTICLES);
	public final Setting.Key potionKey = key(ToolMods.Animations.POTION_KEY);
	private boolean potionKeyWas;

	public HitColor() {
		super("animations", "damage_tint");
	}

	@Override
	public void tick() {
		Minecraft mc = Minecraft.getInstance();
		int code = potionKey.code();
		boolean down = mc.screen == null && code != 0 && (code > 0 ? Keyboard.isKeyDown(code) : Mouse.isButtonDown(code + 100));
		if (down && !potionKeyWas) {
			noPotionParticles.set(!noPotionParticles.on());
			mc.gui.setOverlayMessage(noPotionParticles.on() ? "Own potion particles hidden" : "Own potion particles shown", false);
		}
		potionKeyWas = down;
	}

	/** Whether {@code entity}'s effect swirls are made: yours aren't in first person with Disable Camera Potion Particles. */
	public boolean potionParticles(LivingEntity entity) {
		Minecraft mc = Minecraft.getInstance();
		return !enabled() || !noPotionParticles.on() || entity != mc.player || mc.options.perspective != 0;
	}

	/** One part of the hurt tint (0 red, 1 green, 2 blue, 3 strength) for Minecraft's {@code original}. */
	public float part(int index, float original) {
		if (!enabled() || !custom.on()) {
			return original;
		}
		int rgb = color.rgb();
		switch (index) {
			case 0:
				return ((rgb >> 16) & 255) / 255F;
			case 1:
				return ((rgb >> 8) & 255) / 255F;
			case 2:
				return (rgb & 255) / 255F;
			default:
				return strength.value() / 100F;
		}
	}
}
