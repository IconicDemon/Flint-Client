package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.hud.render.HudLooks;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.MoreHudMods.ItemInfo;
import com.flintclient.client.control.model.mods.MoreHudMods.JumpReset;
import com.flintclient.client.mixin.feature.MultiPlayerGameModeAccessor;
import com.mojang.blaze3d.platform.NativeImage;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector2f;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 26.3's side of the second batch of HUD modules: the block and the mob under the crosshair (further than
 * reach, up to each module's range), jump reset timing (hits and jumps come from {@code LivingEntityMixin}), the item
 * just taken in hand, your turning for Mousestrokes, your own model, and your resource packs with their icons. The HUD
 * views read it on the render thread; what changes rarely (a block's name, an item's lines, the pack list) is rebuilt
 * only when it changes. Fault-isolated.
 */
public final class Mc263HudExtras {
	private static final HookGuard GUARD = new HookGuard("HUD extras",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final int JUMP = Mods.index(JumpReset.ID);
	private static final int INFO = Mods.index(ItemInfo.ID);

	private Mc263HudExtras() {
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	// ---------- the block under the crosshair ----------

	/** What Block Indicator shows; filled by {@link #block}. */
	public static final class BlockLook {
		public ItemStack icon = ItemStack.EMPTY;
		public String name = "";
		/** How far it's mined, 0 to 100, or -1 when it isn't being mined. */
		public int percent = -1;
		private @Nullable BlockState state;
	}

	private static final BlockLook BLOCK = new BlockLook();

	/** The block you look at within {@code range} blocks, or null (no block, no world). */
	public static @Nullable BlockLook block(int range) {
		if (!GUARD.on()) {
			return null;
		}
		try {
			Minecraft mc = mc();
			Entity camera = mc.getCameraEntity();
			if (mc.level == null || camera == null) {
				return null;
			}
			HitResult hit = mc.hitResult;
			double reach = range * (double) range;
			if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
				// Further than reach: pick again as far as the range (only blocks; looking at a mob shows nothing).
				hit = hit != null && hit.getType() == HitResult.Type.ENTITY ? null : camera.pick(range, 1.0F, false);
			}
			if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK
				|| camera.getEyePosition().distanceToSqr(hit.getLocation()) > reach) {
				return null;
			}
			BlockPos pos = blockHit.getBlockPos();
			BlockState state = mc.level.getBlockState(pos);
			if (state.isAir()) {
				return null;
			}
			if (state != BLOCK.state) {
				BLOCK.state = state;
				BLOCK.name = state.getBlock().getName().getString();
				ItemStack icon = state.getCloneItemStack(mc.level, pos, false);
				BLOCK.icon = icon == null ? ItemStack.EMPTY : icon;
			}
			BLOCK.percent = -1;
			MultiPlayerGameMode gameMode = mc.gameMode;
			if (gameMode != null && gameMode.isDestroying() && pos.equals(((MultiPlayerGameModeAccessor) gameMode).flint$destroyBlockPos())) {
				BLOCK.percent = Math.clamp(Math.round(((MultiPlayerGameModeAccessor) gameMode).flint$destroyProgress() * 100), 0, 100);
			}
			return BLOCK;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return null;
		}
	}

	// ---------- the mob under the crosshair ----------

	/** What Damage Indicator shows; filled by {@link #target}. */
	public static final class TargetLook {
		/** The mob's entity id: a new one snaps the health bar instead of easing it. */
		public int id;
		public String name = "";
		public float health;
		public float maxHealth;
		public float absorption;
		public int armor;
		private @Nullable WeakReference<LivingEntity> entity;
	}

	private static final TargetLook TARGET = new TargetLook();

