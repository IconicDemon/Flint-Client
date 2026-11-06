package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.UtilityMods.ShulkerPreview;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import org.jspecify.annotations.Nullable;

/**
 * Shulker Preview, as Shulker Box Tooltip shows boxes (its behaviour, in Flint's own code and look): a box's items in its
 * tooltip, in a panel in the box's own colour.
 *
 * <ul>
 * <li>Full shows every slot in its place; Compact puts the same items together, most first, with counts like 1.2k.
 * Holding Alt shows the other one.</li>
 * <li>Always, or only while the preview key (Shift unless changed) is held, with a "Hold Left Shift to preview" line until
 * then; any size.</li>
 * <li>The ender chest shows your ender chest's items as you last saw them inside it (the game only sends them while it
 * is open), per world or server, until you leave the game.</li>
 * <li>Other items carrying contents (a chest or barrel picked up with its items) preview too, if wanted.</li>
 * </ul>
 * Render thread; fault-isolated.
 *
 * <p>The undyed shulker box's colour is Shulker Box Tooltip's (MisterPeModder, MIT; META-INF/licenses/MIT-ShulkerBoxTooltip.txt).
 */
public final class Mc263Containers {
	private static final int MODULE = Mods.index(ShulkerPreview.ID);
	private static final HookGuard GUARD = new HookGuard("shulker preview", (path, error) -> FlintClient.LOGGER.warn(
		"Flint: the {} hook failed; it's off for this session", path, error));
	/** An undyed shulker box's purple, the ender chest's teal, and a plain container's grey. */
	private static final int SHULKER = 0x976797;
	private static final int ENDER = 0x1B6B5E;
	private static final int PLAIN = 0x8B8B8B;
	/** Ender chest contents as last seen, per world or server. */
	private static final Map<String, List<ItemStack>> ENDER_SEEN = new HashMap<>();

	private Mc263Containers() {
	}

	/** A preview: the slots (empty ones included, in Full), the panel's colour and how many columns. */
	public record Preview(List<ItemStack> items, int rgb, int columns, boolean compact, float scale) implements TooltipComponent {
	}

	private static boolean alt() {
		return Mc263ControlPlatform.keyDown(InputConstants.KEY_LALT) || Mc263ControlPlatform.keyDown(InputConstants.KEY_RALT);
	}

	private static boolean shown(ModuleState state) {
		return !state.bool(ShulkerPreview.HOLD) || Mc263Features.keyHeld(state.text(ShulkerPreview.KEY));
	}

	/** The preview key's name as the Controls screen shows it ("Left Shift"). */
	private static String keyName(ModuleState state) {
		try {
			return InputConstants.getKey(state.text(ShulkerPreview.KEY)).getDisplayName().getString();
		} catch (RuntimeException | LinkageError e) {
			return "the preview key";
		}
	}

	/** The colour of a shulker box item's panel, or -1 when it isn't a shulker box. */
	private static int shulkerColor(ItemStack stack) {
		if (stack.getItem() instanceof BlockItem block && block.getBlock() instanceof ShulkerBoxBlock box) {
			DyeColor dye = box.getColor();
			return dye == null ? SHULKER : dye.getTextureDiffuseColor() & 0xFFFFFF;
		}
		return -1;
	}

	private static @Nullable String worldKey() {
		return com.flintclient.client.control.feature.Features.waypoints().worldKey();
	}

	/** An item's tooltip image: its contents' preview, or the image it had. */
	public static Optional<TooltipComponent> image(ItemStack stack, Optional<TooltipComponent> vanilla) {
		if (vanilla.isPresent() || !GUARD.on() || !Mods.on(MODULE)) {
			return vanilla;
		}
		try {
			ModuleState state = Mods.state(MODULE);
			if (!shown(state)) {
				return vanilla;
			}
			List<ItemStack> items;
			int rgb;
			if (stack.is(Items.ENDER_CHEST)) {
				String key = worldKey();
				items = key == null || !state.bool(ShulkerPreview.ENDER) ? null : ENDER_SEEN.get(key);
				rgb = ENDER;
			} else {
				ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
				int box = shulkerColor(stack);
				if (contents == null || box < 0 && !state.bool(ShulkerPreview.OTHERS)) {
					return vanilla;
				}
				items = com.flintclient.client.compat.McCompat.containerItems(contents).toList();
				rgb = box < 0 ? PLAIN : box;
			}
			if (items == null || items.stream().allMatch(ItemStack::isEmpty)) {
				return vanilla;
			}
			if (!state.bool(ShulkerPreview.COLORED)) {
				rgb = PLAIN;
			}
			boolean compact = state.option(ShulkerPreview.MODE) == 1 != alt();
			float scale = state.decimal(ShulkerPreview.SCALE);
			return Optional.of(compact ? compact(items, rgb, scale) : new Preview(items, rgb, 9, false, scale));
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return vanilla;
		}
	}

