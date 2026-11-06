package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.feature.Features;
import com.flintclient.client.control.feature.InventoryRules;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.UtilityMods.Inventory;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 26.3's side of the Inventory module: the search box and highlights in container screens, slot locks and
 * bindings, drop protection, rarity frames, durability numbers and enchantment summaries on slots (and the hotbar), the
 * hotbar key labels, containers sliding in and out, and keeping the pointer between screens. Every guard only stops the
 * player's own clicks and keys on this side; the one thing sent for them is the swap a Shift-click on a bound slot asks
 * for. Render thread; fault-isolated (a failure leaves the screens vanilla).
 */
public final class Mc263Inventory {
	private static final int MODULE = Mods.index(Inventory.ID);
	private static final HookGuard GUARD = new HookGuard("inventory",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final int DIM = 0xC0101010;
	private static final int MATCH = 0xFFFF8139;

	private static String query = "";
	private static String[] words = new String[0];
	private static final Map<ItemStack, Boolean> MATCHES = new IdentityHashMap<>();
	private static long blockedMessageAt;
	private static boolean cachedLore;
	// Don't reset cursor: where the pointer was when the last screen closed, and when.
	private static double keptX = Double.NaN;
	private static double keptY = Double.NaN;
	private static long keptAt;
	private static boolean keptFromContainer;

	private Mc263Inventory() {
	}

	private static boolean on() {
		return GUARD.on() && Mods.on(MODULE);
	}

	private static ModuleState state() {
		return Mods.state(MODULE);
	}

	// ---------- search ----------

	/** A container screen was laid out: its search box (above it, or below when there's no room), or null. */
	public static @Nullable EditBox searchBox(AbstractContainerScreen<?> screen, int left, int top, int imageWidth, int imageHeight) {
		if (!on() || screen instanceof CreativeModeInventoryScreen || !state().bool(Inventory.SEARCH)) {
			return null;
		}
		try {
			Font font = Minecraft.getInstance().font;
			int y = top >= 16 ? top - 14 : top + imageHeight + 2;
			EditBox box = new EditBox(font, left, y, imageWidth, 12, Component.literal("Search items"));
			box.setMaxLength(50);
			box.setHint(Component.literal("Search items…"));
			box.setValue(query);
			box.setResponder(Mc263Inventory::searched);
			return box;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return null;
		}
	}

	private static void searched(String text) {
		query = text;
		words = InventoryRules.words(text);
		MATCHES.clear();
	}

	/** Whether {@code stack} matches the current search (cached per stack until the search changes). */
	private static boolean matches(ItemStack stack, boolean lore) {
		if (lore != cachedLore) {
			cachedLore = lore;
			MATCHES.clear();
		}
		Boolean known = MATCHES.get(stack);
		if (known != null) {
			return known;
		}
		StringBuilder text = new StringBuilder(stack.getHoverName().getString().toLowerCase(Locale.ROOT));
		text.append(' ').append(BuiltInRegistries.ITEM.getKey(stack.getItem()));
		if (lore) {
			ItemLore itemLore = stack.get(DataComponents.LORE);
			if (itemLore != null) {
				for (Component line : itemLore.lines()) {
					text.append(' ').append(line.getString().toLowerCase(Locale.ROOT));
				}
			}
		}
		boolean match = InventoryRules.matches(words, text.toString());
		if (MATCHES.size() > 512) {
			MATCHES.clear();
		}
		MATCHES.put(stack, match);
		return match;
	}

	/**
	 * After the slots are drawn (the pose is at the container's corner): search highlights, lock marks, rarity frames,
	 * slot bindings, then (above the items) durability numbers and enchantment summaries.
	 */
	public static void decorateSlots(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, @Nullable Slot hovered, int imageWidth,
		int imageHeight) {
		containerDrawnAt = System.currentTimeMillis();
		if (!on() || screen instanceof CreativeModeInventoryScreen) {
			return;
		}
		try {
			ModuleState state = state();
			boolean search = state.bool(Inventory.SEARCH) && words.length > 0;
			boolean dim = state.option(Inventory.HIGHLIGHT) == 0;
			boolean lore = state.bool(Inventory.SEARCH_LORE);
			List<String> locked = state.bool(Inventory.LOCKING) ? state.ids(Inventory.LOCKED) : List.of();
			int lockColor = 0xFF000000 | state.rgb(Inventory.LOCK_COLOR);
			boolean own = ownInventory(screen);
			if (pendingScreen != screen) {
				pendingScreen = null;
				pendingBind = -1;
			}
			List<String> bindings = own && state.bool(Inventory.BINDING) ? state.ids(Inventory.BOUND) : List.of();
			int bindColor = 0xFF000000 | state.rgb(Inventory.BIND_COLOR);
			boolean rarity = state.bool(Inventory.RARITY);
			boolean text = state.bool(Inventory.DURABILITY) || state.bool(Inventory.ENCHANTS);
			for (Slot slot : screen.getMenu().slots) {
				if (!slot.isActive()) {
					continue;
				}
				ItemStack stack = slot.getItem();
				if (rarity && !stack.isEmpty()) {
					rarityFrame(graphics, state, stack, slot.x, slot.y);
				}
				if (search) {
					boolean match = !stack.isEmpty() && matches(stack, lore);
					if (dim && !match) {
						graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, DIM);
					} else if (!dim && match) {
						graphics.outline(slot.x - 1, slot.y - 1, 18, 18, MATCH);
					}
				}
				boolean inventory = slot.container instanceof net.minecraft.world.entity.player.Inventory;
				if (!locked.isEmpty() && inventory && InventoryRules.locked(locked, slot.getContainerSlot())) {
					graphics.outline(slot.x - 1, slot.y - 1, 18, 18, (lockColor & 0xFFFFFF) | 0x90000000);
					graphics.fill(slot.x + 11, slot.y, slot.x + 16, slot.y + 5, lockColor);
					graphics.fill(slot.x + 12, slot.y + 1, slot.x + 15, slot.y + 4, 0xFF202020);
				}
				if (inventory && !bindings.isEmpty() && InventoryRules.boundTo(bindings, slot.getContainerSlot()) >= 0) {
					graphics.fill(slot.x, slot.y + 13, slot.x + 3, slot.y + 16, bindColor);
				}
			}
			if (own && state.bool(Inventory.BINDING)) {
				bindingLink(graphics, screen, hovered, bindings, bindColor, imageWidth, imageHeight);
			}
			if (text) {
				// Above the items.
				graphics.nextStratum();
				for (Slot slot : screen.getMenu().slots) {
					if (slot.isActive() && !slot.getItem().isEmpty()) {
						itemText(graphics, state, slot.getItem(), slot.x, slot.y, state.bool(Inventory.DURABILITY), state.bool(Inventory.ENCHANTS));
					}
				}
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	// ---------- rarity, durability and enchantments on slots ----------

	/** An uncommon, rare or epic item's frame inside its slot, in its rarity's colour. */
	private static void rarityFrame(GuiGraphicsExtractor graphics, ModuleState state, ItemStack stack, int x, int y) {
		net.minecraft.network.chat.TextColor rgb = stack.getRarity() == Rarity.COMMON ? null
			: net.minecraft.network.chat.TextColor.fromLegacyFormat(stack.getRarity().color());
		if (rgb == null) {
			return;
		}
		int color = 0xE0000000 | rgb.getValue();
		int thickness = state.integer(Inventory.RARITY_THICKNESS);
		for (int i = 0; i < thickness; i++) {
			graphics.outline(x + i, y + i, 16 - 2 * i, 16 - 2 * i, color);
		}
	}

	/** A worn item's uses left (bottom right) and its enchantments' summaries (top left), small, at the item's corner. */
	private static void itemText(GuiGraphicsExtractor graphics, ModuleState state, ItemStack stack, int x, int y, boolean durability, boolean enchants) {
		Font font = Minecraft.getInstance().font;
		boolean worn = durability && stack.isDamageableItem() && stack.isDamaged();
		if (worn) {
			String left = String.valueOf(stack.getMaxDamage() - stack.getDamageValue());
			float scale = Math.min(state.integer(Inventory.DURABILITY_SCALE) / 100F, 16F / font.width(left));
			small(graphics, font, left, x + 16 - font.width(left) * scale, y + 13 - 8 * scale, scale, 0xFF000000 | stack.getBarColor());
		}
		if (enchants && (state.bool(Inventory.ENCHANTS_BOOKS) || !stack.is(Items.ENCHANTED_BOOK))) {
			ItemEnchantments all = EnchantmentHelper.getEnchantmentsForCrafting(stack);
			if (all.isEmpty()) {
				return;
			}
			// Two lines leave the bottom corner to the durability number.
			int max = Math.min(state.integer(Inventory.ENCHANTS_MAX), worn ? 2 : 3);
			int color = 0xFF000000 | state.rgb(Inventory.ENCHANTS_COLOR);
			int top = 0xFF000000 | state.rgb(Inventory.ENCHANTS_TOP_COLOR);
			int line = 0;
			for (Holder<Enchantment> enchantment : tooltipOrder(all)) {
				if (line >= max) {
					break;
				}
				int level = all.getLevel(enchantment);
				int maxLevel = enchantment.value().getMaxLevel();
				String code = InventoryRules.enchantCode(enchantment.value().description().getString(), level, maxLevel);
				int c = enchantment.is(EnchantmentTags.CURSE) ? 0xFFFF5555 : maxLevel > 1 && level >= maxLevel ? top : color;
				small(graphics, font, code, x, y + line * 4.5F, 0.5F, c);
				line++;
			}
		}
	}

	/** {@code all}'s enchantments in the order an item's tooltip lists them (Sharpness before Unbreaking before Mending). */
	private static List<Holder<Enchantment>> tooltipOrder(ItemEnchantments all) {
		List<Holder<Enchantment>> out = new java.util.ArrayList<>(all.size());
		net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
		if (level != null) {
			level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).get(EnchantmentTags.TOOLTIP_ORDER)
				.ifPresent(order -> {
					for (Holder<Enchantment> enchantment : order) {
						if (all.getLevel(enchantment) > 0) {
							out.add(enchantment);
						}
					}
				});
		}
		for (Holder<Enchantment> enchantment : all.keySet()) {
			if (!out.contains(enchantment)) {
				out.add(enchantment);
			}
		}
		return out;
	}

	private static void small(GuiGraphicsExtractor graphics, Font font, String text, float x, float y, float scale, int color) {
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(scale, scale);
		graphics.text(font, text, 0, 0, color, true);
		graphics.pose().popMatrix();
	}

	/** After the hotbar is drawn: rarity frames, durability numbers, enchantment summaries and the held slot's frame, as asked. */
	public static void hotbarDecorations(GuiGraphicsExtractor graphics) {
		if (!on()) {
			return;
		}
		try {
			ModuleState state = state();
			boolean rarity = state.bool(Inventory.RARITY) && state.bool(Inventory.RARITY_HOTBAR);
			boolean durability = state.bool(Inventory.DURABILITY) && state.bool(Inventory.DURABILITY_HOTBAR);
			boolean enchants = state.bool(Inventory.ENCHANTS) && state.bool(Inventory.ENCHANTS_HOTBAR);
			boolean frame = state.bool(Inventory.SELECTED_FRAME);
			LocalPlayer player = Minecraft.getInstance().player;
			if (!(rarity || durability || enchants || frame) || player == null || player.isSpectator()) {
				return;
			}
			int center = graphics.guiWidth() / 2;
			int top = graphics.guiHeight() - 19;
			net.minecraft.world.entity.player.Inventory inventory = player.getInventory();
			if (frame) {
				int color = 0xFF000000 | state.rgb(Inventory.SELECTED_COLOR);
				int x = center - 92 + inventory.getSelectedSlot() * 20;
				graphics.outline(x, top - 4, 24, 24, color);
				graphics.outline(x + 1, top - 3, 22, 22, color);
			}
			if (rarity) {
				for (int i = 0; i < 9; i++) {
					ItemStack stack = inventory.getItem(i);
					if (!stack.isEmpty()) {
						rarityFrame(graphics, state, stack, center - 88 + i * 20, top);
					}
				}
			}
			if (durability || enchants) {
				graphics.nextStratum();
				for (int i = 0; i < 9; i++) {
					ItemStack stack = inventory.getItem(i);
					if (!stack.isEmpty()) {
						itemText(graphics, state, stack, center - 88 + i * 20, top, durability, enchants);
					}
				}
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	// ---------- slot binding ----------

	/** The slot (inventory index) the bind key was last pressed over, waiting for its other slot; -1 for none. */
	private static int pendingBind = -1;
	private static @Nullable Screen pendingScreen;
	private static long boundSwapAt;

	/** Whether {@code screen} is your own inventory (where bindings show and Shift-click swaps). */
	private static boolean ownInventory(AbstractContainerScreen<?> screen) {
		LocalPlayer player = Minecraft.getInstance().player;
		return player != null && screen.getMenu() == player.inventoryMenu && !(screen instanceof CreativeModeInventoryScreen);
	}

	private static @Nullable Slot slotOf(AbstractContainerScreen<?> screen, int inventoryIndex) {
		for (Slot slot : screen.getMenu().slots) {
			if (slot.container instanceof net.minecraft.world.entity.player.Inventory && slot.getContainerSlot() == inventoryIndex) {
				return slot;
			}
		}
		return null;
	}

	/** The hovered bound slot's link to its partner, the slot waiting for its partner, and what to do next. */
	private static void bindingLink(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, @Nullable Slot hovered, List<String> bindings,
		int color, int imageWidth, int imageHeight) {
		if (hovered != null && hovered.container instanceof net.minecraft.world.entity.player.Inventory) {
			int partner = InventoryRules.boundTo(bindings, hovered.getContainerSlot());
			Slot other = partner < 0 ? null : slotOf(screen, partner);
			if (other != null) {
				graphics.outline(hovered.x - 1, hovered.y - 1, 18, 18, color);
				graphics.outline(other.x - 1, other.y - 1, 18, 18, color);
				int ax = hovered.x + 8;
				int ay = hovered.y + 8;
				int bx = other.x + 8;
				int by = other.y + 8;
				int line = (color & 0xFFFFFF) | 0xC0000000;
				graphics.fill(Math.min(ax, bx), ay, Math.max(ax, bx) + 1, ay + 1, line);
				graphics.fill(bx, Math.min(ay, by), bx + 1, Math.max(ay, by) + 1, line);
			}
		}
		if (pendingBind >= 0) {
			Slot waiting = slotOf(screen, pendingBind);
			if (waiting != null) {
				int alpha = 0x60 + (int) (0x9F * (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 150.0)));
				graphics.outline(waiting.x - 1, waiting.y - 1, 18, 18, (color & 0xFFFFFF) | alpha << 24);
			}
			Font font = Minecraft.getInstance().font;
			String bound = state().text(Inventory.BIND_KEY);
			String key = bound.isEmpty() ? "the bind key" : InputConstants.getKey(bound).getDisplayName().getString();
			String hint = "Press " + key + " over " + (pendingBind < 9 ? "an inventory slot" : "a hotbar slot") + " to bind it";
			graphics.nextStratum();
			graphics.centeredText(font, hint, imageWidth / 2, imageHeight + 4, 0xFFFFFFFF);
		}
	}

	/** The bind key over {@code hovered}: starts a binding, finishes it on a slot of the other kind, or unbinds a bound slot. */
	private static void bindKey(ModuleState state, Screen screen, Slot hovered) {
		int slot = hovered.getContainerSlot();
		if (slot < 0 || slot > 35) {
			blocked("Only hotbar and inventory slots can be bound");
			return;
		}
		List<String> bindings = state.ids(Inventory.BOUND);
		List<String> next = null;
		if (pendingBind < 0 || pendingScreen != screen) {
			pendingScreen = screen;
			if (InventoryRules.boundTo(bindings, slot) >= 0) {
				next = InventoryRules.unbind(bindings, slot);
				pendingBind = -1;
			} else {
				pendingBind = slot;
			}
		} else if (pendingBind == slot) {
			pendingBind = -1;
		} else if (pendingBind < 9 == slot < 9) {
			pendingBind = slot;
		} else {
			next = InventoryRules.bind(bindings, Math.max(slot, pendingBind), Math.min(slot, pendingBind));
			pendingBind = -1;
		}
		if (next != null && state.set(Inventory.BOUND, Inventory.BOUND.clamp(next))) {
			ControlState.settings().recordChange(Inventory.ID);
		}
	}

	/**
	 * A Shift-click in your own inventory on a bound slot: instead of moving the stack, the one swap with its hotbar slot
	 * that the hotbar's number key would make (locks still apply). True: done here.
	 */
	public static boolean bindingClick(AbstractContainerScreen<?> screen, @Nullable Slot slot, ContainerInput input) {
		if (input != ContainerInput.QUICK_MOVE || slot == null || !on() || !(slot.container instanceof net.minecraft.world.entity.player.Inventory)
			|| !ownInventory(screen)) {
			return false;
		}
		try {
			ModuleState state = state();
			if (!state.bool(Inventory.BINDING)) {
				return false;
			}
			int here = slot.getContainerSlot();
			int partner = InventoryRules.boundTo(state.ids(Inventory.BOUND), here);
			long now = System.currentTimeMillis();
			// A Shift double-click moves every matching stack one by one: only the first, clicked one swaps.
			if (partner < 0 || now - boundSwapAt < 250) {
				return partner >= 0;
			}
			int hotbar = Math.min(here, partner);
			Slot inventorySlot = slotOf(screen, Math.max(here, partner));
			Minecraft mc = Minecraft.getInstance();
			if (inventorySlot == null || mc.gameMode == null || mc.player == null) {
				return false;
			}
			boundSwapAt = now;
			if (!blockClick(screen, inventorySlot, inventorySlot.index, hotbar, ContainerInput.SWAP)) {
				mc.gameMode.handleContainerInput(screen.getMenu().containerId, inventorySlot.index, hotbar, ContainerInput.SWAP, mc.player);
			}
			return true;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return false;
		}
	}

	// ---------- open / close animations ----------

	private static @Nullable Screen animated;
	private static long openedAt;
	private static long closingAt;
	private static boolean closeQueued;
	private static boolean moved;
	/** The animation's transform now: offset x, offset y, scale (about the screen's middle). */
	private static final float[] MOTION = new float[3];

	/** A container screen was laid out: a new one starts its opening animation (a resize doesn't restart it). */
	public static void opened(AbstractContainerScreen<?> screen) {
		if (screen != animated) {
			animated = screen;
			openedAt = System.currentTimeMillis();
			closingAt = 0;
			closeQueued = false;
		}
	}

	private static boolean animates(Screen screen) {
		return screen == animated && !(screen instanceof CreativeModeInventoryScreen) && on() && state().bool(Inventory.ANIMATIONS)
			&& (state().bool(Inventory.MOVE) || state().bool(Inventory.SCALE));
	}

	/** {@code screen} is asked to close: true to wait while it animates out first (asking again closes it at once). */
	public static boolean deferClose(AbstractContainerScreen<?> screen) {
		try {
			if (closingAt != 0 || !animates(screen)) {
				return false;
			}
			closingAt = System.currentTimeMillis();
			return true;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return false;
		}
	}

	/** Whether {@code screen} is animating out (its clicks are ignored). */
	public static boolean closing(Screen screen) {
		return screen == animated && closingAt != 0;
	}

	/** Fills {@link #MOTION} for {@code screen} now; false when it's drawn where it is. */
	private static boolean motion(Screen screen) {
		if (!animates(screen)) {
			return false;
		}
		ModuleState state = state();
		long now = System.currentTimeMillis();
		float duration = state.integer(Inventory.ANIMATION_MS);
		float shown;
		if (closingAt != 0) {
			float t = (now - closingAt) / duration;
			if (t >= 1 && !closeQueued) {
				closeQueued = true;
				Minecraft mc = Minecraft.getInstance();
				mc.schedule(() -> {
					if (mc.gui.screen() == screen) {
						screen.onClose();
					}
				});
			}
			shown = 1 - InventoryRules.ease(state.option(Inventory.EASING), t);
		} else {
			float t = (now - openedAt) / duration;
			if (t >= 1) {
				return false;
			}
			shown = InventoryRules.ease(state.option(Inventory.EASING), t);
		}
		float away = 1 - shown;
		MOTION[0] = 0;
		MOTION[1] = 0;
		if (state.bool(Inventory.MOVE)) {
			switch (state.option(Inventory.DIRECTION)) {
				case 1 -> MOTION[1] = -away * screen.height;
				case 2 -> MOTION[0] = -away * screen.width;
				case 3 -> MOTION[0] = away * screen.width;
				default -> MOTION[1] = away * screen.height;
			}
		}
		MOTION[2] = !state.bool(Inventory.SCALE) ? 1 : Math.max(0.01F, state.bool(Inventory.MOVE) ? 0.7F + 0.3F * shown : shown);
		return true;
	}

	/** After the screen's dimmed backdrop: the container (and what's drawn over it) moves and scales from here on. */
	public static void animationBegin(GuiGraphicsExtractor graphics, Screen screen) {
		if (moved) {
			return;
		}
		try {
			if (!motion(screen)) {
				return;
			}
			float cx = screen.width / 2F;
			float cy = screen.height / 2F;
			graphics.pose().pushMatrix();
			graphics.pose().translate(cx + MOTION[0], cy + MOTION[1]);
			graphics.pose().scale(MOTION[2], MOTION[2]);
			graphics.pose().translate(-cx, -cy);
			moved = true;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** Whether the container being drawn is moving now (what can't move with it waits). */
	public static boolean moving() {
		return moved;
	}

	/** The screen is drawn: back to the usual pose. */
	public static void animationEnd(GuiGraphicsExtractor graphics) {
		if (moved) {
			moved = false;
			graphics.pose().popMatrix();
		}
	}

	/** Where the pointer at {@code x} ({@code vertical}: at {@code y}) is on the moving container. */
	public static double pointer(Screen screen, double at, boolean vertical) {
		try {
			if (screen != animated || !motion(screen)) {
				return at;
			}
			float middle = vertical ? screen.height / 2F : screen.width / 2F;
			return (at - middle - MOTION[vertical ? 1 : 0]) / MOTION[2] + middle;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return at;
		}
	}

	/**
	 * A key in a container screen: the search box takes every key while it has the cursor (Esc or Enter gives the cursor
	 * back); the search key puts it there; the lock key locks or unlocks the slot under the pointer, and the bind key binds
	 * or unbinds it (your own inventory). True: used up.
	 */
	public static boolean keyPressed(Screen screen, @Nullable EditBox search, @Nullable Slot hovered, KeyEvent event) {
		if (!on()) {
			return false;
		}
		try {
			ModuleState state = state();
			if (search != null && search.isFocused()) {
				if (event.isEscape() || event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
					search.setFocused(false);
					screen.setFocused(null);
					return true;
				}
				search.keyPressed(event);
				return true;
			}
			String name = InputConstants.getKey(event).getName();
			if (search != null && name.equals(state.text(Inventory.SEARCH_KEY))) {
				screen.setFocused(search);
				search.setFocused(true);
				return true;
			}
			if (hovered != null && state.bool(Inventory.LOCKING) && name.equals(state.text(Inventory.LOCK_KEY))
				&& hovered.container instanceof net.minecraft.world.entity.player.Inventory) {
				String id = InventoryRules.slotId(hovered.getContainerSlot());
				List<String> locked = state.ids(Inventory.LOCKED);
				if (state.set(Inventory.LOCKED, Inventory.LOCKED.with(locked, id, !locked.contains(id)))) {
					ControlState.settings().recordChange(Inventory.ID);
				}
				return true;
			}
			if (hovered != null && state.bool(Inventory.BINDING) && name.equals(state.text(Inventory.BIND_KEY))
				&& hovered.container instanceof net.minecraft.world.entity.player.Inventory
				&& screen instanceof AbstractContainerScreen<?> container && ownInventory(container)) {
				bindKey(state, screen, hovered);
				return true;
			}
			return false;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return false;
		}
	}

	/** A click in a container screen: whether to stop it (a locked slot, or throwing a protected item away). */
	public static boolean blockClick(AbstractContainerScreen<?> screen, @Nullable Slot slot, int slotId, int button, ContainerInput input) {
		if (input == ContainerInput.CLONE) {
			return false;
		}
		if (dropPrevented(screen, slot, slotId, input)) {
			return blocked("That item is protected from dropping");
		}
		if (!on()) {
			return false;
		}
		try {
			ModuleState state = state();
			LocalPlayer player = Minecraft.getInstance().player;
			if (player == null) {
				return false;
			}
			if (state.bool(Inventory.LOCKING)) {
				List<String> locked = state.ids(Inventory.LOCKED);
				if (slot != null && slot.container instanceof net.minecraft.world.entity.player.Inventory
					&& InventoryRules.locked(locked, slot.getContainerSlot())) {
					return blocked("That slot is locked");
				}
				// A number key or the offhand key swapping into a locked hotbar or offhand slot.
				if (input == ContainerInput.SWAP && (button == 40 ? InventoryRules.locked(locked, 40) : InventoryRules.locked(locked, button))) {
					return blocked("That slot is locked");
				}
			}
			if (state.bool(Inventory.PROTECT)) {
				boolean own = slot != null && slot.container instanceof net.minecraft.world.entity.player.Inventory;
				boolean anywhere = state.bool(Inventory.PROTECT_CONTAINERS) || screen.getMenu() == player.inventoryMenu;
				List<String> items = state.ids(Inventory.PROTECTED);
				if (input == ContainerInput.THROW && slot != null && (own || anywhere) && isProtected(items, slot.getItem())) {
					return blocked("That item is protected from dropping");
				}
				// Clicking outside the window drops what the pointer holds.
				if (slotId == -999 && (input == ContainerInput.PICKUP || input == ContainerInput.THROW) && anywhere
					&& isProtected(items, screen.getMenu().getCarried())) {
					return blocked("That item is protected from dropping");
				}
			}
			return false;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return false;
		}
	}

	/** Drop Prevention in an open inventory: throwing one of its items out (a drop key over a slot, or a click outside). */
	private static boolean dropPrevented(AbstractContainerScreen<?> screen, @Nullable Slot slot, int slotId, ContainerInput input) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || !Mc263Tools.inInventories()) {
			return false;
		}
		if (input == ContainerInput.THROW && slot != null && Mc263Tools.protectedItem(slot.getItem())) {
			return true;
		}
		return slotId == -999 && (input == ContainerInput.PICKUP || input == ContainerInput.THROW) && Mc263Tools.protectedItem(screen.getMenu().getCarried());
	}

	/** The drop key in the world: whether to stop it (the held item is protected, or its hotbar slot is locked). */
	public static boolean blockDrop(LocalPlayer player) {
		if (Mc263Tools.protectedItem(player.getMainHandItem())) {
			return blocked("That item is protected from dropping");
		}
		if (!on()) {
			return false;
		}
		try {
			ModuleState state = state();
			ItemStack held = player.getMainHandItem();
			if (held.isEmpty()) {
				return false;
			}
			if (state.bool(Inventory.LOCKING) && InventoryRules.locked(state.ids(Inventory.LOCKED), player.getInventory().getSelectedSlot())) {
				return blocked("That slot is locked");
			}
			if (state.bool(Inventory.PROTECT) && isProtected(state.ids(Inventory.PROTECTED), held)) {
				return blocked("That item is protected from dropping");
			}
			return false;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return false;
		}
	}

	private static boolean isProtected(List<String> items, ItemStack stack) {
		return !stack.isEmpty() && InventoryRules.protectedItem(items, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
	}

	/** Says why above the hotbar (at most once a second) and stops the action. */
	private static boolean blocked(String why) {
		long now = System.currentTimeMillis();
		if (now - blockedMessageAt > 1000) {
			blockedMessageAt = now;
			com.flintclient.client.compat.McCompat.hud().setOverlayMessage(Component.literal(why), false);
		}
		lastBlocked = why;
		return true;
	}

	private static String lastBlocked = "";

	/** What the last stopped action was ("" if none), for the dev tour. */
	public static String lastBlocked() {
		return lastBlocked;
	}

	// ---------- hotbar keys ----------

	/** After the hotbar is drawn: each slot's key, in the corner the module says. */
	public static void hotbarKeys(GuiGraphicsExtractor graphics) {
		if (!on()) {
			return;
		}
		try {
			ModuleState state = state();
			if (!state.bool(Inventory.KEYS)) {
				return;
			}
			Minecraft mc = Minecraft.getInstance();
			LocalPlayer player = mc.player;
			if (player == null || player.isSpectator()) {
				return;
			}
			int center = graphics.guiWidth() / 2;
			int top = graphics.guiHeight() - 19;
			int corner = state.option(Inventory.KEYS_POSITION);
			boolean small = state.bool(Inventory.KEYS_SMALL);
			boolean shadow = state.bool(Inventory.KEYS_SHADOW);
			boolean lower = state.bool(Inventory.KEYS_LOWERCASE);
			int color = 0xFF000000 | state.rgb(Inventory.KEYS_COLOR);
			int selected = player.getInventory().getSelectedSlot();
			for (int i = 0; i < 9; i++) {
				if (state.bool(Inventory.KEYS_HIDE_SELECTED) && i == selected
					|| state.bool(Inventory.KEYS_HIDE_EMPTY) && player.getInventory().getItem(i).isEmpty()) {
					continue;
				}
				String label = InventoryRules.keyLabel(mc.options.keyHotbarSlots[i].getTranslatedKeyMessage().getString(), lower);
				float scale = small ? 0.5F : 1F;
				int w = Math.round(mc.font.width(label) * scale);
				int h = Math.round(8 * scale);
				int sx = center - 90 + i * 20 + 2;
				int x = corner == 1 || corner == 3 ? sx + 16 - w : sx;
				int y = corner >= 2 ? top + 16 - h : top;
				graphics.pose().pushMatrix();
				graphics.pose().translate(x, y);
				graphics.pose().scale(scale, scale);
				graphics.nextStratum();
				graphics.text(mc.font, label, 0, 0, color, shadow);
				graphics.pose().popMatrix();
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	// ---------- don't reset cursor ----------

	/** A screen is closing (the pointer is about to be captured again): remember where it was. */
	public static void screenClosing(double x, double y) {
		keptX = x;
		keptY = y;
		keptAt = System.currentTimeMillis();
		// Minecraft has already let go of the screen here, so "was it a container" is whether one was drawn just now.
		keptFromContainer = keptAt - containerDrawnAt < 250;
	}

	private static long containerDrawnAt;

	/**
	 * Where to put the pointer as a screen opens: the remembered spot when the module keeps it and the last screen closed
	 * recently enough; else NaN (Minecraft's centre).
	 */
	public static boolean keepCursor(double[] out) {
		if (!on() || Double.isNaN(keptX)) {
			return false;
		}
		ModuleState state = state();
		if (!state.bool(Inventory.KEEP_CURSOR) || System.currentTimeMillis() - keptAt > state.integer(Inventory.CURSOR_MS)) {
			return false;
		}
		if (state.bool(Inventory.CURSOR_CONTAINERS)
			&& (!keptFromContainer || !(Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?>))) {
			return false;
		}
		out[0] = keptX;
		out[1] = keptY;
		return true;
	}

	/** A world was left: forget the search. */
	public static void worldChanged() {
		searched("");
		Features.nowMs();
	}
}
