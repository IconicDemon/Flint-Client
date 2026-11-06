package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.LookMods.ArmorBar;
import com.flintclient.client.control.model.mods.LookMods.Hearts;
import com.flintclient.client.render.Palette;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * Minecraft 26.3's armor and health bars for Armor Bar and Hearts, drawn in place of Minecraft's own (the HUD mixin skips
 * those when these return true), at exactly the places Minecraft would draw them.
 *
 * <p>Armor Bar colours each armor icon by the piece its points come from (helmet's first, then chestplate, leggings and
 * boots): its material, red when it's nearly broken, a shimmer for a protection enchantment, a dot for Mending and Thorns.
 * Hearts draws one row: health past 20 colours the same ten hearts again, layer over layer; absorption lies over them in
 * gold (or in its own row). Hearts in a colour of their own are Minecraft's heart picture turned to grey and tinted, with
 * its white shine drawn on top. Fault-isolated; allocation-free per frame once the heart pictures are made.
 */
public final class Mc263Bars {
	private static final HookGuard GUARD = new HookGuard("armor and hearts",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final int ARMOR = Mods.index(ArmorBar.ID);
	private static final int HEARTS = Mods.index(Hearts.ID);
	private static final Identifier ARMOR_FULL = Identifier.withDefaultNamespace("hud/armor_full");
	private static final Identifier ARMOR_HALF = Identifier.withDefaultNamespace("hud/armor_half");
	private static final Identifier ARMOR_EMPTY = Identifier.withDefaultNamespace("hud/armor_empty");
	private static final Identifier CONTAINER = Identifier.withDefaultNamespace("hud/heart/container");
	private static final Identifier CONTAINER_BLINKING = Identifier.withDefaultNamespace("hud/heart/container_blinking");
	private static final Identifier FULL = Identifier.withDefaultNamespace("hud/heart/full");
	private static final Identifier HALF = Identifier.withDefaultNamespace("hud/heart/half");
	private static final Identifier FULL_BLINKING = Identifier.withDefaultNamespace("hud/heart/full_blinking");
	private static final Identifier HALF_BLINKING = Identifier.withDefaultNamespace("hud/heart/half_blinking");
	private static final Identifier ABSORBING_FULL = Identifier.withDefaultNamespace("hud/heart/absorbing_full");
	private static final Identifier ABSORBING_HALF = Identifier.withDefaultNamespace("hud/heart/absorbing_half");
	private static final EquipmentSlot[] PIECES = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
	private static final ResourceKey<?>[] PROTECTIONS = {Enchantments.PROTECTION, Enchantments.FIRE_PROTECTION, Enchantments.BLAST_PROTECTION,
		Enchantments.PROJECTILE_PROTECTION};

	private Mc263Bars() {
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	// ---------- armor ----------

	/** Per armor point (up to 30): which piece it comes from (0 helmet to 3 boots), or -1. Read each tick. */
	private static final int[] OWNER = new int[30];
	/** Per piece: its tint, its protection colour (0: none), and its marks. */
	private static final int[] TINT = new int[4];
	private static final int[] SHIMMER = new int[4];
	private static final boolean[] MENDING = new boolean[4];
	private static final boolean[] THORNS = new boolean[4];
	private static double counted;

	/** Every client tick: which piece each armor point comes from, and how each piece looks. */
	public static void tick() {
		if (!GUARD.on() || !Mods.on(ARMOR)) {
			return;
		}
		try {
			Player player = mc().player;
			java.util.Arrays.fill(OWNER, -1);
			if (player == null) {
				return;
			}
			ModuleState s = Mods.state(ARMOR);
			int at = 0;
			for (int piece = 0; piece < PIECES.length; piece++) {
				ItemStack stack = player.getItemBySlot(PIECES[piece]);
				counted = 0;
				if (!stack.isEmpty()) {
					stack.forEachModifier(PIECES[piece], Mc263Bars::countArmor);
				}
				int points = (int) Math.round(counted);
				for (int k = 0; k < points && at < OWNER.length; k++) {
					OWNER[at++] = piece;
				}
				TINT[piece] = tint(stack, s);
				SHIMMER[piece] = 0;
				MENDING[piece] = false;
				THORNS[piece] = false;
				for (Holder<Enchantment> enchantment : stack.getEnchantments().keySet()) {
					for (int p = 0; p < PROTECTIONS.length; p++) {
						if (enchantment.is(key(PROTECTIONS[p]))) {
							SHIMMER[piece] = 0xFF000000 | s.rgb(p == 0 ? ArmorBar.ALL_PROTECTION : p == 1 ? ArmorBar.FIRE_PROTECTION
								: p == 2 ? ArmorBar.BLAST_PROTECTION : ArmorBar.PROJECTILE_PROTECTION);
						}
					}
					MENDING[piece] |= enchantment.is(Enchantments.MENDING);
					THORNS[piece] |= enchantment.is(Enchantments.THORNS);
				}
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	@SuppressWarnings("unchecked")
	private static ResourceKey<Enchantment> key(ResourceKey<?> key) {
		return (ResourceKey<Enchantment>) key;
	}

	private static void countArmor(Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, AttributeModifier modifier) {
		if (attribute.is(Attributes.ARMOR) && modifier.operation() == AttributeModifier.Operation.ADD_VALUE) {
			counted += modifier.amount();
		}
	}

	/** A piece's icon colour: red when nearly broken (if wanted), its material's, or Minecraft's own (white). */
	private static int tint(ItemStack stack, ModuleState s) {
		if (stack.isEmpty()) {
			return 0xFFFFFFFF;
		}
		if (s.bool(ArmorBar.LOW) && stack.isDamageableItem()) {
			int max = stack.getMaxDamage();
			if ((max - stack.getDamageValue()) * 100 < max * s.integer(ArmorBar.LOW_AT)) {
				return 0xFF000000 | s.rgb(ArmorBar.LOW_COLOR);
			}
		}
		if (!s.bool(ArmorBar.MATERIAL)) {
			return 0xFFFFFFFF;
		}
		String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		if (path.startsWith("leather_")) {
			return 0xFF000000 | s.rgb(ArmorBar.LEATHER);
		}
		if (path.startsWith("golden_")) {
			return 0xFF000000 | s.rgb(ArmorBar.GOLD);
		}
		if (path.startsWith("diamond_")) {
			return 0xFF000000 | s.rgb(ArmorBar.DIAMOND);
		}
		if (path.startsWith("netherite_")) {
			return 0xFF000000 | s.rgb(ArmorBar.NETHERITE);
		}
		return 0xFFFFFFFF;
	}

	/**
	 * Draws the armor row where Minecraft would ({@code y} is the bottom line of the health rows, {@code rows} and
	 * {@code rowHeight} their count and spacing, {@code x} the left edge); returns false to leave it to Minecraft.
	 */
	public static boolean drawArmor(GuiGraphicsExtractor g, Player player, int y, int rows, int rowHeight, int x) {
		if (!GUARD.on() || !Mods.on(ARMOR)) {
			return false;
		}
		try {
			int armor = player.getArmorValue();
			if (armor <= 0) {
				return true;
			}
			ModuleState s = Mods.state(ARMOR);
			int top = y - (rows - 1) * rowHeight - 10;
			long now = net.minecraft.util.Util.getMillis();
			for (int i = 0; i < 10; i++) {
				int left = x + i * 8;
				Identifier sprite = i * 2 + 1 < armor ? ARMOR_FULL : i * 2 + 1 == armor ? ARMOR_HALF : ARMOR_EMPTY;
				int piece = i * 2 < armor && i * 2 < OWNER.length ? OWNER[i * 2] : -1;
				g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, left, top, 9, 9, piece < 0 ? 0xFFFFFFFF : TINT[piece]);
				if (piece < 0 || sprite == ARMOR_EMPTY) {
					continue;
				}
				if (s.bool(ArmorBar.PROTECTION) && SHIMMER[piece] != 0) {
					// A slow wave of colour along the bar.
					float wave = 0.5F + 0.5F * (float) Math.sin(now / 420.0 - i * 0.7);
					g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, left, top, 9, 9, Palette.withAlpha(SHIMMER[piece], 0.18F + 0.32F * wave));
				}
				if (s.bool(ArmorBar.MENDING) && MENDING[piece]) {
					g.fill(left + 6, top + 1, left + 8, top + 3, 0xFF000000 | s.rgb(ArmorBar.MENDING_COLOR));
				}
				if (s.bool(ArmorBar.THORNS) && THORNS[piece]) {
					g.fill(left + 6, top + 6, left + 8, top + 8, 0xFF000000 | s.rgb(ArmorBar.THORNS_COLOR));
				}
			}
			return true;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return false;
		}
	}

	// ---------- hearts ----------

	/** Whether Hearts draws your health now: on, not hardcore, and (with Effect colors) not poisoned, withered or frozen. */
	public static boolean ownHearts(Player player) {
		if (!GUARD.on() || !Mods.on(HEARTS)) {
			return false;
		}
		try {
			if (mc().level != null && mc().level.getLevelData().isHardcore()) {
				return false;
			}
			if (Mods.state(HEARTS).bool(Hearts.EFFECT_COLORS)
				&& (player.hasEffect(MobEffects.POISON) || player.hasEffect(MobEffects.WITHER) || player.isFullyFrozen())) {
				return false;
			}
			return true;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return false;
		}
	}

	/** How many rows of hearts Minecraft makes room for: one (two with absorption in a row of its own) while Hearts draws them. */
	public static int heartRows(int original) {
		Player player = mc().player;
		if (player == null || !ownHearts(player)) {
			return original;
		}
		boolean ownRow = !Mods.state(HEARTS).bool(Hearts.SAME_ROW_ABSORPTION) && player.getAbsorptionAmount() > 0;
		return ownRow ? 2 : 1;
	}

	/**
	 * Draws your hearts where Minecraft would (its arguments: the row's left edge and bottom line, the row spacing, the heart
	 * raised by regeneration, and health, health before the last hit, and absorption in half hearts); false leaves it to
	 * Minecraft.
	 */
	public static boolean drawHearts(GuiGraphicsExtractor g, Player player, int x, int y, int rowHeight, int regen, float maxHealth, int health,
		int before, int absorption, boolean blinking) {
		if (!ownHearts(player)) {
			return false;
		}
		try {
			ModuleState s = Mods.state(HEARTS);
			pictures();
			int count = Math.clamp((int) Math.ceil(maxHealth / 2.0F), 1, 10);
			boolean low = health + absorption <= 4;
			boolean sameRow = s.bool(Hearts.SAME_ROW_ABSORPTION);
			int tick = player.tickCount;
			for (int i = 0; i < count; i++) {
				int left = x + i * 8;
				int top = y + jiggle(low, tick, i) - (i == regen ? 2 : 0);
				g.blitSprite(RenderPipelines.GUI_TEXTURED, blinking ? CONTAINER_BLINKING : CONTAINER, left, top, 9, 9);
				if (blinking && before > health) {
					// The health just lost, flashing white behind what is left.
					if (i * 2 + 1 < before) {
						g.blitSprite(RenderPipelines.GUI_TEXTURED, FULL_BLINKING, left, top, 9, 9);
					} else if (i * 2 + 1 == before) {
						g.blitSprite(RenderPipelines.GUI_TEXTURED, HALF_BLINKING, left, top, 9, 9);
					}
				}
				health(g, s, left, top, i, health);
				if (sameRow && absorption > 0) {
					absorption(g, left, top, i, absorption);
				}
			}
			if (!sameRow && absorption > 0) {
				int row = y - rowHeight;
				int hearts = Math.min(10, (absorption + 1) / 2);
				for (int i = 0; i < hearts; i++) {
					int left = x + i * 8;
					int top = row + jiggle(low, tick, i + 10);
					g.blitSprite(RenderPipelines.GUI_TEXTURED, CONTAINER, left, top, 9, 9);
					absorption(g, left, top, i, absorption);
				}
			}
			return true;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return false;
		}
	}

	/** At low health each heart shakes a pixel, a little differently each tick (as Minecraft's do). */
	private static int jiggle(boolean low, int tick, int i) {
		if (!low) {
			return 0;
		}
		int h = (tick * 312871 + i * 4231) * 0x9E3779B1;
		return (h >>> 31) == 0 ? 0 : 1;
	}

	/** Heart {@code i} of the row for {@code health} half hearts: the top layer full, or a half over the layer below. */
	private static void health(GuiGraphicsExtractor g, ModuleState s, int x, int y, int i, int health) {
		int layers = health / 20;
		int rest = health % 20;
		if (i * 2 + 2 <= rest) {
			heart(g, s, x, y, layers, false);
		} else if (i * 2 + 1 == rest) {
			if (layers > 0) {
				heart(g, s, x, y, layers - 1, false);
			}
			heart(g, s, x, y, layers, true);
		} else if (layers > 0) {
			heart(g, s, x, y, layers - 1, false);
		}
	}

	private static void absorption(GuiGraphicsExtractor g, int x, int y, int i, int absorption) {
		int shown = Math.min(absorption, 20);
		if (i * 2 + 2 <= shown) {
			g.blitSprite(RenderPipelines.GUI_TEXTURED, ABSORBING_FULL, x, y, 9, 9);
		} else if (i * 2 + 1 == shown) {
			g.blitSprite(RenderPipelines.GUI_TEXTURED, ABSORBING_HALF, x, y, 9, 9);
		}
	}

	/** One heart of {@code layer} (0: your first 20 health), full or half. */
	private static void heart(GuiGraphicsExtractor g, ModuleState s, int x, int y, int layer, boolean half) {
		if (layer == 0 && !s.bool(Hearts.OVERRIDE)) {
			g.blitSprite(RenderPipelines.GUI_TEXTURED, half ? HALF : FULL, x, y, 9, 9);
			return;
		}
		int rgb = layer == 0 ? s.rgb(Hearts.OVERRIDE_COLOR) : s.rgb(Hearts.LAYERS[Math.min(layer - 1, Hearts.LAYERS.length - 1)]);
		if (!picturesMade) {
			g.blitSprite(RenderPipelines.GUI_TEXTURED, half ? HALF : FULL, x, y, 9, 9, 0xFF000000 | rgb);
			return;
		}
		g.blit(RenderPipelines.GUI_TEXTURED, half ? HALF_GREY : FULL_GREY, x, y, 0, 0, 9, 9, 9, 9, 0xFF000000 | rgb);
		if (s.bool(Hearts.SHINE)) {
			g.blit(RenderPipelines.GUI_TEXTURED, half ? HALF_SHINE : FULL_SHINE, x, y, 0, 0, 9, 9, 9, 9, 0xFFFFFFFF);
		}
	}

	private static final Identifier FULL_GREY = Identifier.fromNamespaceAndPath("flintclient", "hearts/full_grey");
	private static final Identifier HALF_GREY = Identifier.fromNamespaceAndPath("flintclient", "hearts/half_grey");
	private static final Identifier FULL_SHINE = Identifier.fromNamespaceAndPath("flintclient", "hearts/full_shine");
	private static final Identifier HALF_SHINE = Identifier.fromNamespaceAndPath("flintclient", "hearts/half_shine");
	private static boolean picturesTried;
	private static boolean picturesMade;

	/** Minecraft's full and half heart pictures (from the resource packs), as grey to tint and their white shine, made once. */
	private static void pictures() {
		if (picturesTried) {
			return;
		}
		picturesTried = true;
		picturesMade = picture("textures/gui/sprites/hud/heart/full.png", FULL_GREY, FULL_SHINE)
			&& picture("textures/gui/sprites/hud/heart/half.png", HALF_GREY, HALF_SHINE);
	}

	private static boolean picture(String path, Identifier grey, Identifier shine) {
		try {
			Optional<Resource> resource = mc().getResourceManager().getResource(Identifier.withDefaultNamespace(path));
			if (resource.isEmpty()) {
				return false;
			}
			try (InputStream in = resource.get().open(); NativeImage source = NativeImage.read(in)) {
				int w = source.getWidth();
				int h = source.getHeight();
				NativeImage fill = new NativeImage(w, h, false);
				NativeImage light = new NativeImage(w, h, false);
				for (int py = 0; py < h; py++) {
					for (int px = 0; px < w; px++) {
						int argb = source.getPixel(px, py);
						int a = argb >>> 24;
						int r = argb >> 16 & 255;
						int gg = argb >> 8 & 255;
						int b = argb & 255;
						boolean highlight = a > 0 && gg > 128 && b > 128;
						// The heart's red as brightness (its shading kept); the shine becomes plain fill under the white drawn over it.
						int level = highlight ? 255 : r;
						fill.setPixel(px, py, a == 0 ? 0 : a << 24 | level << 16 | level << 8 | level);
						light.setPixel(px, py, highlight ? 0xFFFFFFFF : 0);
					}
				}
				var textures = mc().getTextureManager();
				textures.register(grey, new DynamicTexture(grey::toString, fill));
				textures.register(shine, new DynamicTexture(shine::toString, light));
				return true;
			}
		} catch (Exception e) {
			FlintClient.LOGGER.warn("Flint: couldn't make the heart pictures from {}", path, e);
			return false;
		}
	}
}
