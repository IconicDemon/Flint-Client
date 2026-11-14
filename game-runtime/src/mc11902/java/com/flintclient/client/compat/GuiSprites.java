package com.flintclient.client.compat;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;

/**
 * The GUI sprites the shared sources draw by 1.20.2's names (hud/heart/full, hud/hotbar...), on Minecraft 1.19.2, which keeps
 * them in sheets: icons.png, widgets.png, the inventory's background, and the block atlas for the empty-slot icons. A sprite
 * 1.19.2 doesn't have is not drawn ({@link #region} is null).
 */
public final class GuiSprites {
	private static final ResourceLocation ICONS = new ResourceLocation("textures/gui/icons.png");
	private static final ResourceLocation WIDGETS = new ResourceLocation("textures/gui/widgets.png");
	private static final ResourceLocation INVENTORY = new ResourceLocation("textures/gui/container/inventory.png");
	private static final Map<ResourceLocation, Region> SHEETS = new HashMap<>();
	/** Sprites that are block-atlas sprites on 1.19.2 (the armour slots' empty icons). */
	private static final Map<ResourceLocation, ResourceLocation> ATLAS = new HashMap<>();
	/** Mods' own sprites, each a whole texture (render thread only). */
	private static final Map<ResourceLocation, Region> OWN = new HashMap<>();

	/** A sprite's place: {@code width} x {@code height} at ({@code u}, {@code v}) of a {@code textureWidth} x {@code textureHeight} texture. */
	public record Region(ResourceLocation texture, float u, float v, int width, int height, int textureWidth, int textureHeight) {
		/** Its texture coordinates, as an atlas sprite's (the hearts drawn in the world read them so). */
		public float getU0() {
			return u / textureWidth;
		}

		public float getU1() {
			return (u + width) / textureWidth;
		}

		public float getV0() {
			return v / textureHeight;
		}

		public float getV1() {
			return (v + height) / textureHeight;
		}
	}

	static {
		icon("hud/crosshair", 0, 0, 15, 15);
		icon("hud/crosshair_attack_indicator_full", 68, 94, 16, 16);
		icon("hud/crosshair_attack_indicator_background", 36, 94, 16, 4);
		icon("hud/crosshair_attack_indicator_progress", 52, 94, 16, 4);
		icon("hud/hotbar_attack_indicator_background", 0, 94, 18, 18);
		icon("hud/hotbar_attack_indicator_progress", 18, 94, 18, 18);
		// Hearts (1.19.2's Gui.HeartType: 16 + (index * 2 + half + blinking * 2) * 9; hardcore 45 down).
		heart("container", 16, 25, false);
		heart("", 52, 70, true);
		heart("poisoned_", 88, 106, true);
		heart("withered_", 124, 142, true);
		heart("absorbing_", 160, 160, true);
		heart("frozen_", 178, 178, true);
		icon("hud/heart/vehicle_container", 52, 9, 9, 9);
		icon("hud/heart/vehicle_full", 88, 9, 9, 9);
		icon("hud/heart/vehicle_half", 97, 9, 9, 9);
		icon("hud/armor_empty", 16, 9, 9, 9);
		icon("hud/armor_half", 25, 9, 9, 9);
		icon("hud/armor_full", 34, 9, 9, 9);
		icon("hud/air", 16, 18, 9, 9);
		icon("hud/air_bursting", 25, 18, 9, 9);
		icon("hud/food_empty", 16, 27, 9, 9);
		icon("hud/food_full", 52, 27, 9, 9);
		icon("hud/food_half", 61, 27, 9, 9);
		icon("hud/food_empty_hunger", 133, 27, 9, 9);
		icon("hud/food_full_hunger", 88, 27, 9, 9);
		icon("hud/food_half_hunger", 97, 27, 9, 9);
		icon("hud/experience_bar_background", 0, 64, 182, 5);
		icon("hud/experience_bar_progress", 0, 69, 182, 5);
		icon("hud/jump_bar_cooldown", 0, 74, 182, 5);
		icon("hud/jump_bar_background", 0, 84, 182, 5);
		icon("hud/jump_bar_progress", 0, 89, 182, 5);
		sheet("hud/hotbar", WIDGETS, 0, 0, 182, 22);
		sheet("hud/hotbar_selection", WIDGETS, 0, 22, 24, 23);
		sheet("hud/hotbar_offhand_left", WIDGETS, 24, 22, 29, 24);
		sheet("hud/hotbar_offhand_right", WIDGETS, 53, 22, 29, 24);
		sheet("hud/effect_background", INVENTORY, 141, 166, 24, 24);
		sheet("hud/effect_background_ambient", INVENTORY, 165, 166, 24, 24);
		sheet("container/inventory/effect_background_large", INVENTORY, 0, 166, 120, 32);
		sheet("container/inventory/effect_background_small", INVENTORY, 0, 198, 32, 32);
		for (String piece : new String[] {"helmet", "chestplate", "leggings", "boots", "shield"}) {
			ATLAS.put(new ResourceLocation("container/slot/" + piece), new ResourceLocation("item/empty_armor_slot_" + piece));
		}
	}

