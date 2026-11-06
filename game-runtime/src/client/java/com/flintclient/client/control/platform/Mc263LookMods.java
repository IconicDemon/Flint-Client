package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.LookMods.Glint;
import com.flintclient.client.control.model.mods.LookMods.MobOverlay;
import com.flintclient.client.control.model.mods.LookMods.ShieldStatus;
import com.flintclient.client.render.Palette;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 26.3's side of the third batch's world looks: Shield Status (the tint your held shield is drawn with), Glint
 * (Minecraft's two glint textures swapped for recoloured, cleared or cycling ones; shiny potions), Mob Overlay (Minecraft's
 * own glowing outline in Flint's colour and thickness, or a translucent fill over each mob). Loot Beams is
 * {@link Mc263LootBeams}. Fault-isolated.
 */
public final class Mc263LookMods {
	private static final HookGuard GUARD = new HookGuard("world looks",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final int SHIELD = Mods.index(ShieldStatus.ID);
	private static final int GLINT = Mods.index(Glint.ID);
	private static final int MOBS = Mods.index(MobOverlay.ID);
	private static final Identifier OUTLINE = Identifier.withDefaultNamespace("entity_outline");
	private static final Identifier WHITE = Identifier.fromNamespaceAndPath("flintclient", "white");

	private Mc263LookMods() {
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	/** Every client tick: keeps the glint textures in step with Glint's settings (a cycling one moves on each tick). */
	public static void tick() {
		if (!GUARD.on()) {
			return;
		}
		try {
			tickGlint();
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	// ---------- Shield Status ----------

	/** The tint for the shield being drawn now ({@code -1}: Minecraft's own white). */
	private static int heldTint = -1;

	/** Before your held item is drawn in first person: Shield Status's tint for it, if it's a shield. Returns what to restore. */
	public static int beginHeld(ItemStack stack) {
		int before = heldTint;
		heldTint = -1;
		if (!GUARD.on() || !stack.is(Items.SHIELD) || !Mods.on(SHIELD)) {
			return before;
		}
		try {
			LocalPlayer player = mc().player;
			if (player == null) {
				return before;
			}
			ModuleState s = Mods.state(SHIELD);
			int ready = s.bool(ShieldStatus.READY) ? 0xFF000000 | s.rgb(ShieldStatus.READY_COLOR) : 0xFFFFFFFF;
			if (player.getCooldowns().isOnCooldown(stack) && s.bool(ShieldStatus.DISABLED)) {
				int disabled = 0xFF000000 | s.rgb(ShieldStatus.DISABLED_COLOR);
				float left = player.getCooldowns().getCooldownPercent(stack, mc().getDeltaTracker().getGameTimeDeltaPartialTick(true));
				heldTint = s.bool(ShieldStatus.ANIMATE) ? Palette.lerp(ready, disabled, Math.clamp(left, 0, 1)) : disabled;
			} else if (s.bool(ShieldStatus.READY)) {
				heldTint = ready;
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
		return before;
	}

	public static void endHeld(int before) {
		heldTint = before;
	}

	/** The colour a shield is drawn with: Minecraft's {@code original}, or Shield Status's tint while your held shield is drawn. */
	public static int shieldColor(int original) {
		return heldTint == -1 ? original : heldTint;
	}

	// ---------- Glint ----------

	/** A glint texture Flint drew: repeating and smoothed, as Minecraft's own glint is. */
	private static final class GlintTexture extends DynamicTexture {
		GlintTexture(Identifier id, NativeImage image) {
			super(id::toString, image);
			this.sampler = com.flintclient.client.compat.McCompat.linearRepeatSampler();
		}
	}

	/** Per glint texture (0 items, 1 armor): what was applied, so it is redrawn only when that changes. */
	private static final String[] applied = {"", ""};
	private static final @Nullable NativeImage[] originals = new NativeImage[2];
	private static final @Nullable GlintTexture[] made = new GlintTexture[2];

	private static void tickGlint() {
		boolean on = Mods.on(GLINT);
		ModuleState s = Mods.state(GLINT);
		apply(0, ItemFeatureRenderer.ENCHANTED_GLINT_ITEM, on ? s.option(Glint.ITEM_MODE) : 1, s.rgb(Glint.ITEM_COLOR));
		apply(1, ItemFeatureRenderer.ENCHANTED_GLINT_ARMOR, on ? s.option(Glint.ARMOR_MODE) : 1, s.rgb(Glint.ARMOR_COLOR));
	}

	/** Mode 0 off, 1 Minecraft's, 2 a colour, 3 cycling through colours. */
	private static void apply(int which, Identifier id, int mode, int rgb) {
		long now = System.currentTimeMillis();
		int color = mode == 3 ? java.awt.Color.HSBtoRGB(now % 6000 / 6000.0F, 0.75F, 1.0F) & 0xFFFFFF : rgb;
		String key = mode == 1 ? "default" : mode == 0 ? "off" : Integer.toHexString(color);
		if (key.equals(applied[which])) {
			return;
		}
		applied[which] = key;
		var textures = mc().getTextureManager();
		if (mode == 1) {
			if (made[which] != null) {
				// Back to Minecraft's own: dropped here, it loads again from the resource packs when next drawn.
				textures.release(id);
				made[which] = null;
			}
			return;
		}
		NativeImage original = original(which, id);
		if (original == null) {
			return;
		}
		GlintTexture texture = made[which];
		if (texture == null) {
			NativeImage pixels = new NativeImage(original.getWidth(), original.getHeight(), false);
			texture = new GlintTexture(id, pixels);
			textures.register(id, texture);
			made[which] = texture;
		}
		NativeImage pixels = texture.getPixels();
		if (pixels == null) {
			return;
		}
		float r = (color >> 16 & 255) / 255.0F;
		float g = (color >> 8 & 255) / 255.0F;
		float b = (color & 255) / 255.0F;
		for (int y = 0; y < original.getHeight(); y++) {
			for (int x = 0; x < original.getWidth(); x++) {
				int argb = original.getPixel(x, y);
				if (mode == 0) {
					pixels.setPixel(x, y, 0);
					continue;
				}
				// The glint's brightness, in the chosen colour (the glint adds light, so darker is fainter).
				float level = Math.max(argb >> 16 & 255, Math.max(argb >> 8 & 255, argb & 255)) / 255.0F;
				int out = (argb & 0xFF000000) | Math.round(r * level * 255) << 16 | Math.round(g * level * 255) << 8 | Math.round(b * level * 255);
				pixels.setPixel(x, y, out);
			}
		}
		texture.upload();
	}

	/** Minecraft's own glint picture, read once from the resource packs. */
	private static @Nullable NativeImage original(int which, Identifier id) {
		if (originals[which] != null) {
			return originals[which];
		}
		try {
			Optional<Resource> resource = mc().getResourceManager().getResource(id);
			if (resource.isEmpty()) {
				return null;
			}
			try (InputStream in = resource.get().open()) {
				originals[which] = NativeImage.read(in);
			}
		} catch (Exception e) {
			FlintClient.LOGGER.warn("Flint: couldn't read {}", id, e);
		}
		return originals[which];
	}

	/** Whether a potion glints anyway (Glint's Shiny potions). */
	public static boolean shinyPotion(ItemStack stack) {
		if (!GUARD.on() || !Mods.on(GLINT) || !Mods.state(GLINT).bool(Glint.SHINY_POTS)) {
			return false;
		}
		PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
		return contents != null && contents.hasEffects();
	}

	// ---------- Mob Overlay ----------

	/** Whether Mob Overlay covers {@code entity} now (a mob; with Show always off, only the one under the crosshair). */
	private static boolean overlaid(Entity entity, int mode) {
		if (!(entity instanceof Mob) || !Mods.on(MOBS)) {
			return false;
		}
		ModuleState s = Mods.state(MOBS);
		if (s.option(MobOverlay.MODE) != mode) {
			return false;
		}
		return s.bool(MobOverlay.ALWAYS) || mc().crosshairPickEntity == entity;
	}

	/** Whether {@code entity} is drawn with Minecraft's glowing outline (Mob Overlay's Outline mode adds mobs to it). */
	public static boolean glowing(Entity entity, boolean original) {
		if (original || !GUARD.on()) {
			return original;
		}
		try {
			return overlaid(entity, 0);
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return false;
		}
	}

	/** The outline colour of a glowing {@code entity}: Mob Overlay's for the mobs it outlines. */
	public static int outlineColor(Entity entity, int original) {
		if (!GUARD.on() || entity.isCurrentlyGlowing()) {
			return original;
		}
		try {
			return overlaid(entity, 0) ? Mods.state(MOBS).rgb(MobOverlay.OUTLINE_COLOR) : original;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return original;
		}
	}

	/** The outline post effect Minecraft draws glowing entities with: one with Mob Overlay's thickness while it outlines mobs. */
	public static Identifier outlineChain(Identifier id) {
		if (!GUARD.on() || !id.equals(OUTLINE) || !Mods.on(MOBS)) {
			return id;
		}
		ModuleState s = Mods.state(MOBS);
		if (s.option(MobOverlay.MODE) != 0) {
			return id;
		}
		int radius = 1 + s.integer(MobOverlay.THICKNESS) / 25;
		return radius == 2 ? id : Identifier.fromNamespaceAndPath("flintclient", "entity_outline_r" + radius);
	}

	/** Mob Overlay's fill for {@code entity} as it is drawn ({@code 0xAARRGGBB}), or 0 for none. */
	public static int fill(LivingEntity entity) {
		if (!GUARD.on()) {
			return 0;
		}
		try {
			if (!overlaid(entity, 1)) {
				return 0;
			}
			ModuleState s = Mods.state(MOBS);
			return Math.round(s.integer(MobOverlay.FILL_OPACITY) * 2.55F) << 24 | s.rgb(MobOverlay.FILL_COLOR);
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return 0;
		}
	}

	/** A plain white texture the fill is drawn with (made the first time). */
	public static Identifier white() {
		var textures = mc().getTextureManager();
		if (!(textures.getTexture(WHITE) instanceof DynamicTexture)) {
			NativeImage image = new NativeImage(16, 16, false);
			image.fillRect(0, 0, 16, 16, 0xFFFFFFFF);
			textures.register(WHITE, new DynamicTexture(WHITE::toString, image));
		}
		return WHITE;
	}
}
