package com.flintclient.client.platform;

import com.flintclient.client.control.hud.render.EffectOrder;
import com.flintclient.client.control.model.ModuleState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.living.player.LocalClientPlayerEntity;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.client.sound.instance.SimpleSoundInstance;
import net.minecraft.entity.living.effect.StatusEffect;
import net.minecraft.entity.living.effect.StatusEffectInstance;
import net.minecraft.resource.Identifier;

/**
 * The player's status effects for the Effects module, on Minecraft 1.8.9: the list, and each effect's type, time left, level,
 * colour, name and icon (the inventory's own icons). All of 1.8.9's effects show; one at its longest is endless. It has no Status Effect
 * Bars and no effect tiles of its own, so the tile is drawn as newer Minecraft draws it ({@link ItemPainter#tile}). Platform
 * class (see {@link Clock}). Render thread.
 */
public final class HudEffects {
	public static final int INFINITE = EffectOrder.INFINITE;
	private static final Identifier CHIME = new Identifier("note.pling");

	private HudEffects() {
	}

	private static StatusEffectInstance e(Object effect) {
		return (StatusEffectInstance) effect;
	}

	private static StatusEffect typeOf(Object effect) {
		int id = e(effect).getId();
		StatusEffect type = id >= 0 && id < StatusEffect.BY_ID.length ? StatusEffect.BY_ID[id] : null;
		return type != null ? type : StatusEffect.UNKNOWN;
	}

	public static int read(Object[] out) {
		Minecraft mc = Minecraft.getInstance();
		LocalClientPlayerEntity player = mc.world == null ? null : mc.player;
		if (player == null) {
			return 0;
		}
		int n = 0;
		for (StatusEffectInstance effect : player.getStatusEffects()) {
			if (n < out.length) {
				out[n] = effect;
			}
			n++;
		}
		return n;
	}

	public static int size() {
		Minecraft mc = Minecraft.getInstance();
		return mc.world == null || mc.player == null ? -1 : mc.player.getStatusEffects().size();
	}

	public static Object type(Object effect) {
		return typeOf(effect);
	}

	public static int remaining(Object effect) {
		return e(effect).isPermanent() ? INFINITE : e(effect).getDuration();
	}

	public static int duration(Object effect) {
		return e(effect).getDuration();
	}

	/** One the server sent at its longest (1.8.9's inventory shows it as **:**): it doesn't run out as far as you can tell. */
	public static boolean infinite(Object effect) {
		return e(effect).isPermanent();
	}

	public static boolean ambient(Object effect) {
		return e(effect).isAmbient();
	}

	public static boolean endsWithin(Object effect, int ticks) {
		return e(effect).getDuration() <= ticks;
	}

	public static int amplifier(Object effect) {
		return e(effect).getAmplifier();
	}

	public static int id(Object effect) {
		return e(effect).getId();
	}

	public static boolean beneficial(Object effect) {
		return !typeOf(effect).isHarmful();
	}

	public static int color(Object type) {
		return ((StatusEffect) type).getPotionColor();
	}

	public static String name(Object type) {
		return I18n.translate(((StatusEffect) type).getTranslationKey());
	}

	/** The effect's icon from the inventory's sheet (an effect without one shows the tile alone). */
	public static Object icon(Object effect) {
		StatusEffect type = typeOf(effect);
		return ItemPainter.effectIcon(type.hasIcon() ? type.getIconIndex() : -1);
	}

	public static Object tile(boolean ambient) {
		return ItemPainter.tile(ambient);
	}

	public static void chime(float volume) {
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.of(CHIME, 0.8F));
	}

	// ---------- Status Effect Bars: not on 1.8.9 ----------

	public static boolean barsOn() {
		return false;
	}

	public static boolean barLayout(ModuleState state, Object effect, int width, int height, float partialTick) {
		return false;
	}

	public static float barRect(int index) {
		return 0;
	}

	public static int barColor(ModuleState state, Object effect) {
		return 0;
	}

	public static int barBackground(ModuleState state) {
		return 0;
	}
}
