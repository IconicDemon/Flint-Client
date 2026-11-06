package com.flintclient.legacy.mods;

import com.flintclient.client.control.model.mods.MoreMods;
import com.flintclient.legacy.module.Module;
import com.flintclient.legacy.module.Setting;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Popular UHC items drawn bigger on the ground: golden apples (and the god apple, 1.8.9's golden apple with data 1), gold ore,
 * ingots and nuggets, heads, armour, bottles o' enchanting, ender pearls and cobwebs, each at its own size, and any item the
 * player searched for and added ("Your items"), which wins over those. The 1.8.9 backend of UHC Overlay: the sizes are the
 * shared settings; 1.8.9 has no totem or mace, so their rows aren't shown. Drawn by ItemEntityRendererMixin.
 */
public final class UhcOverlay extends Module {
	public final Setting.Decimal goldenApple = scale(MoreMods.UhcOverlay.GOLDEN_APPLE);
	public final Setting.Decimal godApple = scale(MoreMods.UhcOverlay.GOD_APPLE);
	public final Setting.Decimal goldOre = scale(MoreMods.UhcOverlay.GOLD_ORE);
	public final Setting.Decimal goldIngot = scale(MoreMods.UhcOverlay.GOLD_INGOT);
	public final Setting.Decimal goldNugget = scale(MoreMods.UhcOverlay.GOLD_NUGGET);
	public final Setting.Decimal skull = scale(MoreMods.UhcOverlay.SKULL);
	public final Setting.Decimal armor = scale(MoreMods.UhcOverlay.ARMOR);
	public final Setting.Decimal xpBottle = scale(MoreMods.UhcOverlay.XP_BOTTLE);
	public final Setting.Decimal pearl = scale(MoreMods.UhcOverlay.PEARL);
	public final Setting.Decimal cobweb = scale(MoreMods.UhcOverlay.COBWEB);

	/** Each item's id, looked up once. Render thread. */
	private final Map<Item, String> ids = new IdentityHashMap<Item, String>();

	public UhcOverlay() {
		super(MoreMods.UhcOverlay.ID, "uhcoverlay");
		reads(MoreMods.UhcOverlay.ITEMS);
	}

	/** A size slider in steps of 0.05, as on every line. */
	private Setting.Decimal scale(com.flintclient.client.control.model.FloatSetting setting) {
		return decimal(setting, 20, "×");
	}

	/** The size dropped {@code stack} is drawn at: 1 (as Minecraft draws it) when the mod is off or the item isn't one of its. */
	public float scale(@Nullable ItemStack stack) {
		if (!enabled() || stack == null || stack.getItem() == null) {
			return 1;
		}
		Item item = stack.getItem();
		String id = ids.get(item);
		if (id == null) {
			id = String.valueOf(Item.REGISTRY.getKey(item));
			ids.put(item, id);
		}
		Map<String, Integer> added = state().values(MoreMods.UhcOverlay.ITEMS);
		if (!added.isEmpty()) {
			Integer hundredths = added.get(id);
			if (hundredths != null) {
				return hundredths / 100F;
			}
		}
		switch (id) {
			case "minecraft:golden_apple":
				return stack.getMetadata() == 1 ? godApple.decimal() : goldenApple.decimal();
			case "minecraft:gold_ore":
				return goldOre.decimal();
			case "minecraft:gold_ingot":
				return goldIngot.decimal();
			case "minecraft:gold_nugget":
				return goldNugget.decimal();
			case "minecraft:skull":
				return skull.decimal();
			case "minecraft:experience_bottle":
				return xpBottle.decimal();
			case "minecraft:ender_pearl":
				return pearl.decimal();
			case "minecraft:web":
				return cobweb.decimal();
			default:
				return item instanceof ArmorItem ? armor.decimal() : 1;
		}
	}
}
