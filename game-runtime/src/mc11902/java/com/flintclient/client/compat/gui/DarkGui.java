package com.flintclient.client.compat.gui;

import com.flintclient.client.control.platform.Mc263DarkMode;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;

/**
 * Dark Mode on Minecraft 1.19.2, which blits its GUI from sheets (widgets.png, icons.png, the container textures) bound
 * before the blit, where 1.20.2 and later draw each GUI sprite by name: the texture bound to unit 0 is followed
 * (mixin.feature.DarkTextureMixin) and each GuiComponent blit (mixin.feature.GuiGraphicsDarkMixin) is shaded as the sprite or
 * texture it stands for would be (Mc263DarkMode): widgets.png's buttons as 1.20.2's widget sprites (the hovered row in the
 * accent), its hotbar (drawn 90 units deep) as the hotbar sprite, the recipe book as its sprites, the HUD's sheets
 * (icons.png, the boss bars, the HUD's effect frames) never, and the rest as whole textures. The shade goes on the shader
 * colour for the length of the blit (on top of any tint already set) and is put back after. Render thread only; nothing is
 * allocated per blit.
 */
public final class DarkGui {
	private static final String WIDGETS = "textures/gui/widgets.png";
	private static final String ICONS = "textures/gui/icons.png";
	private static final String BARS = "textures/gui/bars.png";
	private static final String INVENTORY = "textures/gui/container/inventory.png";
	private static final String RECIPE_BOOK = "textures/gui/recipe_book.png";
	private static final ResourceLocation HOTBAR = new ResourceLocation("hud/hotbar");
	private static final ResourceLocation BUTTON = new ResourceLocation("widget/button");
	private static final ResourceLocation BUTTON_HIGHLIGHTED = new ResourceLocation("widget/button_highlighted");
	private static final ResourceLocation RECIPE_BOOK_SPRITE = new ResourceLocation("recipe_book/button");
	/** 1.19.2's hotbar blit depth (Gui.renderHotbar and the spectator hotbar set it). */
	private static final int HOTBAR_Z = -90;

	private static @Nullable ResourceLocation bound;
	private static final float[] SAVED = new float[4];
	private static int depth;
	private static boolean shaded;

	private DarkGui() {
	}

	/** The texture just bound to unit {@code unit} (null: a raw texture id, not known by name). */
	public static void bound(int unit, @Nullable ResourceLocation texture) {
		if (unit == 0) {
			bound = texture;
		}
	}

	/** Before a GUI blit at depth {@code z} from ({@code u0}, {@code v0}) of the bound texture (0..1): its shade, if any. */
	public static void begin(int z, float u0, float v0) {
		if (depth++ > 0) {
			return;
		}
		int argb = color(z, u0, v0);
		if (argb == -1) {
			return;
		}
		float[] now = RenderSystem.getShaderColor();
		System.arraycopy(now, 0, SAVED, 0, 4);
		RenderSystem.setShaderColor(now[0] * (argb >> 16 & 0xFF) / 255.0F, now[1] * (argb >> 8 & 0xFF) / 255.0F, now[2] * (argb & 0xFF) / 255.0F,
			now[3] * (argb >>> 24) / 255.0F);
		shaded = true;
	}

	/** After the blit: the colour it had. */
	public static void end() {
		if (--depth > 0) {
			return;
		}
		depth = 0;
		if (shaded) {
			shaded = false;
			RenderSystem.setShaderColor(SAVED[0], SAVED[1], SAVED[2], SAVED[3]);
		}
	}

	private static int color(int z, float u0, float v0) {
		ResourceLocation texture = bound;
		if (texture == null || !"minecraft".equals(texture.getNamespace())) {
			return -1;
		}
		switch (texture.getPath()) {
			case ICONS, BARS:
				return -1;
			case WIDGETS: {
				if (z == HOTBAR_Z) {
					return Mc263DarkMode.sprite(HOTBAR, -1);
				}
				// A button's rows: disabled at v 46, plain at 66, under the pointer at 86 (1.19.2's AbstractWidget.getYImage).
				int v = Math.round(v0 * 256.0F);
				return Mc263DarkMode.sprite(v >= 86 && v < 106 ? BUTTON_HIGHLIGHTED : BUTTON, -1);
			}
			case INVENTORY: {
				// The HUD's effect frames (1.20.2's hud/effect_background sprites) stay as they are.
				int u = Math.round(u0 * 256.0F);
				if (Math.round(v0 * 256.0F) == 166 && (u == 141 || u == 165)) {
					return -1;
				}
				return Mc263DarkMode.texture(texture, -1);
			}
			case RECIPE_BOOK:
				return Mc263DarkMode.sprite(RECIPE_BOOK_SPRITE, -1);
			default:
				return Mc263DarkMode.texture(texture, -1);
		}
	}
}
