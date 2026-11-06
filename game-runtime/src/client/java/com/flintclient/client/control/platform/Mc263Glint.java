package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.GlintStyles;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.LookMods.Glint;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.jspecify.annotations.Nullable;

/**
 * Glint's tooltips, the glint mod's behaviour in Flint's own code: an item's tooltip takes the style of its deciding
 * enchantment (or the generic one, or its rarity's), drawn flat: a soft glow, a background and a border in the style's
 * colours at the chosen intensity; each enchantment's line gets an icon (Flint's own) before it. Items with a tooltip style
 * of their own keep it. Fault-isolated.
 */
public final class Mc263Glint {
	private static final HookGuard GUARD = new HookGuard("glint tooltips",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final int GLINT = Mods.index(Glint.ID);
	private static final String PREFIX = "glint/";
	/** Icons of the tooltip being set up: line index and style, valid for the next tooltip drawn. */
	private static final int MAX_ICONS = 64;
	private static final int[] ICON_LINES = new int[MAX_ICONS];
	private static final GlintStyles.Icon[] ICONS = new GlintStyles.Icon[MAX_ICONS];
	private static final GlintStyles.Style[] ICON_STYLES = new GlintStyles.Style[MAX_ICONS];
	private static int icons;
	/** The style the pending icons belong to ("" for Minecraft's own background). */
	private static String iconsFor = "";
	private static final String SPACER = "   ";

	private Mc263Glint() {
	}

	private static boolean on() {
		return GUARD.on() && Mods.on(GLINT);
	}

	private static boolean visual(ModuleState s) {
		return s.bool(Glint.BORDERS) || s.bool(Glint.BACKGROUNDS) || s.bool(Glint.GLOW);
	}

	/** The style {@code stack}'s tooltip takes, or null (Minecraft's). */
	private static GlintStyles.@Nullable Style styleOf(ItemStack stack, ModuleState s) {
		GlintStyles.Style best = null;
		int bestRank = Integer.MAX_VALUE;
		boolean enchanted = false;
		for (ItemEnchantments enchantments : new ItemEnchantments[] {stack.get(DataComponents.ENCHANTMENTS), stack.get(DataComponents.STORED_ENCHANTMENTS)}) {
			if (enchantments == null) {
				continue;
			}
			for (Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments.entrySet()) {
				enchanted = true;
				String path = path(entry.getKey());
				int rank = GlintStyles.PRIORITY.indexOf(path);
				if (rank >= 0 && rank < bestRank) {
					bestRank = rank;
					best = GlintStyles.forEnchantment(path);
				}
			}
		}
		if (best != null) {
			return best;
		}
		if (enchanted) {
			return GlintStyles.GENERIC;
		}
		return s.bool(Glint.RARITY_STYLES) ? GlintStyles.RARITY[Math.min(3, stack.getRarity().ordinal())] : null;
	}

	private static String path(Holder<Enchantment> holder) {
		return holder.unwrapKey().map(key -> key.identifier().getPath()).orElse("");
	}

	/** The style id an item's tooltip is set up with: Glint's own (drawn by {@link #background}) when it restyles it. */
	public static @Nullable Identifier style(ItemStack stack, @Nullable Identifier style) {
		if (style != null || !on()) {
			return style;
		}
		try {
			ModuleState s = Mods.state(GLINT);
			GlintStyles.Style own = visual(s) ? styleOf(stack, s) : null;
			return own == null ? null : Identifier.fromNamespaceAndPath(FlintClient.MOD_ID, PREFIX + own.name());
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return style;
		}
	}

	/** An item tooltip's lines: each enchantment's line moved over for its icon, which {@link #background} draws. */
	public static List<Component> lines(ItemStack stack, List<Component> lines, @Nullable Identifier style, boolean hasImage) {
		icons = 0;
		if (!on()) {
			return lines;
		}
		try {
			ModuleState s = Mods.state(GLINT);
			if (!s.bool(Glint.ICONS) || hasImage || style != null && !style.getNamespace().equals(FlintClient.MOD_ID)) {
				return lines;
			}
			GlintStyles.Style own = visual(s) ? styleOf(stack, s) : null;
			iconsFor = own == null ? "" : own.name();
			List<Component> out = null;
			for (ItemEnchantments enchantments : new ItemEnchantments[] {stack.get(DataComponents.ENCHANTMENTS), stack.get(DataComponents.STORED_ENCHANTMENTS)}) {
				if (enchantments == null) {
					continue;
				}
				for (Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments.entrySet()) {
					String name = Enchantment.getFullname(entry.getKey(), entry.getIntValue()).getString();
					for (int i = 0; i < lines.size() && icons < MAX_ICONS; i++) {
						if (lines.get(i).getString().equals(name)) {
							if (out == null) {
								out = new ArrayList<>(lines);
							}
							out.set(i, Component.literal(SPACER).append(lines.get(i)));
							String path = path(entry.getKey());
							GlintStyles.Style iconStyle = GlintStyles.forEnchantment(path);
							ICON_LINES[icons] = i;
							ICONS[icons] = GlintStyles.icon(path);
							ICON_STYLES[icons] = iconStyle == null ? GlintStyles.GENERIC : iconStyle;
							icons++;
							break;
						}
					}
				}
			}
			return out == null ? lines : out;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			icons = 0;
			return lines;
		}
	}

	/** Before a tooltip's background: Glint's own (true: Minecraft's is skipped). Its icons follow. */
	public static boolean background(GuiGraphicsExtractor g, int x, int y, int w, int h, @Nullable Identifier style) {
		if (!on() || style == null || !style.getNamespace().equals(FlintClient.MOD_ID) || !style.getPath().startsWith(PREFIX)) {
			return false;
		}
		try {
			String name = style.getPath().substring(PREFIX.length());
			GlintStyles.Style own = find(name);
			if (own == null) {
				return false;
			}
			draw(g, x, y, w, h, own, Mods.state(GLINT));
			if (name.equals(iconsFor)) {
				drawIcons(g, x, y);
			}
			return true;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return false;
		}
	}

	/** After Minecraft drew a tooltip's background itself: the icons of a tooltip Glint didn't restyle. */
	public static void afterBackground(GuiGraphicsExtractor g, int x, int y, @Nullable Identifier style) {
		if (icons > 0 && on() && style == null && iconsFor.isEmpty()) {
			try {
				drawIcons(g, x, y);
			} catch (RuntimeException | LinkageError e) {
				GUARD.fail(e);
			}
		}
	}

	private static GlintStyles.@Nullable Style find(String name) {
		if (name.equals(GlintStyles.GENERIC.name())) {
			return GlintStyles.GENERIC;
		}
		for (GlintStyles.Style rarity : GlintStyles.RARITY) {
			if (rarity.name().equals(name)) {
				return rarity;
			}
		}
		return GlintStyles.forEnchantment(name);
	}

	private static void draw(GuiGraphicsExtractor g, int x, int y, int w, int h, GlintStyles.Style style, ModuleState s) {
		int intensity = s.option(Glint.INTENSITY);
		float alpha = intensity == 0 ? 0.72F : intensity == 2 ? 1.28F : 1.0F;
		int spread = intensity == 0 ? 1 : intensity == 2 ? 3 : 2;
		int left = x - 4;
		int top = y - 4;
		int right = x + w + 4;
		int bottom = y + h + 4;
		boolean borders = s.bool(Glint.BORDERS);
		int start = borders ? style.borderStart() : 0xFF5000FF;
		int end = borders ? style.borderEnd() : 0xFF28007F;
		if (s.bool(Glint.GLOW)) {
			for (int k = spread; k >= 1; k--) {
				int a = Math.max(0, Math.min(255, Math.round(0.18F / k * alpha * 255)));
				int c = a << 24 | style.borderStart() & 0xFFFFFF;
				g.fill(left - k, top - k, right + k, top - k + 1, c);
				g.fill(left - k, bottom + k - 1, right + k, bottom + k, c);
				g.fill(left - k, top - k + 1, left - k + 1, bottom + k - 1, c);
				g.fill(right + k - 1, top - k + 1, right + k, bottom + k - 1, c);
			}
		}
		g.fill(left, top, right, bottom, s.bool(Glint.BACKGROUNDS) ? GlintStyles.scaleAlpha(style.background(), alpha) : 0xF0100010);
		int middle = mix(start, end);
		g.fill(left, top, right, top + 1, middle);
		g.fill(left, bottom - 1, right, bottom, middle);
		g.fill(left, top + 1, left + 1, bottom - 1, start);
		g.fill(right - 1, top + 1, right, bottom - 1, end);
	}

	private static int mix(int a, int b) {
		int out = 0;
		for (int shift = 0; shift < 32; shift += 8) {
			out |= (((a >>> shift) & 0xFF) + ((b >>> shift) & 0xFF)) / 2 << shift;
		}
		return out;
	}

	/** Each pending icon, 8 by 8 at the start of its line (a line is 10 high; the first is followed by 2 more). */
	private static void drawIcons(GuiGraphicsExtractor g, int x, int y) {
		for (int n = 0; n < icons; n++) {
			int line = ICON_LINES[n];
			int top = y + (line == 0 ? 0 : 2 + 10 * line) + 1;
			GlintStyles.Icon icon = ICONS[n];
			GlintStyles.Style style = ICON_STYLES[n];
			for (int py = 0; py < 8; py++) {
				for (int px = 0; px < 8; px++) {
					int at = icon.at(px, py);
					if (at != 0) {
						g.fill(x + px, top + py, x + px + 1, top + py + 1, at == 1 ? style.borderStart() : style.borderEnd());
					}
				}
			}
		}
		icons = 0;
	}
}
