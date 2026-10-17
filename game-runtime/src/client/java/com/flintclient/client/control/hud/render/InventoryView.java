package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.UtilityMods.Inventory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Inventory's HUD: your 27 main inventory slots (not the hotbar) as a 9 by 3 grid of items with Minecraft's counts and
 * durability bars, on the shared panel when "Show background" is on. Hidden while the module's "Inventory HUD" is off,
 * and (unless "Show empty") while the inventory is empty. The sample is a few fixed items.
 */
final class InventoryView extends ModuleView {
	private static final int COLUMNS = 9;
	private static final int ROWS = 3;
	private static final ItemStack[] SAMPLE_ITEMS = new ItemStack[COLUMNS * ROWS];
	private final ItemStack[] stacks = new ItemStack[COLUMNS * ROWS];
	private boolean shown;
	private boolean background;
	private int cell;

	InventoryView(int surface) {
		super(Inventory.ID, surface);
	}

	@Override
	void update(ModuleState state, int mode, long nowNanos) {
		background = state.bool(Inventory.HUD_BACKGROUND);
		if (mode == SAMPLE || mode == PLACEHOLDER) {
			if (SAMPLE_ITEMS[0] == null) {
				java.util.Arrays.fill(SAMPLE_ITEMS, ItemStack.EMPTY);
				SAMPLE_ITEMS[0] = new ItemStack(Items.COBBLESTONE, 64);
				SAMPLE_ITEMS[3] = new ItemStack(Items.TORCH, 23);
				SAMPLE_ITEMS[10] = new ItemStack(Items.IRON_PICKAXE);
				SAMPLE_ITEMS[14] = new ItemStack(Items.BREAD, 12);
				SAMPLE_ITEMS[25] = new ItemStack(Items.ENDER_PEARL, 8);
			}
			System.arraycopy(SAMPLE_ITEMS, 0, stacks, 0, stacks.length);
			shown = true;
			return;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (!state.bool(Inventory.HUD) || player == null) {
			shown = false;
			return;
		}
		boolean any = false;
		for (int i = 0; i < stacks.length; i++) {
			stacks[i] = player.getInventory().getItem(9 + i);
			any |= !stacks[i].isEmpty();
		}
		shown = any || state.bool(Inventory.HUD_EMPTY);
	}

	@Override
	void measure(HudPen p, ModuleState state) {
		cell = Math.max(10, p.s.px(18));
		int pad = background ? p.s.px(3) : 0;
		width = shown ? cell * COLUMNS + 2 * pad : 0;
		height = shown ? cell * ROWS + 2 * pad : 0;
	}

	@Override
	void draw(HudPen p, ModuleState state, int x, int y) {
		if (!shown) {
			return;
		}
		int pad = background ? p.s.px(3) : 0;
		if (background) {
			p.fill(x, y, x + width, y + height, 0x70000000);
		}
		int item = Math.max(8, p.s.px(16));
		int inset = (cell - item) / 2;
		for (int i = 0; i < stacks.length; i++) {
			int cx = x + pad + (i % COLUMNS) * cell;
			int cy = y + pad + (i / COLUMNS) * cell;
			if (background) {
				p.fill(cx + 1, cy + 1, cx + cell - 1, cy + cell - 1, 0x30FFFFFF);
			}
			ItemStack stack = stacks[i];
			if (stack != null && !stack.isEmpty()) {
				p.item(stack, cx + inset, cy + inset, item, null);
			}
		}
	}

	@Override
	void forget() {
		java.util.Arrays.fill(stacks, null);
	}

	@Override
	String emptyReason() {
		return "Turn on Inventory HUD on the module's page";
	}
}
