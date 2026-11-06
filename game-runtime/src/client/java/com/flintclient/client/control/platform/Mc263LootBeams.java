/*
 * Adapted from Loot Beams Refork 3.4.7 by Clefal (https://github.com/TUsama/Loot-Beams-Refork), dedicated to the public
 * domain under CC0 1.0 (its repository's licence; its Modrinth page lists MIT). See
 * META-INF/licenses/CC0-1.0-LootBeamsRefork.txt. Its beam, glow and nametag drawing, item filter, colours, drop sound
 * and breathing are rebuilt here on Flint's settings; the textures and sounds are the mod's own.
 */
package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.LookMods.LootBeams;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Loot Beams on Minecraft: what each dropped item gets is worked out as its render state is filled (the filter, its
 * colour, the beam's size and fade, its name), and drawn with the item: a beam turned towards you with a soft bloom and a
 * fading top, a glow on the ground, the name over it. The tooltip mode draws on the HUD. Fault-isolated.
 */
public final class Mc263LootBeams {
	private static final HookGuard GUARD = new HookGuard("loot beams",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final int BEAMS = Mods.index(LootBeams.ID);
	private static final Identifier MAIN_BEAM = Identifier.fromNamespaceAndPath("flintclient", "textures/lootbeams/main_beam.png");
	private static final Identifier BEAM_TOP = Identifier.fromNamespaceAndPath("flintclient", "textures/lootbeams/beam_top.png");
	private static final Identifier GLOW = Identifier.fromNamespaceAndPath("flintclient", "textures/lootbeams/glow.png");
	private static final SoundEvent DROP = SoundEvent.createFixedRangeEvent(Identifier.fromNamespaceAndPath("flintclient", "loot_beams.drop"), 8);
	private static final String[] RARITY = {"Common", "Uncommon", "Rare", "Epic"};
	private static final int FULL_BRIGHT = 15728880;
	private static final byte UNDECIDED = 0;
	private static final byte PASS = 1;
	private static final byte REJECT = 2;

	/** What Loot Beams knows of one dropped item: its rarity and colour, whether it passed the filter, its fade-in, its chime. */
	private static final class Loot {
		@Nullable
		ItemStack stack;
		int revision = -1;
		int ordinal;
		int rgb;
		boolean overridden;
		byte render;
		boolean sounded;
		int fadeIn;
	}

	/** One frame's drawing for one item, kept on its render state. */
	public static final class Draw {
		boolean beam;
		float yaw;
		float radius;
		float height;
		float yOffset;
		int rgb;
		int alpha;
		boolean glow;
		float glowRadius;
		@Nullable
		String[] lines;
		int textColor;
		int backgroundAlpha;
		boolean border;
		float scale;
		double tagY;
	}

	private static final Map<ItemEntity, Loot> LOOT = new WeakHashMap<>();
	// The settings' lists, read again when the module's settings change.
	private static int parsed = -1;
	private static Set<String> whitelist = Set.of();
	private static Set<String> whitelistTags = Set.of();
	private static Set<String> whitelistMods = Set.of();
	private static Set<String> blacklist = Set.of();
	private static Set<String> blacklistTags = Set.of();
	private static Set<String> blacklistMods = Set.of();
	private static Set<String> equipmentItems = Set.of();
	private static Set<String> equipmentTags = Set.of();
	private static Set<String> equipmentMods = Set.of();
	private static Set<String> notEquipment = Set.of();
	private static Set<String> silentItems = Set.of();
	private static Set<String> silentTags = Set.of();
	private static Set<String> silentMods = Set.of();
	private static Map<String, Integer> colorByName = Map.of();
	private static Map<String, Integer> colorByTag = Map.of();
	private static Map<String, Integer> colorByMod = Map.of();
	// Breathing (Loot Beams' dynamic provider): 0 to 1 and back over twice the half-cycle.
	private static int breathTicks;
	private static float breath;
	private static boolean breathingOut;

	private Mc263LootBeams() {
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	/** Every client tick: the breathing moves on. */
	public static void tick() {
		if (!GUARD.on() || !Mods.on(BEAMS)) {
			return;
		}
		ModuleState s = Mods.state(BEAMS);
		if (!s.bool(LootBeams.DYNAMIC)) {
			return;
		}
		int half = s.integer(LootBeams.HALF_ROUND);
		breathTicks++;
		breath = breathingOut ? (half - breathTicks) / (float) half : breathTicks / (float) half;
		if (breathTicks >= half) {
			breathingOut = !breathingOut;
			breathTicks = 0;
		}
	}

	// ---------- extracting ----------

	/** As a dropped item's render state is filled: whether it gets a beam and a name this frame, and how they look. */
	public static void extract(ItemEntity entity, ItemEntityRenderState state) {
		LootBeamState out = (LootBeamState) state;
		out.flint$loot(null);
		if (!GUARD.on() || !Mods.on(BEAMS)) {
			return;
		}
		try {
			LocalPlayer player = mc().player;
			ItemStack stack = entity.getItem();
			if (player == null || stack.isEmpty()) {
				return;
			}
			ModuleState s = Mods.state(BEAMS);
			lists(s);
			Loot loot = loot(entity, stack, s, player);
			Draw draw = new Draw();
			boolean infoAll = s.bool(LootBeams.INFO_ALL);
			boolean info = infoAll;
			if (loot.render != REJECT) {
				boolean landed = !s.bool(LootBeams.ON_GROUND) || entity.onGround() || entity.isInWater();
				if (loot.render == PASS || renderable(loot, stack, s) && landed) {
					if (s.bool(LootBeams.BEAM)) {
						beam(entity, loot, s, player, draw);
						chime(entity, loot, stack, s);
					}
					info |= !infoAll;
					loot.render = PASS;
				} else if (landed) {
					loot.render = REJECT;
				}
			}
			int mode = s.option(LootBeams.INFO);
			if (info && (mode == 1 || mode == 2)) {
				nametag(entity, stack, loot, s, player, mode == 2, draw);
			}
			if (draw.beam || draw.lines != null) {
				out.flint$loot(draw);
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	private static Loot loot(ItemEntity entity, ItemStack stack, ModuleState s, LocalPlayer player) {
		Loot loot = LOOT.get(entity);
		if (loot == null || loot.stack != stack) {
			loot = new Loot();
			loot.stack = stack;
			LOOT.put(entity, loot);
		}
		if (loot.revision != s.revision()) {
			loot.revision = s.revision();
			loot.render = UNDECIDED;
			loot.ordinal = stack.getRarity().ordinal();
			TextColor rarity = TextColor.fromLegacyFormat(stack.getRarity().color());
			loot.rgb = rarity == null ? 0xFFFFFF : rarity.getValue() & 0xFFFFFF;
			loot.overridden = false;
			if (s.bool(LootBeams.CUSTOM_COLOR)) {
				Integer custom = customColor(stack);
				if (custom != null) {
					loot.rgb = custom;
					loot.overridden = true;
				}
			}
			if (!loot.overridden && s.bool(LootBeams.NAME_COLOR) && loot.ordinal == 0) {
				TextColor named = stack.getHoverName().getStyle().getColor();
				if (named != null) {
					loot.rgb = named.getValue() & 0xFFFFFF;
				}
			}
		}
		return loot;
	}

	/** Loot Beams' colour overrides: by item, then by tag, then by mod. */
	private static @Nullable Integer customColor(ItemStack stack) {
		Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		Integer c = colorByName.get(id.toString());
		if (c != null) {
			return c;
		}
		if (!colorByTag.isEmpty()) {
			Optional<Integer> tagged = stack.getItem().builtInRegistryHolder().tags().map(t -> colorByTag.get(t.location().toString())).filter(v -> v != null).findFirst();
			if (tagged.isPresent()) {
				return tagged.get();
			}
		}
		return colorByMod.get(id.getNamespace());
	}

	/** Loot Beams' filter: never the blacklist; always all items, the whitelist, a custom colour; else equipment and/or rare. */
	private static boolean renderable(Loot loot, ItemStack stack, ModuleState s) {
		if (listed(stack, blacklist, blacklistTags, blacklistMods)) {
			return false;
		}
		if (s.bool(LootBeams.ALL_ITEMS) || listed(stack, whitelist, whitelistTags, whitelistMods) || loot.overridden) {
			return true;
		}
		boolean rare = loot.ordinal >= s.option(LootBeams.RARE_FROM);
		if (s.bool(LootBeams.ONLY_EQUIPMENT)) {
			return equipment(stack) && (!s.bool(LootBeams.ONLY_RARE) || rare);
		}
		return s.bool(LootBeams.ONLY_RARE) && rare;
	}

	/** Anything the player can wear, shields, bows, crossbows, tridents, maces, and the equipment lists. */
	private static boolean equipment(ItemStack stack) {
		Item item = stack.getItem();
		Identifier id = BuiltInRegistries.ITEM.getKey(item);
		if (notEquipment.contains(id.toString())) {
			return false;
		}
		if (item instanceof ShieldItem || item instanceof BowItem || item instanceof CrossbowItem || item instanceof TridentItem
			|| item instanceof MaceItem) {
			return true;
		}
		Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
		LocalPlayer player = mc().player;
		if (equippable != null && player != null
			&& equippable.allowedEntities().map(set -> set.contains(player.getType().builtInRegistryHolder())).orElse(true)) {
			return true;
		}
		return listed(stack, equipmentItems, equipmentTags, equipmentMods);
	}

	private static boolean listed(ItemStack stack, Set<String> items, Set<String> tags, Set<String> mods) {
		if (items.isEmpty() && tags.isEmpty() && mods.isEmpty()) {
			return false;
		}
		Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		return items.contains(id.toString()) || mods.contains(id.getNamespace())
			|| !tags.isEmpty() && stack.getItem().builtInRegistryHolder().tags().anyMatch(t -> tags.contains(t.location().toString()));
	}

	/** The beam's size, fade and colour for this frame (Loot Beams' beam renderer). */
	private static void beam(ItemEntity entity, Loot loot, ModuleState s, LocalPlayer player, Draw draw) {
		int fadeInTime = s.integer(LootBeams.FADE_IN_TIME);
		float fadeIn = loot.fadeIn / (float) fadeInTime;
		if (loot.fadeIn < fadeInTime) {
			loot.fadeIn++;
		}
		float alpha = s.decimal(LootBeams.ALPHA);
		Vec3 at = new Vec3(entity.getX(), entity.getY(), entity.getZ());
		double distance = Math.sqrt(player.distanceToSqr(at));
		float fadeDistance = s.decimal(LootBeams.FADE_DISTANCE);
		if (distance > fadeDistance) {
			alpha *= 1.0F / Math.max((float) (distance - fadeDistance) / fadeDistance, 1.0F);
		}
		float radius = 0.08F * s.decimal(LootBeams.RADIUS);
		float height = s.decimal(LootBeams.HEIGHT);
		float yOffset = s.decimal(LootBeams.Y_OFFSET);
		if (s.bool(LootBeams.COMMON_SHORTER) && loot.ordinal <= 0) {
			height *= 0.65F;
			yOffset = 0;
		}
		int beamAlpha = (int) (alpha * 255.0F);
		if (s.bool(LootBeams.DYNAMIC)) {
			float light = 0.5F * breath + 0.6F;
			beamAlpha = (int) (beamAlpha * Math.min(light, 1.0F));
			height += light - 0.3F;
			radius += 0.005F * (breath + 0.1F);
		}
		beamAlpha = (int) (beamAlpha * fadeIn);
		height *= fadeIn;
		Vec3 from = player.getPosition(mc().getDeltaTracker().getGameTimeDeltaPartialTick(true));
		Vec3 direction = at.subtract(from).normalize();
		draw.beam = true;
		draw.yaw = (float) Math.atan2(direction.x, direction.z);
		draw.radius = radius;
		draw.height = height;
		draw.yOffset = yOffset;
		draw.rgb = loot.rgb;
		draw.alpha = Math.clamp(beamAlpha, 0, 255);
		draw.glow = s.bool(LootBeams.GLOW) && entity.onGround();
		draw.glowRadius = s.decimal(LootBeams.GLOW_RADIUS);
	}

	/** The drop chime, once per item, for a rare one that isn't silenced (the frame after its beam first shows). */
	private static void chime(ItemEntity entity, Loot loot, ItemStack stack, ModuleState s) {
		if (loot.sounded || loot.render != PASS || !s.bool(LootBeams.SOUND) || loot.ordinal < s.option(LootBeams.RARE_FROM)
			|| listed(stack, silentItems, silentTags, silentMods) || mc().level == null) {
			return;
		}
		mc().level.playLocalSound(entity.getX(), entity.getY(), entity.getZ(), DROP, SoundSource.AMBIENT, 0.1F * s.decimal(LootBeams.SOUND_VOLUME), 1,
			false);
		loot.sounded = true;
	}

	/** The name (and rarity) over the item: while you crouch, or while you look at it. */
	private static void nametag(ItemEntity entity, ItemStack stack, Loot loot, ModuleState s, LocalPlayer player, boolean rarity, Draw draw) {
		Vec3 at = new Vec3(entity.getX(), entity.getY(), entity.getZ());
		if (!player.isCrouching() && !(s.bool(LootBeams.ON_LOOK) && lookingAt(player, at, s.decimal(LootBeams.LOOK_SENSITIVITY)))) {
			return;
		}
		List<String> lines = new ArrayList<>(2);
		lines.add(name(stack, s).getString());
		if (rarity && showRarity(loot, s)) {
			lines.add(RARITY[Math.clamp(loot.ordinal, 0, 3)]);
		}
		draw.lines = lines.toArray(new String[0]);
		draw.textColor = (int) (s.decimal(LootBeams.TEXT_ALPHA) * 255.0F) << 24 | loot.rgb;
		draw.backgroundAlpha = (int) (255.0F * s.decimal(LootBeams.BACKGROUND_ALPHA));
		draw.border = s.bool(LootBeams.TEXT_BORDER);
		draw.scale = s.decimal(LootBeams.NAMETAG_SCALE);
		draw.tagY = Math.min(1.0, player.distanceToSqr(at) * 0.025) + s.decimal(LootBeams.NAMETAG_Y_OFFSET);
		draw.rgb = loot.rgb;
	}

	private static boolean showRarity(Loot loot, ModuleState s) {
		return switch (s.option(LootBeams.SHOW_RARITY)) {
			case 0 -> false;
			case 1 -> loot.ordinal >= s.option(LootBeams.RARE_FROM);
			default -> true;
		};
	}

	/** The item's name, with " xN" for a stack when Stack count is on. */
	private static Component name(ItemStack stack, ModuleState s) {
		Component name = stack.getHoverName();
		if (!s.bool(LootBeams.STACK_COUNT) || stack.getCount() <= 1) {
			return name;
		}
		return Component.literal(name.getString()).append(" x" + stack.getCount()).withStyle(name.getStyle());
	}

	/** Whether the camera looks at {@code at}: within the sensitivity's angle, wider up close. */
	private static boolean lookingAt(LocalPlayer player, Vec3 at, double accuracy) {
		Vec3 difference = new Vec3(at.x - player.getX(), at.y - player.getEyeY(), at.z - player.getZ());
		double length = difference.length();
		Entity camera = mc().getCameraEntity();
		if (camera == null || length == 0) {
			return false;
		}
		double dot = camera.getViewVector(1.0F).normalize().dot(difference.normalize());
		return dot > 1.0 - accuracy / length;
	}

	// ---------- drawing ----------

	/** With the dropped item, its pose at the item: the beam, the glow and the name worked out for this frame. */
	public static void submit(ItemEntityRenderState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		Draw d = ((LootBeamState) state).flint$loot();
		if (d == null || !GUARD.on()) {
			return;
		}
		try {
			if (d.beam && d.alpha > 0) {
				drawBeam(d, pose, collector);
			}
			if (d.lines != null) {
				drawName(d, pose, collector, camera);
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	private static void drawBeam(Draw d, PoseStack pose, SubmitNodeCollector collector) {
		int r = d.rgb >> 16 & 0xFF;
		int g = d.rgb >> 8 & 0xFF;
		int b = d.rgb & 0xFF;
		int a = d.alpha;
		int bloomA = (int) (a * 0.4F);
		float radius = d.radius;
		float bloom = radius * 1.35F;
		float h = d.height;
		pose.pushPose();
		com.flintclient.client.compat.McCompat.rotate(pose, Axis.YP, d.yaw);
		pose.pushPose();
		pose.translate(0.0F, d.yOffset + 1.0F, 0.0F);
		collector.submitCustomGeometry(pose, beamType(MAIN_BEAM), (p, vc) -> {
			quad(p, vc, r, g, b, a, -radius, radius, -h, h, 0.001F);
			quad(p, vc, r, g, b, bloomA, -bloom, bloom, -h, h, 0.001F);
		});
		collector.submitCustomGeometry(pose, beamType(BEAM_TOP), (p, vc) -> {
			quad(p, vc, r, g, b, a, radius, -radius, h * 3.0F / 2.0F, h, 0.001F);
			quad(p, vc, r, g, b, bloomA, bloom, -bloom, h * 3.0F / 2.0F, h, 0.001F);
		});
		pose.popPose();
		pose.popPose();
		if (d.glow) {
			float glow = d.glowRadius;
			pose.pushPose();
			com.flintclient.client.compat.McCompat.rotateDegrees(pose, Axis.XP, 90.0F);
			pose.translate(0.0F, -glow, -0.01F);
			collector.submitCustomGeometry(pose, beamType(GLOW), (p, vc) -> quad(p, vc, r, g, b, bloomA, -glow, glow, 0.0F, 1.0F, 0.0F));
			pose.popPose();
		}
	}

	private static RenderType beamType(Identifier texture) {
		return RenderTypes.beaconBeam(texture, true);
	}

	private static void quad(PoseStack.Pose p, VertexConsumer vc, int r, int g, int b, int a, float minX, float maxX, float minY, float maxY, float z) {
		vertex(p, vc, minX, minY, z, r, g, b, a, 0, 0);
		vertex(p, vc, minX, maxY, z, r, g, b, a, 0, 1);
		vertex(p, vc, maxX, maxY, z, r, g, b, a, 1, 1);
		vertex(p, vc, maxX, minY, z, r, g, b, a, 1, 0);
	}

	private static void vertex(PoseStack.Pose p, VertexConsumer vc, float x, float y, float z, int r, int g, int b, int a, float u, float v) {
		vc.addVertex(p, x, y, z).setColor(r, g, b, a).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULL_BRIGHT).setNormal(p, 0, 1, 0);
	}

	private static void drawName(Draw d, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		Font font = mc().font;
		float scale = d.scale;
		pose.pushPose();
		pose.translate(0.0, d.tagY, 0.0);
		com.flintclient.client.compat.McCompat.rotate(pose, camera.orientation);
		com.flintclient.client.compat.McCompat.rotateDegrees(pose, Axis.YP, 180.0F);
		pose.scale(-0.02F * scale, -0.02F * scale, 0.02F * scale);
		pose.translate(0.0F, 2.0F, -10.0F);
		for (String line : d.lines) {
			if (!line.isBlank()) {
				if (d.border) {
					collector.submitText(pose, -font.width(line) / 2.0F, 0, Component.literal(line).getVisualOrderText(), false, Font.DisplayMode.NORMAL,
						FULL_BRIGHT, d.textColor, 0, d.backgroundAlpha << 24);
				} else {
					collector.submitText(pose, -font.width(line) / 2, 30, Component.literal(line).getVisualOrderText(), false, Font.DisplayMode.NORMAL,
						15728864, d.textColor, d.backgroundAlpha << 24, 0);
				}
				pose.translate(0.0F, 9.0F, 0.0F);
			}
		}
		pose.popPose();
	}

	// ---------- the tooltip mode ----------

	/** On the HUD: in the tooltip mode, the item you look at gets its name and rarity (or, crouching, its whole tooltip). */
	public static void hud(GuiGraphicsExtractor graphics) {
		if (!GUARD.on() || !Mods.on(BEAMS)) {
			return;
		}
		try {
			ModuleState s = Mods.state(BEAMS);
			Minecraft mc = mc();
			LocalPlayer player = mc.player;
			if (s.option(LootBeams.INFO) != 3 || player == null || mc.level == null || mc.gui.screen() != null) {
				return;
			}
			lists(s);
			ItemEntity item = lookedAtItem(player, mc.getDeltaTracker().getGameTimeDeltaPartialTick(true));
			if (item == null || item.getItem().isEmpty()) {
				return;
			}
			ItemStack stack = item.getItem();
			Loot loot = loot(item, stack, s, player);
			int x = s.option(LootBeams.TOOLTIP_POSITION) == 0 ? graphics.guiWidth() / 2 : graphics.guiWidth() - s.integer(LootBeams.TOOLTIP_X);
			int y = s.option(LootBeams.TOOLTIP_POSITION) == 0 ? graphics.guiHeight() / 2 : graphics.guiHeight() - s.integer(LootBeams.TOOLTIP_Y);
			if (!s.bool(LootBeams.TOOLTIP_CROUCH) || player.isCrouching()) {
				graphics.setTooltipForNextFrame(mc.font, stack, x, y);
				// Minecraft draws a deferred tooltip only for screens: drawn here, on the HUD, now.
				graphics.extractDeferredElements(0, 0, 0);
				return;
			}
			List<Component> lines = new ArrayList<>(2);
			lines.add(colored(name(stack, s), loot.rgb));
			if (showRarity(loot, s)) {
				lines.add(colored(Component.literal(RARITY[Math.clamp(loot.ordinal, 0, 3)]), loot.rgb));
			}
			graphics.setTooltipForNextFrame(mc.font, lines, stack.getTooltipImage(), x, y);
			graphics.extractDeferredElements(0, 0, 0);
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** A line without a style of its own takes the item's colour. */
	private static Component colored(Component line, int rgb) {
		return line.getStyle().equals(Style.EMPTY) ? Component.literal(line.getString()).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb))) : line;
	}

	/** The first dropped item along your view, up to 6 blocks or what you're looking at, its box grown half a block. */
	private static @Nullable ItemEntity lookedAtItem(LocalPlayer player, float partialTick) {
		Minecraft mc = mc();
		double distance = 6.0;
		Vec3 eye = player.getEyePosition(partialTick);
		Vec3 view = player.getViewVector(partialTick);
		if (mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS) {
			distance = mc.hitResult.getLocation().distanceTo(eye);
		}
		Vec3 look = eye.add(view.x * distance, view.y * distance, view.z * distance);
		Vec3 reach = look.subtract(eye);
		for (Entity entity : player.level().getEntities(player, player.getBoundingBox().expandTowards(reach.x, reach.y, reach.z))) {
			if (entity instanceof ItemEntity item) {
				AABB box = item.getBoundingBox().inflate(0.5).inflate(0.0, 0.5, 0.0);
				if (box.clip(eye, look).isPresent() || box.contains(eye)) {
					return item;
				}
			}
		}
		return null;
	}

	// ---------- the settings' lists ----------

	private static void lists(ModuleState s) {
		if (parsed == s.revision()) {
			return;
		}
		parsed = s.revision();
		whitelist = new HashSet<>(s.ids(LootBeams.WHITELIST));
		whitelistTags = tags(s.text(LootBeams.WHITELIST_TAGS));
		whitelistMods = words(s.text(LootBeams.WHITELIST_MODS));
		blacklist = new HashSet<>(s.ids(LootBeams.BLACKLIST));
		blacklistTags = tags(s.text(LootBeams.BLACKLIST_TAGS));
		blacklistMods = words(s.text(LootBeams.BLACKLIST_MODS));
		equipmentItems = new HashSet<>(s.ids(LootBeams.EQUIPMENT_ITEMS));
		equipmentTags = tags(s.text(LootBeams.EQUIPMENT_TAGS));
		equipmentMods = words(s.text(LootBeams.EQUIPMENT_MODS));
		notEquipment = new HashSet<>(s.ids(LootBeams.NOT_EQUIPMENT));
		silentItems = new HashSet<>(s.ids(LootBeams.SOUND_BLACKLIST));
		silentTags = tags(s.text(LootBeams.SOUND_BLACKLIST_TAGS));
		silentMods = words(s.text(LootBeams.SOUND_BLACKLIST_MODS));
		colorByName = colors(s.text(LootBeams.COLOR_BY_NAME), true);
		colorByTag = colors(s.text(LootBeams.COLOR_BY_TAG), true);
		colorByMod = colors(s.text(LootBeams.COLOR_BY_MOD), false);
	}

	/** Comma- (or space-) separated words, lower case. */
	static Set<String> words(String text) {
		Set<String> out = new HashSet<>();
		for (String w : text.split("[,\\s]+")) {
			if (!w.isBlank()) {
				out.add(w.strip().toLowerCase(Locale.ROOT));
			}
		}
		return out;
	}

	/** Tags as Loot Beams writes them ({@code #namespace:path}); the leading # is optional here. */
	static Set<String> tags(String text) {
		Set<String> out = new HashSet<>();
		for (String w : words(text)) {
			String tag = w.startsWith("#") ? w.substring(1) : w;
			out.add(tag.contains(":") ? tag : "minecraft:" + tag);
		}
		return out;
	}

	/** {@code key=#RRGGBB} pairs (an #AARRGGBB colour's alpha is ignored, as Loot Beams draws the beam with its own). */
	static Map<String, Integer> colors(String text, boolean ids) {
		Map<String, Integer> out = new HashMap<>();
		for (String pair : text.split(",")) {
			int eq = pair.indexOf('=');
			if (eq <= 0) {
				continue;
			}
			String key = pair.substring(0, eq).strip().toLowerCase(Locale.ROOT);
			String value = pair.substring(eq + 1).strip();
			if (value.startsWith("#")) {
				value = value.substring(1);
			} else if (value.startsWith("0x") || value.startsWith("0X")) {
				value = value.substring(2);
			}
			if (ids) {
				// An item or tag id (a tag written with Loot Beams' leading #); no namespace means minecraft's.
				key = key.startsWith("#") ? key.substring(1) : key;
				key = key.contains(":") ? key : "minecraft:" + key;
			}
			if (key.isEmpty()) {
				continue;
			}
			try {
				if (value.length() == 6 || value.length() == 8) {
					out.put(key, (int) (Long.parseLong(value, 16) & 0xFFFFFF));
				}
			} catch (NumberFormatException ignored) {
				// Not a colour: the pair is skipped, as Loot Beams' own validation would refuse it.
			}
		}
		return out;
	}
}
