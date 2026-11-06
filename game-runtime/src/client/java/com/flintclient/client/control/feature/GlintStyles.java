package com.flintclient.client.control.feature;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Glint's tooltip styles, as the glint mod colours enchanted tooltips: each enchantment (by its id's path) has a border's
 * start and end colours and a background, plus Flint's own 8 by 8 icon; items without a listed enchantment use the generic
 * style, items without enchantments their rarity's. Which enchantment decides is fixed: mending, sharpness, fire aspect,
 * protection, efficiency, fortune, silk touch, unbreaking, then the others in this list's order. Pure Java.
 */
public final class GlintStyles {
	/** One style: ARGB colours. */
	public record Style(String name, int borderStart, int borderEnd, int background, Icon icon) {
	}

	/** Flint's icons: 8 rows of 8, '#' the border's start colour, '+' its end colour, ' ' clear. */
	public enum Icon {
		SWORD("      ##", "     #+#", "    #+# ", " # #+#  ", "  #+#   ", "  +#    ", " #+ #   ", "#   ##  "),
		PICKAXE(" ####   ", "#++++#  ", "     #+ ", "    #+# ", "   #+ # ", "  #+   #", " #+     ", "#+      "),
		FLAME("   #    ", "  ##  # ", "  #+# # ", " #++##  ", " #+++## ", "#++++++#", "#+++++# ", " ##### "),
		SHIELD("########", "#++++++#", "#+####+#", "#+####+#", "#+####+#", " #+##+# ", "  #++#  ", "   ##   "),
		DROP("   #    ", "   #    ", "  #+#   ", "  #+#   ", " #+++#  ", " #++##  ", " #+++#  ", "  ###   "),
		BOW("  ###   ", " #   #  ", "#  +  # ", "#  +  # ", "#  +  # ", "#  +  # ", " #   #  ", "  ###   "),
		ARROW("     ###", "      ##", "     # #", "    #   ", "   #    ", "  #     ", "++      ", "++      "),
		TRIDENT("# # #   ", "# # #   ", "#####   ", "  #     ", "  +     ", "  +     ", "  +     ", "  +     "),
		BOOT("  ####  ", "  #++#  ", "  #++#  ", "  #++#  ", "  #++## ", " #++++##", " #+++++#", " #######"),
		HEART(" ## ##  ", "#++#++# ", "#+++++# ", "#+++++# ", " #+++#  ", "  #+#   ", "   #    ", "        "),
		SKULL(" ###### ", "#++++++#", "#+##+##+", "#+##+##+", "#++++++#", " #+##+# ", " #+##+# ", "  ####  "),
		MACE("  ##    ", " #++#   ", " #++#   ", "  ##    ", "   +    ", "    +   ", "     +  ", "      + "),
		GEM("  ####  ", " #++++# ", "#++++++#", " #++++# ", "  #++#  ", "   ##   ", "        ", "        "),
		FISH("        ", " ###  # ", "#+++###+", "#+#+++# ", "#+++###+", " ###  # ", "        ", "        "),
		SNOW("#  #  # ", " # # #  ", "  ###   ", "### ### ", "  ###   ", " # # #  ", "#  #  # ", "        "),
		BOLT("   ###  ", "  ##+   ", " ##+    ", "#####   ", "  ++#   ", "  +#    ", " +#     ", " #      "),
		SPIKES("#   #   ", "##  ##  ", "#+# #+# ", "#++##++#", "########", "#++++++#", "#++++++#", "########"),
		ANVIL("########", "#++++++#", " ###### ", "   ##   ", "   ##   ", "  ####  ", " #++++# ", "########"),
		SPEAR("      ##", "     #+#", "    #+# ", "   #+#  ", "  +#    ", " +      ", "+       ", "        "),
		STAR("   #    ", "   #    ", "  #+#   ", "###+### ", "  #+#   ", "  # #   ", " #   #  ", "        ");

