package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.AutoHide;
import com.flintclient.client.control.feature.Features;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.VisualMods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Minecraft 26.3's side of Autohide HUD: each tick it fingerprints what the three parts show (health and armor, food and
 * air, the hotbar's items and experience); {@code HudAutoHideMixin} asks how far down to draw each part as Minecraft draws
 * it. Fault-isolated; render thread only, allocation-free.
 */
public final class Mc263AutoHide {
	private static final int AUTOHIDE = Mods.index(VisualMods.AutohideHud.ID);
	/** How far a part slides down, in GUI pixels, before it is gone. */
	private static final float SLIDE = 60;
	/** {@link #shift}: not drawn at all. */
	public static final float GONE = -1;
	private static final HookGuard GUARD = new HookGuard("autohide HUD",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));

	private Mc263AutoHide() {
	}

	static void tick() {
		if (!GUARD.on()) {
			return;
		}
		try {
			LocalPlayer player = Minecraft.getInstance().player;
			if (player == null || !Mods.on(AUTOHIDE)) {
				return;
			}
			long health = Float.floatToIntBits(player.getHealth());
			health = health * 31 + Float.floatToIntBits(player.getAbsorptionAmount());
			health = health * 31 + Float.floatToIntBits(player.getMaxHealth());
			health = health * 31 + player.getArmorValue();
			long food = player.getFoodData().getFoodLevel() * 31L + player.getAirSupply();
			Inventory inventory = player.getInventory();
			long hotbar = inventory.getSelectedSlot();
			for (int i = 0; i < Inventory.getSelectionSize(); i++) {
				ItemStack stack = inventory.getItem(i);
				hotbar = hotbar * 31 + (stack.isEmpty() ? 0 : System.identityHashCode(stack.getItem()) * 64L + stack.getCount());
			}
			hotbar = hotbar * 31 + player.experienceLevel;
			hotbar = hotbar * 31 + Float.floatToIntBits(player.experienceProgress);
			Features.AUTO_HIDE.tick(health, food, hotbar);
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** How far down to draw {@code part} ({@link AutoHide#HEALTH}...), in GUI pixels: 0 as usual, {@link #GONE} not at all. */
	public static float shift(int part) {
		if (!GUARD.on() || !Mods.on(AUTOHIDE)) {
			return 0;
		}
		try {
			ModuleState hide = Mods.state(AUTOHIDE);
			boolean chosen = switch (part) {
				case AutoHide.HEALTH -> hide.bool(VisualMods.AutohideHud.HEALTH);
				case AutoHide.FOOD -> hide.bool(VisualMods.AutohideHud.FOOD);
				default -> hide.bool(VisualMods.AutohideHud.HOTBAR);
			};
			if (!chosen) {
				return 0;
			}
			boolean animate = hide.bool(VisualMods.AutohideHud.ANIMATE) && !com.flintclient.client.ui.kit.Kit.reduced();
			float hidden = Features.AUTO_HIDE.hidden(part, hide.bool(VisualMods.AutohideHud.SEPARATELY), hide.integer(VisualMods.AutohideHud.DELAY),
				animate ? hide.integer(VisualMods.AutohideHud.DURATION) : 0,
				Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
			return hidden >= 1 ? GONE : hidden * SLIDE;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return 0;
		}
	}
}