	private GuiSprites() {
	}

	private static void icon(String id, int u, int v, int width, int height) {
		sheet(id, ICONS, u, v, width, height);
	}

	private static void sheet(String id, ResourceLocation texture, int u, int v, int width, int height) {
		SHEETS.put(new ResourceLocation(id), new Region(texture, u, v, width, height, 256, 256));
	}

	/** A heart's four looks (full, half, and blinking) in both rows (normal, hardcore). */
	private static void heart(String kind, int full, int blinking, boolean halves) {
		for (int row = 0; row < 2; row++) {
			String hardcore = row == 1 ? "hardcore_" : "";
			int v = row == 1 ? 45 : 0;
			if (!halves) {
				icon("hud/heart/" + kind + (row == 1 ? "_hardcore" : ""), full, v, 9, 9);
				icon("hud/heart/" + kind + (row == 1 ? "_hardcore" : "") + "_blinking", blinking, v, 9, 9);
				continue;
			}
			icon("hud/heart/" + kind + hardcore + "full", full, v, 9, 9);
			icon("hud/heart/" + kind + hardcore + "half", full + 9, v, 9, 9);
			icon("hud/heart/" + kind + hardcore + "full_blinking", blinking, v, 9, 9);
			icon("hud/heart/" + kind + hardcore + "half_blinking", blinking + 9, v, 9, 9);
		}
	}

	/** Where 1.19.2 keeps the sprite 1.20.2 calls {@code id}, or null when it has none. */
	public static @Nullable Region region(ResourceLocation id) {
		Region region = SHEETS.get(id);
		if (region != null) {
			return region;
		}
		ResourceLocation atlasSprite = ATLAS.get(id);
		if (atlasSprite == null) {
			// A mod's own sprite (Flint's: flintclient:no_chat_reports/...) is its file under textures/gui/sprites, drawn whole.
			if (!"minecraft".equals(id.getNamespace())) {
				return OWN.computeIfAbsent(id, k -> new Region(new ResourceLocation(k.getNamespace(), "textures/gui/sprites/" + k.getPath() + ".png"),
					0, 0, 1, 1, 1, 1));
			}
			return null;
		}
		TextureAtlas atlas = (TextureAtlas) Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
		TextureAtlasSprite sprite = atlas.getSprite(atlasSprite);
		int w = sprite.getWidth();
		int h = sprite.getHeight();
		// The sprite as a region of an imagined texture of the atlas' size: its UVs scaled back to pixels.
		float atlasW = w / (sprite.getU1() - sprite.getU0());
		float atlasH = h / (sprite.getV1() - sprite.getV0());
		return new Region(TextureAtlas.LOCATION_BLOCKS, sprite.getU0() * atlasW, sprite.getV0() * atlasH, w, h, Math.round(atlasW), Math.round(atlasH));
	}
}