		private final boolean[][] start = new boolean[8][8];
		private final boolean[][] end = new boolean[8][8];

		Icon(String... rows) {
			for (int y = 0; y < rows.length && y < 8; y++) {
				for (int x = 0; x < rows[y].length() && x < 8; x++) {
					start[y][x] = rows[y].charAt(x) == '#';
					end[y][x] = rows[y].charAt(x) == '+';
				}
			}
		}

		/** 0: clear, 1: the start colour, 2: the end colour. */
		public int at(int x, int y) {
			return start[y][x] ? 1 : end[y][x] ? 2 : 0;
		}
	}

	private static final Map<String, Style> BY_ENCHANTMENT = new HashMap<>();
	/** Enchantment paths in the order they decide a tooltip's style. */
	public static final List<String> PRIORITY;
	public static final Style GENERIC = new Style("generic", 0xFFB98AFF, 0xFF624092, 0xE0221730, Icon.STAR);
	/** Common, uncommon, rare, epic. */
	public static final Style[] RARITY = {new Style("rarity_common", 0xFFA4A8AE, 0xFF5D626A, 0xE0202226, Icon.STAR),
		new Style("rarity_uncommon", 0xFF4FAF67, 0xFF215B35, 0xE012261A, Icon.STAR),
		new Style("rarity_rare", 0xFF68E4FF, 0xFF287A9F, 0xE0122934, Icon.STAR),
		new Style("rarity_epic", 0xFFD58CFF, 0xFF7540A6, 0xE0281933, Icon.STAR)};

