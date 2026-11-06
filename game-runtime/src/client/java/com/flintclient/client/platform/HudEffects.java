package com.flintclient.client.platform;

import com.flintclient.client.control.hud.render.EffectOrder;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.platform.Mc263EffectBars;
import com.flintclient.client.control.platform.Mc263HudData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * The player's status effects for the Effects module: the list (effects the game shows an icon for), and each effect's type,
 * time left, level, colour, name and icon. An effect and its type are the engine's own objects, held as {@link Object}; a type
 * is compared by identity. Status Effect Bars' bar over an icon, and the effect tile Minecraft draws behind each icon. Platform
 * class (see {@link Clock}). Render thread.
 */
public final class HudEffects {
	/** The time left of an effect that never ends ({@link #duration}). */
	public static final int INFINITE = EffectOrder.INFINITE;
	private static final Identifier FRAME = Identifier.withDefaultNamespace("hud/effect_background");
	private static final Identifier FRAME_AMBIENT = Identifier.withDefaultNamespace("hud/effect_background_ambient");

	private HudEffects() {
	}

	/**
	 * The effects the game shows an icon for, into {@code out} (as many as fit); returns how many there are, which may be more
	 * than fit. Iterating them allocates an iterator: at most a few times a second.
	 */
	public static int read(Object[] out) {
		LocalPlayer player = Mc263HudData.player();
		if (player == null) {
			return 0;
		}
		int n = 0;
		for (MobEffectInstance effect : player.getActiveEffects()) {
			if (!effect.showIcon()) {
				continue;
			}
			if (n < out.length) {
				out[n] = effect;
			}
			n++;
		}
		return n;
	}

	/** How many effects the player has (shown or not; a cheap size, to notice a change), or -1 outside a world. */
	public static int size() {
		LocalPlayer player = Mc263HudData.player();
		return player == null ? -1 : player.getActiveEffects().size();
	}

	private static MobEffectInstance e(Object effect) {
		return (MobEffectInstance) effect;
	}

	/** The effect's type (compared by identity). */
	public static Object type(Object effect) {
		return e(effect).getEffect().value();
	}

	/** Ticks left, or {@link #INFINITE}. */
	public static int remaining(Object effect) {
		return e(effect).isInfiniteDuration() ? INFINITE : e(effect).getDuration();
	}

	/** Ticks left as the game counts them (meaningless for an endless effect). */
	public static int duration(Object effect) {
		return e(effect).getDuration();
	}

	public static boolean infinite(Object effect) {
		return e(effect).isInfiniteDuration();
	}

	/** From a beacon (its tile is the beacon's, it doesn't blink). */
	public static boolean ambient(Object effect) {
		return e(effect).isAmbient();
	}

	public static boolean endsWithin(Object effect, int ticks) {
		return e(effect).endsWithin(ticks);
	}

	public static int amplifier(Object effect) {
		return e(effect).getAmplifier();
	}

	/** The type's number in the game's registry (a stable tie-break). */
	public static int id(Object effect) {
		return BuiltInRegistries.MOB_EFFECT.getId(e(effect).getEffect().value());
	}

	public static boolean beneficial(Object effect) {
		return e(effect).getEffect().value().isBeneficial();
	}

	/** The type's colour (0xRRGGBB) and its name in the game's language. */
	public static int color(Object type) {
		return ((MobEffect) type).getColor();
	}

	public static String name(Object type) {
		return ((MobEffect) type).getDisplayName().getString();
	}

	/** The effect's icon, for {@link ItemPainter#sprite}. */
	public static Object icon(Object effect) {
		return com.flintclient.client.compat.McCompat.mobEffectSprite(e(effect).getEffect());
	}

	/** The tile Minecraft draws behind an effect's icon on its HUD (a beacon's own for {@code ambient}). */
	public static Object tile(boolean ambient) {
		return ambient ? FRAME_AMBIENT : FRAME;
	}

	/** A soft chime (an effect ran out) at {@code volume}. */
	public static void chime(float volume) {
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), 0.8F, volume));
	}

	// ---------- Status Effect Bars ----------

	/** Whether the HUD's bars are on. */
	public static boolean barsOn() {
		return Mc263EffectBars.on(true);
	}

	/** Works the bar out over a {@code width × height} box; false when it isn't shown. Then {@link #barRect}. */
	public static boolean barLayout(ModuleState state, Object effect, int width, int height, float partialTick) {
		return Mc263EffectBars.layout(state, e(effect), width, height, true, partialTick);
	}

	/** The bar's rectangles in box units: 0..3 the part left, 4..7 the part used. */
	public static float barRect(int index) {
		return Mc263EffectBars.rect(index);
	}

	public static int barColor(ModuleState state, Object effect) {
		return Mc263EffectBars.color(state, e(effect));
	}

	public static int barBackground(ModuleState state) {
		return Mc263EffectBars.background(state);
	}
}