	/** The same items together (same item and components), most first. */
	private static Preview compact(List<ItemStack> items, int rgb, float scale) {
		List<ItemStack> merged = new ArrayList<>();
		for (ItemStack stack : items) {
			if (stack.isEmpty()) {
				continue;
			}
			ItemStack into = null;
			for (ItemStack m : merged) {
				if (ItemStack.isSameItemSameComponents(m, stack)) {
					into = m;
					break;
				}
			}
			if (into == null) {
				merged.add(stack.copyWithCount(stack.getCount()));
			} else {
				into.setCount(into.getCount() + stack.getCount());
			}
		}
		merged.sort(Comparator.comparingInt(ItemStack::getCount).reversed());
		return new Preview(merged, rgb, Math.min(9, Math.max(1, merged.size())), true, scale);
	}

	/** Lines after a container's details: the key hint while it waits for Shift, or how to fill the ender chest preview. */
	public static void lines(ItemStack stack, Consumer<Component> lines) {
		if (!GUARD.on() || !Mods.on(MODULE)) {
			return;
		}
		try {
			ModuleState state = Mods.state(MODULE);
			boolean ender = stack.is(Items.ENDER_CHEST);
			if (ender && state.bool(ShulkerPreview.ENDER)) {
				String key = worldKey();
				if (key == null || !ENDER_SEEN.containsKey(key)) {
					lines.accept(Component.literal("Open your ender chest once to preview it").withStyle(ChatFormatting.DARK_GRAY));
					return;
				}
			}
			boolean previewable = ender && state.bool(ShulkerPreview.ENDER) || shulkerColor(stack) >= 0
				|| state.bool(ShulkerPreview.OTHERS) && stack.get(DataComponents.CONTAINER) != null;
			if (previewable && state.bool(ShulkerPreview.HOLD) && state.bool(ShulkerPreview.HINT) && !shown(state) && hasItems(stack)) {
				lines.accept(Component.literal("Hold " + keyName(state) + " to preview").withStyle(ChatFormatting.DARK_GRAY));
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	private static boolean hasItems(ItemStack stack) {
		if (stack.is(Items.ENDER_CHEST)) {
			String key = worldKey();
			List<ItemStack> seen = key == null ? null : ENDER_SEEN.get(key);
			return seen != null && seen.stream().anyMatch(s -> !s.isEmpty());
		}
		ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
		return contents != null && contents.nonEmptyItemCopyStream().findAny().isPresent();
	}

	/** Whether Minecraft's text list of a container's items is left out (the panel shows them). */
	public static boolean hideList() {
		return GUARD.on() && Mods.on(MODULE) && Mods.state(MODULE).bool(ShulkerPreview.HIDE_LIST) && shown(Mods.state(MODULE));
	}

	/** From the client tick: while your ender chest is open, what's in it, for its preview. */
	public static void tick() {
		if (!GUARD.on() || !Mods.on(MODULE)) {
			return;
		}
		try {
			if (!(Minecraft.getInstance().gui.screen() instanceof ContainerScreen screen)) {
				return;
			}
			if (!(screen.getTitle().getContents() instanceof TranslatableContents title) || !title.getKey().equals("container.enderchest")) {
				return;
			}
			String key = worldKey();
			if (key == null) {
				return;
			}
			Container container = screen.getMenu().getContainer();
			List<ItemStack> items = new ArrayList<>(container.getContainerSize());
			for (int i = 0; i < container.getContainerSize(); i++) {
				items.add(container.getItem(i).copy());
			}
			ENDER_SEEN.put(key, items);
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** The client side of {@link Preview}, or null for any other tooltip image. */
	public static @Nullable ClientTooltipComponent clientTooltip(TooltipComponent component) {
		return component instanceof Preview preview ? new Panel(preview) : null;
	}

	/**
	 * The panel: in the box's colour (dark, so the items read), a lighter rim, and a well per slot, lit from the top left
	 * as Minecraft's slots are; Minecraft's own item rendering, with short counts for merged stacks.
	 */
	private static final class Panel implements ClientTooltipComponent {
		private static final int SLOT = 18;
		private static final int PAD = 4;
		private final Preview preview;
		private final int rows;

		Panel(Preview preview) {
			this.preview = preview;
			this.rows = Math.max(1, (preview.items().size() + preview.columns() - 1) / preview.columns());
		}

		@Override
		public int getHeight(Font font) {
			return Math.round((rows * SLOT + 2 * PAD + 2) * preview.scale());
		}

		@Override
		public int getWidth(Font font) {
			return Math.round(unscaledWidth() * preview.scale());
		}

		private int unscaledWidth() {
			return preview.columns() * SLOT + 2 * PAD;
		}

		private static int mix(int rgb, int with, float t) {
			int r = Math.round((rgb >> 16 & 255) + ((with >> 16 & 255) - (rgb >> 16 & 255)) * t);
			int g = Math.round((rgb >> 8 & 255) + ((with >> 8 & 255) - (rgb >> 8 & 255)) * t);
			int b = Math.round((rgb & 255) + ((with & 255) - (rgb & 255)) * t);
			return r << 16 | g << 8 | b;
		}

		@Override
		public void extractImage(Font font, int x, int y, int w, int h, GuiGraphicsExtractor graphics) {
			graphics.pose().pushMatrix();
			graphics.pose().translate(x, y);
			graphics.pose().scale(preview.scale(), preview.scale());
			panel(font, graphics);
			graphics.pose().popMatrix();
		}

		/** The panel at (0, 0), unscaled. */
		private void panel(Font font, GuiGraphicsExtractor graphics) {
			int x = 0;
			int rgb = preview.rgb();
			int pw = unscaledWidth();
			int ph = rows * SLOT + 2 * PAD;
			int top = 1;
			int fill = 0xF0000000 | mix(rgb, 0x000000, 0.62F);
			int rim = 0xFF000000 | mix(rgb, 0xFFFFFF, 0.12F);
			// A panel with its corners cut, so it reads rounded at this size.
			graphics.fill(x + 1, top, x + pw - 1, top + ph, fill);
			graphics.fill(x, top + 1, x + pw, top + ph - 1, fill);
			graphics.fill(x + 1, top, x + pw - 1, top + 1, rim);
			graphics.fill(x + 1, top + ph - 1, x + pw - 1, top + ph, rim);
			graphics.fill(x, top + 1, x + 1, top + ph - 1, rim);
			graphics.fill(x + pw - 1, top + 1, x + pw, top + ph - 1, rim);
			int well = 0xFF000000 | mix(rgb, 0x000000, 0.78F);
			int shade = 0xFF000000 | mix(rgb, 0x000000, 0.9F);
			int light = 0xFF000000 | mix(rgb, 0x000000, 0.45F);
			List<ItemStack> items = preview.items();
			int slots = preview.compact() ? items.size() : Math.max(items.size(), rows * preview.columns());
			for (int i = 0; i < slots; i++) {
				int sx = x + PAD + i % preview.columns() * SLOT;
				int sy = top + PAD + i / preview.columns() * SLOT;
				graphics.fill(sx, sy, sx + 17, sy + 17, well);
				graphics.fill(sx, sy, sx + 17, sy + 1, shade);
				graphics.fill(sx, sy, sx + 1, sy + 17, shade);
				graphics.fill(sx + 1, sy + 16, sx + 17, sy + 17, light);
				graphics.fill(sx + 16, sy + 1, sx + 17, sy + 17, light);
				ItemStack stack = i < items.size() ? items.get(i) : ItemStack.EMPTY;
				if (!stack.isEmpty()) {
					graphics.item(stack, sx + 1, sy + 1);
					graphics.itemDecorations(font, stack, sx + 1, sy + 1, count(stack.getCount()));
				}
			}
		}

		/** A stack's count as shown: none for one, the number up to 999, then 1.2k, 12k. */
		private static @Nullable String count(int n) {
			if (n <= 1) {
				return null;
			}
			if (n < 1000) {
				return Integer.toString(n);
			}
			return n < 10_000 ? String.format(java.util.Locale.ROOT, "%.1fk", n / 1000.0) : n / 1000 + "k";
		}
	}
}
