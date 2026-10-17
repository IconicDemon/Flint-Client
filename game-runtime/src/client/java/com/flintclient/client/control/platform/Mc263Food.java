package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.HudMods.Saturation;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 26.3's side of the Saturation module beyond its counter: saturation drawn over the hunger bar, what held food
 * would add (blinking), and food values in tooltips. All values are the ones the client already has: your food data from
 * the server, a food item's food component. Render thread; fault-isolated.
 */
public final class Mc263Food {
	private static final int MODULE = Mods.index(Saturation.ID);
	private static final HookGuard GUARD = new HookGuard("saturation overlay",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final Identifier FULL = Identifier.withDefaultNamespace("hud/food_full");
	private static final Identifier HALF = Identifier.withDefaultNamespace("hud/food_half");

	private Mc263Food() {
	}

	/** The food a player holds (main hand first), or null. */
	private static @Nullable FoodProperties held(Player player) {
		FoodProperties food = player.getMainHandItem().get(DataComponents.FOOD);
		return food != null ? food : player.getOffhandItem().get(DataComponents.FOOD);
	}

	/** After the hunger bar is drawn: saturation over it, and what held food would add. */
	public static void foodBar(GuiGraphicsExtractor graphics, Player player, int yLineBase, int xRight) {
		if (!GUARD.on() || !Mods.on(MODULE)) {
			return;
		}
		try {
			ModuleState state = Mods.state(MODULE);
			FoodData data = player.getFoodData();
			int food = data.getFoodLevel();
			float saturation = data.getSaturationLevel();
			FoodProperties gain = held(player);
			// Blinks about twice a second, between faint and strong.
			float blink = 0.35F + 0.35F * (float) (Math.sin(System.nanoTime() / 1.6e8) + 1) / 2;
			int rgb = state.rgb(Saturation.COLOR);
			if (gain != null && state.bool(Saturation.FOOD_GAIN) && food < 20) {
				int after = Math.min(20, food + gain.nutrition());
				for (int i = 0; i < 10; i++) {
					int points = Math.min(after, i * 2 + 2) - Math.max(food, i * 2);
					if (points <= 0) {
						continue;
					}
					Identifier sprite = Math.min(after, i * 2 + 2) - i * 2 >= 2 ? FULL : HALF;
					graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, xRight - i * 8 - 9, yLineBase, 9, 9, blink);
				}
			}
			if (state.bool(Saturation.OVERLAY)) {
				bars(graphics, 0, saturation, xRight, yLineBase, 0xC0000000 | rgb);
			}
			if (gain != null && state.bool(Saturation.SATURATION_GAIN)) {
				float limit = Math.min(20, food + gain.nutrition());
				float after = Math.min(limit, saturation + gain.saturation());
				int alpha = Math.round(blink * 255) << 24;
				bars(graphics, saturation, after, xRight, yLineBase, alpha | rgb);
			}
		} catch (RuntimeException e) {
			GUARD.fail(e);
		}
	}

	/** Saturation from {@code from} to {@code to} points as an outline on each drumstick, two points to an icon. */
	private static void bars(GuiGraphicsExtractor graphics, float from, float to, int xRight, int y, int argb) {
		for (int i = 0; i < 10; i++) {
			float lo = Math.max(from, i * 2);
			float hi = Math.min(to, i * 2 + 2);
			if (hi <= lo) {
				continue;
			}
			int x = xRight - i * 8 - 9;
			// The icon's right edge is its first point (icons fill from the right): a bar along the bottom, as wide as the share.
			int left = x + 1 + Math.round(7 * (1 - (hi - i * 2) / 2F));
			int right = x + 1 + Math.round(7 * (1 - (lo - i * 2) / 2F));
			graphics.fill(left, y + 7, right, y + 9, argb);
			graphics.fill(left, y, right, y + 1, argb);
		}
	}

	/** A food's tooltip: its hunger and saturation, per the module's Never / Shift Only / Always. */
	public static void tooltip(ItemStack stack, Consumer<Component> lines) {
		if (!GUARD.on() || !Mods.on(MODULE)) {
			return;
		}
		try {
			int when = Mods.state(MODULE).option(Saturation.TOOLTIP);
			if (when == 0 || when == 1 && !shift()) {
				return;
			}
			FoodProperties food = stack.get(DataComponents.FOOD);
			if (food == null) {
				return;
			}
			lines.accept(Component.literal(String.format(Locale.ROOT, "+%d hunger · +%.1f saturation", food.nutrition(), food.saturation()))
				.withStyle(ChatFormatting.GOLD));
		} catch (RuntimeException e) {
			GUARD.fail(e);
		}
	}

	private static boolean shift() {
		return Mc263ControlPlatform.keyDown(InputConstants.KEY_LSHIFT) || Mc263ControlPlatform.keyDown(InputConstants.KEY_RSHIFT);
	}
}
