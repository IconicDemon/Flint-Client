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
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 26.3's side of the Inventory module: the search box and highlights in container screens, slot locks, drop
 * protection, the hotbar key labels and keeping the pointer between screens. Every guard only stops the player's own
 * clicks and keys on this side; nothing is ever moved or sent for them. Render thread; fault-isolated (a failure leaves the
 * screens vanilla).
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
		} catch (RuntimeException e) {
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

	/** After the slots are drawn (the pose is at the container's corner): search highlights and lock marks. */
	public static void decorateSlots(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
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
			for (Slot slot : screen.getMenu().slots) {
				if (!slot.isActive()) {
					continue;
				}
				if (search) {
					ItemStack stack = slot.getItem();
					boolean match = !stack.isEmpty() && matches(stack, lore);
					if (dim && !match) {
						graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, DIM);
					} else if (!dim && match) {
						graphics.outline(slot.x - 1, slot.y - 1, 18, 18, MATCH);
					}
				}
				if (!locked.isEmpty() && slot.container instanceof net.minecraft.world.entity.player.Inventory
					&& InventoryRules.locked(locked, slot.getContainerSlot())) {
					graphics.outline(slot.x - 1, slot.y - 1, 18, 18, (lockColor & 0xFFFFFF) | 0x90000000);
					graphics.fill(slot.x + 11, slot.y, slot.x + 16, slot.y + 5, lockColor);
					graphics.fill(slot.x + 12, slot.y + 1, slot.x + 15, slot.y + 4, 0xFF202020);
				}
			}
		} catch (RuntimeException e) {
			GUARD.fail(e);
		}
	}

	/**
	 * A key in a container screen: the search box takes every key while it has the cursor (Esc or Enter gives the cursor
	 * back); the search key puts it there; the lock key locks or unlocks the slot under the pointer. True: used up.
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
			return false;
		} catch (RuntimeException e) {
			GUARD.fail(e);
			return false;
		}
	}

	/** A click in a container screen: whether to stop it (a locked slot, or throwing a protected item away). */
	public static boolean blockClick(AbstractContainerScreen<?> screen, @Nullable Slot slot, int slotId, int button, ContainerInput input) {
		if (!on() || input == ContainerInput.CLONE) {
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
		} catch (RuntimeException e) {
			GUARD.fail(e);
			return false;
		}
	}

	/** The drop key in the world: whether to stop it (the held item is protected, or its hotbar slot is locked). */
	public static boolean blockDrop(LocalPlayer player) {
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
		} catch (RuntimeException e) {
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
			Minecraft.getInstance().gui.hud.setOverlayMessage(Component.literal(why), false);
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
		} catch (RuntimeException e) {
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