	/** The living thing you look at within {@code range} blocks (not through walls), or null. */
	public static @Nullable TargetLook target(int range) {
		if (!GUARD.on()) {
			return null;
		}
		try {
			Minecraft mc = mc();
			Entity camera = mc.getCameraEntity();
			LocalPlayer player = mc.player;
			if (mc.level == null || camera == null || player == null) {
				return null;
			}
			LivingEntity found = null;
			if (mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof LivingEntity living) {
				found = living;
			} else {
				Vec3 eye = camera.getEyePosition(1.0F);
				Vec3 look = camera.getViewVector(1.0F);
				Vec3 end = eye.add(look.scale(range));
				AABB box = camera.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0);
				EntityHitResult hit = ProjectileUtil.getEntityHitResult(camera, eye, end, box,
					e -> e instanceof LivingEntity && e.isPickable() && !e.isSpectator() && !e.isInvisibleTo(player), range * (double) range);
				if (hit != null) {
					HitResult wall = camera.pick(range, 1.0F, false);
					if (wall.getType() != HitResult.Type.BLOCK || eye.distanceToSqr(wall.getLocation()) > eye.distanceToSqr(hit.getLocation())) {
						found = (LivingEntity) hit.getEntity();
					}
				}
			}
			if (found == null || !found.isAlive()) {
				return null;
			}
			LivingEntity last = TARGET.entity == null ? null : TARGET.entity.get();
			if (last != found) {
				TARGET.entity = new WeakReference<>(found);
			}
			TARGET.id = found.getId();
			TARGET.name = found.getDisplayName().getString();
			TARGET.health = found.getHealth();
			TARGET.maxHealth = Math.max(1, found.getMaxHealth());
			TARGET.absorption = found.getAbsorptionAmount();
			TARGET.armor = found.getArmorValue();
			return TARGET;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return null;
		}
	}

	// ---------- jump reset ----------

	private static int hurtTick = Integer.MIN_VALUE / 2;
	private static int jumpTick = Integer.MIN_VALUE / 2;
	private static boolean judged = true;
	private static int result;
	private static long resultAt;

	/** You were hit (the damage event reached the client): from the next tick a jump counts against this hit. */
	public static void hurt(LivingEntity entity) {
		if (!GUARD.on() || !Mods.on(JUMP) || entity != mc().player) {
			return;
		}
		hurtTick = entity.tickCount + 1;
		judged = false;
		// A jump before the hit (early) is judged now; a later one when it happens.
		if (hurtTick - jumpTick <= Mods.state(JUMP).integer(JumpReset.MAX_TICKS) && jumpTick <= hurtTick) {
			judge(jumpTick - hurtTick);
		}
	}

	/** You jumped. */
	public static void jumped(LivingEntity entity) {
		if (!GUARD.on() || !Mods.on(JUMP) || entity != mc().player) {
			return;
		}
		jumpTick = entity.tickCount;
		if (!judged && jumpTick - hurtTick >= 0 && jumpTick - hurtTick <= Mods.state(JUMP).integer(JumpReset.MAX_TICKS)) {
			judge(jumpTick - hurtTick);
		}
	}

	private static void judge(int ticks) {
		judged = true;
		result = ticks;
		resultAt = System.nanoTime();
	}

	/** The last jump reset: ticks from the hit (0 perfect, negative early, positive late). */
	public static int jumpResult() {
		return result;
	}

	/** When the last jump reset was judged ({@link System#nanoTime()}), or 0 when there was none. */
	public static long jumpResultAt() {
		return resultAt;
	}

	// ---------- the item in hand ----------

	/** The item just taken in hand, as Item Info lists it. */
	public static final class HeldLook {
		public ItemStack stack = ItemStack.EMPTY;
		public final List<String> lines = new ArrayList<>();
		/** Per line: its colour (0xRRGGBB). */
		public final List<Integer> colors = new ArrayList<>();
		/** The stack's count after the name ("  x16"), empty when not shown, and its own colour (Count color). */
		public String count = "";
		public int countColor;
		/** When it was taken in hand ({@link System#nanoTime()}). */
		public long since;
		/** Bumped whenever the lines change. */
		public int revision;
	}

	private static final HeldLook HELD = new HeldLook();
	private static int heldSlot = -1;
	private static int heldRevision = -1;

	/** Each client tick: notices the item in hand changing, and rebuilds Item Info's lines when it did. */
	public static void tick() {
		if (!GUARD.on()) {
			return;
		}
		try {
			LocalPlayer player = mc().player;
			if (player == null || !Mods.on(INFO)) {
				heldSlot = -1;
				HELD.stack = ItemStack.EMPTY;
				return;
			}
			ItemStack now = player.getMainHandItem();
			int slot = player.getInventory().getSelectedSlot();
			ModuleState s = Mods.state(INFO);
			boolean changed = slot != heldSlot || !ItemStack.isSameItemSameComponents(now, HELD.stack);
			boolean recount = !changed && now.getCount() != HELD.stack.getCount();
			if (changed || recount || s.revision() != heldRevision) {
				if (changed) {
					HELD.since = System.nanoTime();
					alert(now, s);
				}
				heldSlot = slot;
				heldRevision = s.revision();
				HELD.stack = now.copy();
				describe(HELD, s);
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** What Item Info shows now, or null when there's nothing in hand. */
	public static @Nullable HeldLook held() {
		return HELD.stack.isEmpty() ? null : HELD;
	}

	/** Whether Item Info takes the place of Minecraft's item name above the hotbar. */
	public static boolean replacesItemName() {
		return GUARD.on() && Mods.on(INFO);
	}

	private static void describe(HeldLook out, ModuleState s) {
		ItemStack stack = out.stack;
		out.lines.clear();
		out.colors.clear();
		out.count = "";
		out.revision++;
		if (stack.isEmpty()) {
			return;
		}
		Component hover = stack.getHoverName();
		TextColor own = hover.getStyle().getColor();
		TextColor rarity = TextColor.fromLegacyFormat(stack.getRarity().color());
		int nameColor = own != null ? own.getValue() : rarity != null ? rarity.getValue() : 0xFFFFFF;
		String name = hover.getString();
		if (s.bool(ItemInfo.COUNT) && stack.getCount() >= s.integer(ItemInfo.MIN_COUNT)) {
			out.count = (s.bool(ItemInfo.NO_PREFIX) ? "  " : "  x") + stack.getCount();
			out.countColor = s.rgb(ItemInfo.COUNT_COLOR) & 0xFFFFFF;
		}
		add(out, name, nameColor);
		if (s.bool(ItemInfo.ENCHANTS)) {
			ItemEnchantments enchantments = EnchantmentHelper.getEnchantmentsForCrafting(stack);
			int shown = 0;
			for (var entry : enchantments.entrySet()) {
				if (shown == 6) {
					add(out, "+" + (enchantments.size() - shown) + " more", s.rgb(ItemInfo.ENCHANTS_COLOR));
					break;
				}
				Holder<Enchantment> holder = entry.getKey();
				add(out, Enchantment.getFullname(holder, entry.getIntValue()).getString(), s.rgb(ItemInfo.ENCHANTS_COLOR));
				shown++;
			}
		}
		if (s.bool(ItemInfo.DURABILITY) && stack.isDamageableItem()) {
			int max = stack.getMaxDamage();
			int left = max - stack.getDamageValue();
			boolean low = s.bool(ItemInfo.ALERT) && left * 100 < max * s.integer(ItemInfo.ALERT_AT);
			add(out, left + " / " + max, low ? s.rgb(ItemInfo.ALERT_COLOR) : s.rgb(ItemInfo.DURABILITY_COLOR));
		}
		if (s.bool(ItemInfo.CONTAINER)) {
			int items = 0;
			ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
			if (contents != null) {
				items = contents.nonEmptyItemCopyStream().mapToInt(ItemStack::getCount).sum();
			}
			BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
			if (bundle != null) {
				items += com.flintclient.client.compat.McCompat.bundleItems(bundle).mapToInt(ItemStack::getCount).sum();
			}
			if (contents != null || bundle != null) {
				add(out, items == 0 ? "Empty" : items == 1 ? "1 item inside" : items + " items inside", s.rgb(ItemInfo.CONTAINER_COLOR));
			}
		}
		if (s.bool(ItemInfo.LORE)) {
			ItemLore lore = stack.get(DataComponents.LORE);
			if (lore != null) {
				int n = 0;
				for (Component line : lore.lines()) {
					if (n++ == 4) {
						break;
					}
					add(out, line.getString(), s.rgb(ItemInfo.LORE_COLOR));
				}
			}
		}
	}

	private static void add(HeldLook out, String line, int rgb) {
		out.lines.add(line);
		out.colors.add(rgb & 0xFFFFFF);
	}

	/** A soft ping when a nearly broken item is taken in hand. */
	private static void alert(ItemStack stack, ModuleState s) {
		if (!s.bool(ItemInfo.DURABILITY) || !s.bool(ItemInfo.ALERT) || !s.bool(ItemInfo.ALERT_SOUND) || !stack.isDamageableItem()) {
			return;
		}
		int max = stack.getMaxDamage();
		if ((max - stack.getDamageValue()) * 100 < max * s.integer(ItemInfo.ALERT_AT)) {
			mc().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 1.6F, s.integer(ItemInfo.ALERT_VOLUME) / 100.0F));
		}
	}

	// ---------- effects ----------

	private static final int EFFECTS = Mods.index(com.flintclient.client.control.model.ModuleId.EFFECTS);

	/** Whether Minecraft's effect list beside the inventory is hidden (Effects on, with Hide inventory list). */
	public static boolean hideInventoryEffects() {
		return GUARD.on() && Mods.on(EFFECTS) && Mods.state(EFFECTS).bool(com.flintclient.client.control.model.ModuleSettings.Effects.HIDE_INVENTORY);
	}

	// ---------- turning, for Mousestrokes ----------

	/** Your yaw in degrees as of this frame (the mouse turns you every frame), or NaN with no player. */
	public static float yaw() {
		LocalPlayer player = mc().player;
		return player == null ? Float.NaN : player.getYRot();
	}

	/** Your pitch in degrees as of this frame, or NaN with no player. */
	public static float pitch() {
		LocalPlayer player = mc().player;
		return player == null ? Float.NaN : player.getXRot();
	}

	// ---------- your own model ----------

	private static final Vector2f CORNER = new Vector2f();

	/**
	 * Draws you, as the inventory does, in the canvas rectangle {@code x, y, w, h} (canvas pixels: the current pose maps them
	 * to the GUI), turned a little to one side; the head follows your pitch when {@code head}. Returns false with no player.
	 */
	public static boolean drawPlayer(GuiGraphicsExtractor graphics, int x, int y, int w, int h, boolean head) {
		if (!GUARD.on()) {
			return false;
		}
		try {
			LocalPlayer player = mc().player;
			if (player == null) {
				return false;
			}
			// Minecraft's picture-in-picture renders at GUI coordinates whatever the pose: map the corners through it.
			graphics.pose().transformPosition(x, y, CORNER);
			int x0 = Math.round(CORNER.x);
			int y0 = Math.round(CORNER.y);
			graphics.pose().transformPosition(x + w, y + h, CORNER);
			int x1 = Math.round(CORNER.x);
			int y1 = Math.round(CORNER.y);
			if (x1 - x0 < 4 || y1 - y0 < 8) {
				return true;
			}
			int size = Math.max(4, Math.round((y1 - y0) * 30 / 70.0F));
			float cx = (x0 + x1) / 2.0F;
			float cy = (y0 + y1) / 2.0F;
			// The inventory turns the model towards a pointer: one a little to the left turns it a quarter towards you. A
			// pointer above or below nods the head by atan(dy / 40) × 20 degrees (and tilts the model a little with it), so
			// the head follows a gentle share of your pitch.
			float pitch = head ? Math.clamp(player.getXRot(), -90, 90) * 0.16F : 0;
			float mouseY = cy + 40 * (float) Math.tan(pitch / 20.0F);
			InventoryScreen.extractEntityInInventoryFollowsMouse(graphics, x0, y0, x1, y1, size, 0.0625F, cx - 26, mouseY, player);
			return true;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return false;
		}
	}

	// ---------- resource packs ----------

	private static final Map<String, Identifier> ICONS = new HashMap<>();
	private static List<HudLooks.Pack> packs = List.of();
	private static List<String> packIds = List.of();
	private static long packsAt;

	/**
	 * The packs you turned on yourself (yours, and optional built-in ones such as Programmer Art), top of the list first as the
	 * Resource Packs screen orders them; rechecked each second.
	 */
	public static List<HudLooks.Pack> packs() {
		if (!GUARD.on()) {
			return List.of();
		}
		long now = System.nanoTime();
		if (now - packsAt < 1_000_000_000L) {
			return packs;
		}
		packsAt = now;
		try {
			List<String> ids = new ArrayList<>(mc().getResourcePackRepository().getSelectedIds());
			if (ids.equals(packIds)) {
				return packs;
			}
			packIds = ids;
			List<HudLooks.Pack> out = new ArrayList<>();
			for (Pack pack : mc().getResourcePackRepository().getSelectedPacks()) {
				// Minecraft's own and mods' resources are fixed in place; optional built-in packs (Programmer Art) are yours to pick.
				if (pack.isRequired() || pack.isFixedPosition() || pack.getPackSource() == PackSource.FEATURE) {
					continue;
				}
				String description = pack.getDescription().getString();
				int line = description.indexOf('\n');
				out.add(new HudLooks.Pack(pack.getId(), pack.getTitle().getString(), line < 0 ? description : description.substring(0, line), icon(pack)));
			}
			// The repository lists the bottom pack first; the screen shows the top one first.
			java.util.Collections.reverse(out);
			packs = List.copyOf(out);
			return packs;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return List.of();
		}
	}

	private static HudLooks.@Nullable Pack topPack;
	private static List<String> topPackIds = List.of();
	private static long topPackAt;

	/**
	 * Pack Displayer's pack: the top selected pack from your resource packs folder (not Minecraft's built-in packs, a feature's
	 * or a world's or server's), or null; rechecked each second.
	 */
	public static HudLooks.@Nullable Pack topPack() {
		if (!GUARD.on()) {
			return null;
		}
		long now = System.nanoTime();
		if (now - topPackAt < 1_000_000_000L) {
			return topPack;
		}
		topPackAt = now;
		try {
			List<String> ids = new ArrayList<>(mc().getResourcePackRepository().getSelectedIds());
			if (ids.equals(topPackIds)) {
				return topPack;
			}
			topPackIds = ids;
			topPack = null;
			List<Pack> selected = new ArrayList<>(mc().getResourcePackRepository().getSelectedPacks());
			for (int i = selected.size() - 1; i >= 0; i--) {
				Pack pack = selected.get(i);
				if (pack.getPackSource() == PackSource.DEFAULT) {
					topPack = new HudLooks.Pack(pack.getId(), pack.getTitle().getString(), "", icon(pack));
					break;
				}
			}
			return topPack;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return null;
		}
	}

	/** The pack's pack.png as a texture, made the first time (as the Resource Packs screen does), or null when it has none. */
	private static @Nullable Identifier icon(Pack pack) {
		Identifier known = ICONS.get(pack.getId());
		if (known != null || ICONS.containsKey(pack.getId())) {
			return known;
		}
		Identifier id = null;
		try {
			NativeImage image = com.flintclient.client.compat.McCompat.packIcon(pack);
			if (image != null) {
				String path = "pack_icon/" + Integer.toHexString(pack.getId().hashCode()) + "_" + ICONS.size();
				Identifier made = Identifier.fromNamespaceAndPath("flintclient", path);
				mc().getTextureManager().register(made, new DynamicTexture(made::toString, image));
				id = made;
			}
		} catch (Exception e) {
			FlintClient.LOGGER.debug("Flint: no icon for pack {}", pack.getId(), e);
		}
		ICONS.put(pack.getId(), id);
		return id;
	}
}
