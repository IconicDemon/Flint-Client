/*
 * Adapted from Status Effect Bars by A5b84 (https://github.com/A5b84/status-effect-bars), LGPL-3.0-or-later:
 * see META-INF/licenses/LGPL-3.0-StatusEffectBars.txt.
 */
package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.ModuleSettings.Effects;
import com.flintclient.client.control.model.ModuleState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import org.jspecify.annotations.Nullable;

/**
 * Effects' Status Effect Bars: a thin bar along each effect icon showing the share of its time left, on the HUD (Minecraft's
 * icons and this module's own list) and in the effect list beside the inventory. The bar is the effect's colour (or its
 * category's, or a custom one) over a background, in the layout chosen for each place (direction, side, thickness, padding,
 * offsets), hidden above a remaining time and for beacon effects not yet old enough. An effect's full duration is the one it
 * started with, kept when the server sends it again at the same level ({@code MobEffectInstanceMixin}). Fault-isolated.
 */
public final class Mc263EffectBars {
	private static final int EFFECTS = Mods.index(ModuleId.EFFECTS);
	private static final HookGuard GUARD = new HookGuard("effect bars",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	/** The bar's two rectangles, worked out by {@link #layout}: x0, y0, x1, y1 of the part left, then of the part used. */
	private static final float[] RECTS = new float[8];

	private Mc263EffectBars() {
	}

	/** An effect's duration when it started, kept by {@code MobEffectInstanceMixin}. */
	public interface Timed {
		int flint$maxDuration();

		void flint$setMaxDuration(int ticks);
	}

	/** Whether {@code hud} (or the inventory's) bars are on now. */
	public static boolean on(boolean hud) {
		if (!GUARD.on() || !Mods.on(EFFECTS)) {
			return false;
		}
		ModuleState s = Mods.state(EFFECTS);
		return s.bool((hud ? Effects.HUD_BARS : Effects.INVENTORY_BARS).enabled);
	}

	/** Draws {@code effect}'s bar over its icon's box ({@code x}, {@code y}, {@code width} × {@code height}, GUI units). */
	public static void render(GuiGraphicsExtractor graphics, @Nullable MobEffectInstance effect, int x, int y, int width, int height, boolean hud) {
		if (effect == null || !on(hud)) {
			return;
		}
		try {
			ModuleState s = Mods.state(EFFECTS);
			float partialTick = hud ? Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false) : 0.0F;
			if (!layout(s, effect, width, height, hud, partialTick)) {
				return;
			}
			graphics.fill(x + (int) RECTS[0], y + (int) RECTS[1], x + (int) RECTS[2], y + (int) RECTS[3], color(s, effect));
			graphics.fill(x + (int) RECTS[4], y + (int) RECTS[5], x + (int) RECTS[6], y + (int) RECTS[7], background(s));
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/**
	 * Works the bar out in a {@code width} × {@code height} box into {@link #RECTS} (box units); false when it isn't shown.
	 * Status Effect Bars' own geometry: along the box's width (or height, for the vertical directions), inset by the padding
	 * and moved by the offsets, the part left from the start to the middle point and the part used from there to the end.
	 */
	public static boolean layout(ModuleState s, MobEffectInstance effect, int width, int height, boolean hud, float partialTick) {
		Effects.BarLayout bars = hud ? Effects.HUD_BARS : Effects.INVENTORY_BARS;
		int maxRemaining = s.integer(Effects.BAR_MAX_REMAINING);
		if (maxRemaining > 0 && effect.getDuration() > maxRemaining * 20 || effect.isInfiniteDuration()) {
			return false;
		}
		int max = ((Timed) effect).flint$maxDuration();
		if (max <= 0) {
			return false;
		}
		int age = max - effect.getDuration();
		if (effect.isAmbient() && age < s.integer(Effects.BAR_MIN_AMBIENT_AGE)) {
			return false;
		}
		int direction = s.option(bars.direction);
		boolean swapXY = direction == 2 || direction == 3;
		boolean reverseAxis = direction == 1 || direction == 2;
		if (swapXY) {
			int tmp = width;
			width = height;
			height = tmp;
		}
		int padding = s.integer(bars.collinearPadding);
		int collinearOffset = s.integer(bars.collinearOffset);
		int thickness = s.integer(bars.thickness);
		int startX = collinearOffset + padding;
		int endX = collinearOffset + width - padding;
		if (reverseAxis) {
			int tmp = startX;
			startX = endX;
			endX = tmp;
		}
		float progress = (effect.getDuration() - partialTick) / max;
		int middleX = lerpInt(progress, startX, endX);
		int startY = s.integer(bars.orthogonalOffset);
		if (s.bool(bars.relativeToEnd)) {
			startY = height - (startY + thickness);
		}
		int middleY = startY + thickness;
		int endY = startY;
		if (swapXY) {
			int tmp = startX;
			startX = startY;
			startY = tmp;
			tmp = middleX;
			middleX = middleY;
			middleY = tmp;
			tmp = endX;
			endX = endY;
			endY = tmp;
		}
		RECTS[0] = startX;
		RECTS[1] = startY;
		RECTS[2] = middleX;
		RECTS[3] = middleY;
		RECTS[4] = middleX;
		RECTS[5] = middleY;
		RECTS[6] = endX;
		RECTS[7] = endY;
		return true;
	}

	/** One corner of the last {@link #layout}: index 0-3 the part left (x0, y0, x1, y1), 4-7 the part used. */
	public static float rect(int index) {
		return RECTS[index];
	}

	/** Mth.lerpInt: {@code start} plus the floor of {@code delta} of the way to {@code end}. */
	private static int lerpInt(float delta, int start, int end) {
		return start + (int) Math.floor(delta * (end - start));
	}

	/** The bar's colour: the effect's own, its category's, or the custom one for its category. */
	public static int color(ModuleState s, MobEffectInstance effect) {
		MobEffect type = effect.getEffect().value();
		return switch (s.option(Effects.BAR_COLOR_MODE)) {
			case 1 -> {
				net.minecraft.network.chat.TextColor category = net.minecraft.network.chat.TextColor.fromLegacyFormat(type.getCategory().getTooltipFormatting());
				yield 0xFF000000 | (category == null ? 0xFFFFFF : category.getValue());
			}
			case 2 -> switch (type.getCategory()) {
				case BENEFICIAL -> argb(s.integer(Effects.BAR_BENEFICIAL_OPACITY), s.rgb(Effects.BAR_BENEFICIAL));
				case HARMFUL -> argb(s.integer(Effects.BAR_HARMFUL_OPACITY), s.rgb(Effects.BAR_HARMFUL));
				default -> argb(s.integer(Effects.BAR_NEUTRAL_OPACITY), s.rgb(Effects.BAR_NEUTRAL));
			};
			default -> 0xFF000000 | type.getColor();
		};
	}

	public static int background(ModuleState s) {
		return argb(s.integer(Effects.BAR_BACKGROUND_OPACITY), s.rgb(Effects.BAR_BACKGROUND));
	}

	private static int argb(int percent, int rgb) {
		return Math.round(255 * Math.clamp(percent, 0, 100) / 100.0F) << 24 | rgb & 0xFFFFFF;
	}

	// ---------- the effect being drawn ----------

	private static @Nullable MobEffectInstance drawingHud;
	private static @Nullable MobEffectInstance drawingInventory;

	/** Minecraft's HUD (or the inventory list) is about to draw {@code effect}'s icon. */
	public static void drawing(MobEffectInstance effect, boolean hud) {
		if (hud) {
			drawingHud = effect;
		} else {
			drawingInventory = effect;
		}
	}

	/** The effect whose icon is being drawn (and forgets it). */
	public static @Nullable MobEffectInstance drawn(boolean hud) {
		MobEffectInstance effect = hud ? drawingHud : drawingInventory;
		if (hud) {
			drawingHud = null;
		} else {
			drawingInventory = null;
		}
		return effect;
	}
}