	static {
		Object[][] table = {
			{"mending", 0xFF62E6D2, 0xFF207B73, 0xE0142C30, Icon.HEART},
			{"sharpness", 0xFFF17878, 0xFF9E3232, 0xE02B151B, Icon.SWORD},
			{"fire_aspect", 0xFFFFB04A, 0xFFC54B20, 0xE0321D12, Icon.FLAME},
			{"protection", 0xFF80B8E8, 0xFF3D638A, 0xE014202E, Icon.SHIELD},
			{"efficiency", 0xFFFFD66E, 0xFFA97A23, 0xE02E2714, Icon.PICKAXE},
			{"fortune", 0xFF62D890, 0xFF237B4C, 0xE0142C1D, Icon.GEM},
			{"silk_touch", 0xFFC79AFF, 0xFF7044A8, 0xE0241733, Icon.PICKAXE},
			{"unbreaking", 0xFFB6BCC8, 0xFF626A78, 0xE0202228, Icon.ANVIL},
			{"aqua_affinity", 0xFF60D8F0, 0xFF217A96, 0xE0122830, Icon.DROP},
			{"bane_of_arthropods", 0xFFA8D96B, 0xFF58752D, 0xE0202A14, Icon.SWORD},
			{"binding_curse", 0xFFDD4B76, 0xFF76233F, 0xE02D1420, Icon.SKULL},
			{"blast_protection", 0xFFE89B5A, 0xFF8B4C26, 0xE02E1E14, Icon.SHIELD},
			{"breach", 0xFFFF8066, 0xFF9D3B33, 0xE0311716, Icon.MACE},
			{"channeling", 0xFF66B7FF, 0xFF2B5FA9, 0xE0132135, Icon.BOLT},
			{"density", 0xFFA679FF, 0xFF593999, 0xE0211730, Icon.MACE},
			{"depth_strider", 0xFF4AAFC7, 0xFF276677, 0xE0102329, Icon.DROP},
			{"feather_falling", 0xFFD2E8FF, 0xFF7293B0, 0xE01D2730, Icon.BOOT},
			{"fire_protection", 0xFFFFC35A, 0xFFB06326, 0xE0322112, Icon.SHIELD},
			{"flame", 0xFFFF6C3D, 0xFFA92E21, 0xE0321610, Icon.FLAME},
			{"frost_walker", 0xFFB9F4FF, 0xFF4A91AD, 0xE0162C34, Icon.SNOW},
			{"impaling", 0xFF49D1BB, 0xFF26786D, 0xE0112A28, Icon.TRIDENT},
			{"infinity", 0xFFFF8CD8, 0xFFA04482, 0xE02E1730, Icon.BOW},
			{"knockback", 0xFFE8B36B, 0xFF895E2E, 0xE02B2216, Icon.SWORD},
			{"looting", 0xFFB5E85D, 0xFF627B29, 0xE0222C13, Icon.GEM},
			{"loyalty", 0xFF82AFFF, 0xFF405D9F, 0xE0152033, Icon.TRIDENT},
			{"luck_of_the_sea", 0xFF4FE0A7, 0xFF247D60, 0xE0112B22, Icon.FISH},
			{"lunge", 0xFFFF5E72, 0xFFA52A42, 0xE032141C, Icon.SPEAR},
			{"lure", 0xFF42D4D0, 0xFF237D7A, 0xE010292B, Icon.FISH},
			{"multishot", 0xFFD9A0FF, 0xFF8252A6, 0xE0271932, Icon.BOW},
			{"piercing", 0xFF9AC4E6, 0xFF55778E, 0xE019252D, Icon.ARROW},
			{"power", 0xFFF59C76, 0xFF9A4C36, 0xE02F1A16, Icon.BOW},
			{"projectile_protection", 0xFF92A8FF, 0xFF4D5B9C, 0xE0181D32, Icon.SHIELD},
			{"punch", 0xFFDC875C, 0xFF82442D, 0xE02C1A15, Icon.BOW},
			{"quick_charge", 0xFFFF91A8, 0xFF9D4960, 0xE0301821, Icon.ARROW},
			{"respiration", 0xFF78CBEF, 0xFF397C9A, 0xE0142731, Icon.DROP},
			{"riptide", 0xFF4E9DFF, 0xFF29589A, 0xE0111D32, Icon.TRIDENT},
			{"smite", 0xFFFFF08A, 0xFF9F8B35, 0xE02E2A14, Icon.SWORD},
			{"soul_speed", 0xFF65E6E1, 0xFF277F7C, 0xE0122D2C, Icon.BOOT},
			{"sweeping_edge", 0xFFF279A0, 0xFF97415F, 0xE02D1721, Icon.SWORD},
			{"swift_sneak", 0xFF8FCB83, 0xFF4C7848, 0xE01B291A, Icon.BOOT},
			{"thorns", 0xFFE66CCB, 0xFF873D79, 0xE02C172A, Icon.SPIKES},
			{"vanishing_curse", 0xFFB85AD9, 0xFF652D7A, 0xE025152C, Icon.SKULL},
			{"wind_burst", 0xFFA6F0D1, 0xFF4D9A80, 0xE0182D27, Icon.MACE}};
		String[] order = new String[table.length];
		for (int i = 0; i < table.length; i++) {
			Object[] row = table[i];
			String name = (String) row[0];
			order[i] = name;
			BY_ENCHANTMENT.put(name, new Style(name, (Integer) row[1], (Integer) row[2], (Integer) row[3], (Icon) row[4]));
		}
		PRIORITY = List.of(order);
	}

	private GlintStyles() {
	}

	/** The style of the enchantment whose id's path is {@code path}, or null for one the list doesn't have. */
	public static Style forEnchantment(String path) {
		return BY_ENCHANTMENT.get(path);
	}

	/** The icon by an enchantment's line: its own, or the generic star. */
	public static Icon icon(String path) {
		Style style = BY_ENCHANTMENT.get(path);
		return style == null ? Icon.STAR : style.icon();
	}

	/** {@code argb}'s alpha times {@code k} (0..255). */
	public static int scaleAlpha(int argb, float k) {
		int a = Math.max(0, Math.min(255, Math.round((argb >>> 24) * k)));
		return a << 24 | argb & 0xFFFFFF;
	}
}
