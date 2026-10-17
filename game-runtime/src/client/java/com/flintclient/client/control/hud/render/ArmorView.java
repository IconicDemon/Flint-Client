package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.model.DurabilityFormat;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.ModuleSettings;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.Orientation;
import com.flintclient.client.control.platform.Mc263HudData;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.Tokens;
import java.util.Arrays;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Armor: the four armour pieces (helmet to boots), optionally the items in both hands, drawn as Minecraft
 * draws items in the hotbar (with its durability bar and stack count), each with its remaining durability as a
 * percentage, the uses left, or a thin bar of the real damage values (beside the item when stacked, under it in a
 * row). Below the chosen threshold the number or bar turns to the warning colour and gets a warning sign, so the
 * shape says it too (with the durability hidden, the sign alone). Empty slots are left out (or shown as empty
 * outlines), stacked or in a row. Without a world the four armour slots show as outlines with dashes.
 *
 * <p>The worn stacks are held only from {@link #update} to the end of {@link #draw} in the same frame: the view
 * is static, and a stack kept until the next frame could outlive the world it came from.
 */
final class ArmorView extends ModuleView {
	private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
		EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};
	private static final int ARMOR_SLOTS = 4;
	/** Stack counts as text, made once per count (Minecraft would make a new string every frame). */
	private static final String[] COUNTS = new String[1000];
	/** What shows with an item: nothing, its number, a dash (no data), its bar, or the warning sign alone. */
	private static final int NOTHING = 0;
	private static final int DIGITS = 1;
	private static final int DASH = 2;
	private static final int BAR = 3;
	private static final int WARNING = 4;
	/** A bar's length beside a stacked item, and its thickness, in module units. */
	private static final int BAR_LENGTH = 16;
	private static final int BAR_THICKNESS = 2;

	/** The stacks this frame draws (null for an empty or outlined slot), cleared once drawn. */
	private final @Nullable ItemStack[] stacks = new ItemStack[SLOTS.length];
	private final boolean[] shown = new boolean[SLOTS.length];
	private final boolean[] low = new boolean[SLOTS.length];
	private final char[][] text = new char[SLOTS.length][HudFormat.CHARS];
	private final int[] textLength = new int[SLOTS.length];
	/** What each slot's text was written from (damage, max damage, format), to rewrite it only on change. */
	private final long[] written = new long[SLOTS.length];
	private final int[] kind = new int[SLOTS.length];
	private final int[] damage = new int[SLOTS.length];
	private final int[] maxDamage = new int[SLOTS.length];
	private final int[] contentWidth = new int[SLOTS.length];
	private boolean vertical;
	private int itemPx;
	private int column;
	private int columnWidth;
	private boolean anyText;
	private boolean anyBar;

	ArmorView(int surface) {
		super(ModuleId.ARMOR, surface);
	}

	@Override
	void update(ModuleState state, int mode, long nowNanos) {
		vertical = state.choice(ModuleSettings.Armor.LAYOUT) == Orientation.VERTICAL;
		boolean lowWarning = state.bool(ModuleSettings.Armor.LOW_WARNING);
		int threshold = state.integer(ModuleSettings.Armor.LOW_THRESHOLD);
		boolean hands = state.bool(ModuleSettings.Armor.SHOW_HANDS);
		boolean hideEmpty = state.bool(ModuleSettings.Armor.HIDE_EMPTY);
		DurabilityFormat format = state.choice(ModuleSettings.Armor.DURABILITY);
		LocalPlayer player = mode == PLACEHOLDER ? null : Mc263HudData.player();
		int count = hands ? SLOTS.length : ARMOR_SLOTS;
		boolean any = false;
		for (int i = 0; i < SLOTS.length; i++) {
			stacks[i] = null;
			shown[i] = false;
			low[i] = false;
			kind[i] = NOTHING;
			if (i >= count) {
				continue;
			}
			if (player == null) {
				// No world: the armour slots as outlines, a dash (or an empty bar) where the durability would be.
				shown[i] = i < ARMOR_SLOTS;
				kind[i] = format == DurabilityFormat.NONE ? NOTHING : format == DurabilityFormat.BAR ? BAR : DASH;
				maxDamage[i] = 0;
				any |= shown[i];
				continue;
			}
			ItemStack stack = player.getItemBySlot(SLOTS[i]);
			if (stack.isEmpty()) {
				shown[i] = !hideEmpty;
				any |= shown[i];
				continue;
			}
			stacks[i] = stack;
			shown[i] = true;
			any = true;
			if (!stack.isDamageableItem()) {
				continue;
			}
			int used = stack.getDamageValue();
			int max = stack.getMaxDamage();
			low[i] = lowWarning && HudFormat.lowDurability(used, max, threshold);
			switch (format) {
				case PERCENT, NUMBER -> {
					write(i, used, max, format);
					kind[i] = DIGITS;
				}
				case BAR -> {
					damage[i] = used;
					maxDamage[i] = max;
					kind[i] = BAR;
				}
				case NONE -> kind[i] = low[i] ? WARNING : NOTHING;
			}
		}
		if (!any && mode == PREVIEW) {
			// Nothing worn: a preview still shows where the slots go.
			for (int i = 0; i < ARMOR_SLOTS; i++) {
				shown[i] = true;
			}
		}
	}

	private void write(int slot, int used, int max, DurabilityFormat format) {
		long key = (long) used << 32 | (max & 0x3FFFFFFFL) << 2 | format.ordinal();
		if (key == written[slot] && textLength[slot] > 0) {
			return;
		}
		written[slot] = key;
		char[] out = text[slot];
		if (format == DurabilityFormat.PERCENT) {
			int n = HudFormat.integer(HudFormat.percentLeft(used, max), out);
			out[n++] = '%';
			textLength[slot] = n;
		} else {
			textLength[slot] = HudFormat.integer(Math.max(0, max - used), out);
		}
	}

	@Override
	void measure(HudPen p, ModuleState state) {
		HudStyle s = p.s;
		itemPx = Math.max(8, s.px(16));
		int warn = warningPx(p) + Math.max(1, s.px(2));
		column = 0;
		anyText = false;
		anyBar = false;
		int slots = 0;
		for (int i = 0; i < SLOTS.length; i++) {
			if (!shown[i]) {
				continue;
			}
			slots++;
			int w = switch (kind[i]) {
				case DIGITS -> p.width(text[i], textLength[i], HudFaces.VALUE) + (low[i] ? warn : 0);
				case DASH -> p.width(HudField.DASH, HudFaces.VALUE);
				// Beside a stacked item the bar is its own length; under an item in a row, the item's width.
				case BAR -> vertical ? barLength(p) + (low[i] ? warn : 0) : itemPx;
				case WARNING -> warningPx(p);
				default -> 0;
			};
			contentWidth[i] = w;
			column = Math.max(column, w);
			anyText |= kind[i] == DIGITS || kind[i] == DASH || kind[i] == WARNING;
			anyBar |= kind[i] == BAR;
		}
		if (slots == 0) {
			width = 0;
			height = 0;
			return;
		}
		int gap = Math.max(1, s.px(2));
		if (vertical) {
			int row = Math.max(itemPx, s.lineH);
			width = itemPx + (column > 0 ? s.px(3) + column : 0);
			height = slots * row + (slots - 1) * gap;
		} else {
			columnWidth = Math.max(itemPx, column);
			width = slots * columnWidth + (slots - 1) * s.px(3);
			height = itemPx + (anyText ? gap + s.lineH : anyBar ? barGap(p) + barThickness(p) : 0);
		}
	}

	@Override
	void draw(HudPen p, ModuleState state, int x, int y) {
		HudStyle s = p.s;
		int gap = Math.max(1, s.px(2));
		int row = Math.max(itemPx, s.lineH);
		int at = vertical ? y : x;
		for (int i = 0; i < SLOTS.length; i++) {
			if (!shown[i]) {
				continue;
			}
			if (vertical) {
				int itemY = at + (row - itemPx) / 2;
				slot(p, i, x, itemY);
				if (contentWidth[i] > 0) {
					int contentX = x + itemPx + s.px(3);
					if (kind[i] == BAR) {
						bar(p, i, contentX, at + row / 2, barLength(p), true);
					} else {
						label(p, i, contentX, p.baseline(at + (row - s.lineH) / 2));
					}
				}
				at += row + gap;
			} else {
				int itemX = at + (columnWidth - itemPx) / 2;
				slot(p, i, itemX, y);
				if (kind[i] == BAR) {
					bar(p, i, itemX, y + itemPx + barGap(p) + barThickness(p) / 2, itemPx, false);
				} else if (contentWidth[i] > 0) {
					label(p, i, at + (columnWidth - contentWidth[i]) / 2, p.baseline(y + itemPx + gap));
				}
				at += columnWidth + s.px(3);
			}
		}
		forget();
	}

	@Override
	void forget() {
		Arrays.fill(stacks, null);
	}

	private void slot(HudPen p, int i, int x, int y) {
		ItemStack stack = stacks[i];
		if (stack == null) {
			HudStyle s = p.s;
			p.ring(x + 1, y + 1, itemPx - 2, itemPx - 2, s.classic() ? 0 : s.px(2), Math.max(1, s.unitPx / 3),
				Palette.withAlpha(s.text, 0.25F));
			return;
		}
		int count = stack.getCount();
		p.item(stack, x, y, itemPx, count == 1 ? null : countText(count));
	}

	/** The number (or dash, or the warning sign alone) from {@code x} on {@code baseline}. */
	private void label(HudPen p, int i, int x, int baseline) {
		HudStyle s = p.s;
		if (kind[i] == DASH) {
			p.text(HudField.DASH, HudFaces.VALUE, x, baseline, s.muted);
			return;
		}
		int at = x;
		if (low[i]) {
			int size = warningPx(p);
			p.icon(Icon.WARNING, at, baseline - p.cap(HudFaces.VALUE) / 2 - size / 2, size, Tokens.WARNING);
			at += size + Math.max(1, s.px(2));
		}
		if (kind[i] == DIGITS) {
			p.digits(text[i], textLength[i], HudFaces.VALUE, at, baseline, low[i] ? Tokens.WARNING : s.text);
		}
	}

	/**
	 * The durability bar from {@code x}, {@code length} px long, centred on {@code centerY}: a faint track and the
	 * part left solid (the warning colour when low). Beside a stacked item a low bar also gets the warning sign in
	 * front; under an item in a row the sign sits on the item's corner.
	 */
	private void bar(HudPen p, int i, int x, int centerY, int length, boolean beside) {
		HudStyle s = p.s;
		int thickness = barThickness(p);
		int at = x;
		if (low[i]) {
			int size = warningPx(p);
			if (beside) {
				p.icon(Icon.WARNING, at, centerY - size / 2, size, Tokens.WARNING);
				at += size + Math.max(1, s.px(2));
			} else {
				int itemTop = centerY - thickness / 2 - barGap(p) - itemPx;
				p.icon(Icon.WARNING, x + itemPx - size, itemTop, size, Tokens.WARNING);
			}
		}
		int top = centerY - thickness / 2;
		int track = Palette.withAlpha(s.text, (s.text >>> 24) / 255.0F * 0.25F);
		p.fill(at, top, at + length, top + thickness, track);
		int fill = maxDamage[i] <= 0 ? 0 : HudFormat.barFill(damage[i], maxDamage[i], length);
		if (fill > 0) {
			p.fill(at, top, at + fill, top + thickness, low[i] ? Tokens.WARNING : s.text);
		}
	}

	private static int barLength(HudPen p) {
		return Math.max(4, p.s.px(BAR_LENGTH));
	}

	private static int barThickness(HudPen p) {
		return Math.max(1, p.s.px(BAR_THICKNESS));
	}

	private static int barGap(HudPen p) {
		return Math.max(1, p.s.px(1));
	}

	private static int warningPx(HudPen p) {
		return Math.max(5, Math.round(p.cap(HudFaces.VALUE) * 1.4F));
	}

	private static @Nullable String countText(int count) {
		if (count < 0 || count >= COUNTS.length) {
			return null;
		}
		String text = COUNTS[count];
		if (text == null) {
			text = Integer.toString(count);
			COUNTS[count] = text;
		}
		return text;
	}

	@Override
	String emptyReason() {
		return "Nothing equipped";
	}

	@Override
	@Nullable String noLabelReason(HudThemeId theme) {
		return "Armor shows its items, so there is no label";
	}

	@Override
	@Nullable String noAccentReason() {
		return "Armor has no labels to tint";
	}

	@Override
	int summary(ModuleState state, char[] out, long nowNanos) {
		LocalPlayer player = Mc263HudData.player();
		if (player == null) {
			return -1;
		}
		int pieces = 0;
		int lowest = -1;
		for (int i = 0; i < ARMOR_SLOTS; i++) {
			ItemStack stack = player.getItemBySlot(SLOTS[i]);
			if (stack.isEmpty()) {
				continue;
			}
			pieces++;
			if (stack.isDamageableItem()) {
				int percent = HudFormat.percentLeft(stack.getDamageValue(), stack.getMaxDamage());
				lowest = lowest < 0 ? percent : Math.min(lowest, percent);
			}
		}
		return HudFormat.armorSummary(pieces, lowest, out);
	}
}
