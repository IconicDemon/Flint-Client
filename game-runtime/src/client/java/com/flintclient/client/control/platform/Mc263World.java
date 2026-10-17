package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.LightRules;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.PlayerMods.Hitbox;
import com.flintclient.client.control.model.mods.VisualMods.ChunkBorders;
import com.flintclient.client.control.model.mods.VisualMods.LightOverlay;
import net.minecraft.client.Camera;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.Fireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.phys.AABB;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Minecraft 26.3's world overlays drawn with Minecraft's own gizmos (depth-tested lines, text and faces, as its debug views
 * are): Light Overlay. Emitted from the client tick into the per-tick gizmo collection, so a still overlay costs nothing
 * per frame beyond drawing. Reads only blocks in loaded chunks; never loads or generates any. Render thread;
 * fault-isolated.
 */
public final class Mc263World {
	private static final int LIGHT = Mods.index(LightOverlay.ID);
	private static final int CHUNKS = Mods.index(ChunkBorders.ID);
	private static final int HITBOX = Mods.index(Hitbox.ID);
	/** The most entities outlined in one frame. */
	static final int MAX_HITBOXES = 512;
	private static final int LOOK_COLOR = 0xFF3F76E4;
	private static boolean chunksHidden;
	private static boolean chunksKeyWas;
	private static final HookGuard GUARD = new HookGuard("world overlays",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	/** The most markers kept (a 32-block range in a flat cave would reach this). */
	static final int MAX_MARKERS = 4096;
	private static final int VERTICAL = 8;

	// Light Overlay: the spots found at the last poll.
	private static final int[] markX = new int[MAX_MARKERS];
	private static final int[] markY = new int[MAX_MARKERS];
	private static final int[] markZ = new int[MAX_MARKERS];
	private static final byte[] markLevel = new byte[MAX_MARKERS];
	private static final String[] NUMBERS = new String[16];
	private static int marks;
	private static int pollIn;
	private static boolean lightHidden;
	private static boolean lightKeyWas;
	private static ClientLevel lightLevel;
	private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();
	private static final BlockPos.MutableBlockPos BELOW = new BlockPos.MutableBlockPos();

	static {
		for (int i = 0; i < NUMBERS.length; i++) {
			NUMBERS[i] = Integer.toString(i);
		}
	}

	private Mc263World() {
	}

	/** From the client tick (the per-tick gizmo collection is open). */
	public static void tick() {
		if (!GUARD.on()) {
			return;
		}
		try {
			tickLight();
			tickChunks();
			tickWaypoints();
		} catch (RuntimeException e) {
			GUARD.fail(e);
		}
	}

	// ---------- Light Overlay ----------

	private static void tickLight() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null || !Mods.on(LIGHT)) {
			marks = 0;
			lightKeyWas = false;
			lightLevel = null;
			return;
		}
		if (level != lightLevel) {
			lightLevel = level;
			marks = 0;
			pollIn = 0;
		}
		ModuleState state = Mods.state(LIGHT);
		String key = state.text(LightOverlay.TOGGLE_KEY);
		boolean held = !key.isEmpty() && mc.gui.screen() == null && Mc263Features.keyHeld(key);
		if (held && !lightKeyWas) {
			lightHidden = !lightHidden;
			if (state.bool(LightOverlay.STATE_MESSAGE)) {
				mc.gui.hud.setOverlayMessage(Component.literal(lightHidden ? "Light Overlay off" : "Light Overlay on"), false);
			}
		}
		lightKeyWas = held;
		if (key.isEmpty()) {
			lightHidden = false;
		}
		if (lightHidden || !holdingAllows(player, state)) {
			return;
		}
		if (--pollIn <= 0) {
			pollIn = state.integer(LightOverlay.POLL);
			scan(level, player, state);
		}
		emitLight(player, state);
	}

	private static boolean holdingAllows(LocalPlayer player, ModuleState state) {
		boolean light = state.bool(LightOverlay.HOLDING_LIGHT);
		boolean pickaxe = state.bool(LightOverlay.HOLDING_PICKAXE);
		if (!light && !pickaxe) {
			return true;
		}
		return light && (givesLight(player.getMainHandItem()) || givesLight(player.getOffhandItem()))
			|| pickaxe && (player.getMainHandItem().is(ItemTags.PICKAXES) || player.getOffhandItem().is(ItemTags.PICKAXES));
	}

	private static boolean givesLight(ItemStack stack) {
		return stack.getItem() instanceof BlockItem block && block.getBlock().defaultBlockState().getLightEmission() > 0;
	}

	/** Finds the spots a mob could stand on within the range, in loaded chunks only, and their light. */
	private static void scan(ClientLevel level, LocalPlayer player, ModuleState state) {
		int range = state.integer(LightOverlay.RANGE);
		int vertical = Math.min(range, VERTICAL);
		int px = player.getBlockX();
		int py = player.getBlockY();
		int pz = player.getBlockZ();
		int type = state.option(LightOverlay.LIGHT_TYPE);
		boolean darken = state.bool(LightOverlay.COUNT_DARKEN);
		int skyDarken = level.getSkyDarken();
		boolean onlyDark = state.option(LightOverlay.SHOW) == 1;
		int danger = state.integer(LightOverlay.DANGER_LEVEL);
		int minY = Math.max(level.getMinY() + 1, py - vertical);
		int maxY = Math.min(level.getMaxY(), py + vertical);
		int n = 0;
		int r2 = range * range;
		for (int dx = -range; dx <= range && n < MAX_MARKERS; dx++) {
			for (int dz = -range; dz <= range && n < MAX_MARKERS; dz++) {
				if (dx * dx + dz * dz > r2) {
					continue;
				}
				int x = px + dx;
				int z = pz + dz;
				if (!level.hasChunkAt(x, z)) {
					continue;
				}
				for (int y = minY; y <= maxY && n < MAX_MARKERS; y++) {
					POS.set(x, y, z);
					BlockState here = level.getBlockState(POS);
					if (!here.getCollisionShape(level, POS).isEmpty() || !here.getFluidState().isEmpty()) {
						continue;
					}
					BELOW.set(x, y - 1, z);
					BlockState below = level.getBlockState(BELOW);
					if (!below.isValidSpawn(level, BELOW, EntityTypes.ZOMBIE)) {
						continue;
					}
					int light = LightRules.level(level.getBrightness(LightLayer.BLOCK, POS), level.getBrightness(LightLayer.SKY, POS), skyDarken,
						type, darken);
					if (onlyDark && !LightRules.dark(light, danger)) {
						continue;
					}
					markX[n] = x;
					markY[n] = y;
					markZ[n] = z;
					markLevel[n] = (byte) light;
					n++;
				}
			}
		}
		marks = n;
	}

	private static void emitLight(LocalPlayer player, ModuleState state) {
		int type = state.option(LightOverlay.TYPE);
		boolean value = type == 0 || state.bool(LightOverlay.SHOW_VALUE);
		boolean cull = state.bool(LightOverlay.CULLING);
		boolean gradient = state.option(LightOverlay.COLOR_TYPE) == 0;
		int danger = state.integer(LightOverlay.DANGER_LEVEL);
		int safest = state.integer(LightOverlay.SAFE_LEVEL);
		int dangerRgb = state.rgb(LightOverlay.DANGER_COLOR);
		int safeRgb = state.rgb(LightOverlay.SAFE_COLOR);
		float width = state.integer(LightOverlay.CROSS_THICKNESS);
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getViewVector(1.0F);
		for (int i = 0; i < marks; i++) {
			double cx = markX[i] + 0.5;
			double cy = markY[i] + 0.02;
			double cz = markZ[i] + 0.5;
			if (cull) {
				double vx = cx - eye.x;
				double vy = cy - eye.y;
				double vz = cz - eye.z;
				// Behind you (more than ~100 degrees off where you look), except right underfoot.
				if (vx * look.x + vy * look.y + vz * look.z < -0.2 * Math.sqrt(vx * vx + vy * vy + vz * vz) && vx * vx + vz * vz > 4) {
					continue;
				}
			}
			int light = markLevel[i];
			int argb = 0xFF000000 | LightRules.color(light, danger, safest, gradient, dangerRgb, safeRgb);
			if (type == 1) {
				Gizmos.line(new Vec3(cx - 0.35, cy, cz - 0.35), new Vec3(cx + 0.35, cy, cz + 0.35), argb, width);
				Gizmos.line(new Vec3(cx - 0.35, cy, cz + 0.35), new Vec3(cx + 0.35, cy, cz - 0.35), argb, width);
			} else if (type == 2) {
				int fill = 0x66000000 | (argb & 0xFFFFFF);
				Gizmos.rect(new Vec3(cx - 0.5, cy, cz - 0.5), new Vec3(cx + 0.5, cy, cz - 0.5), new Vec3(cx + 0.5, cy, cz + 0.5),
					new Vec3(cx - 0.5, cy, cz + 0.5), GizmoStyle.fill(fill));
			}
			if (value) {
				Gizmos.billboardText(NUMBERS[light], new Vec3(cx, cy + (type == 0 ? 0.25 : 0.4), cz), TextGizmo.Style.forColorAndCentered(argb)
					.withScale(type == 0 ? 0.5F : 0.35F));
			}
		}
	}

	/** How many Light Overlay spots the last poll found (for tests and the dev tour). */
	public static int lightMarkers() {
		return marks;
	}

	// ---------- Chunk Borders ----------

	private static void tickChunks() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null || !Mods.on(CHUNKS)) {
			chunksKeyWas = false;
			return;
		}
		ModuleState state = Mods.state(CHUNKS);
		String key = state.text(ChunkBorders.TOGGLE_KEY);
		boolean held = !key.isEmpty() && mc.gui.screen() == null && Mc263Features.keyHeld(key);
		if (held && !chunksKeyWas) {
			chunksHidden = !chunksHidden;
		}
		chunksKeyWas = held;
		if (key.isEmpty()) {
			chunksHidden = false;
		}
		if (chunksHidden) {
			return;
		}
		Entity camera = mc.getCameraEntity() != null ? mc.getCameraEntity() : player;
		SectionPos section = SectionPos.of(camera.blockPosition());
		double x0 = section.minBlockX();
		double z0 = section.minBlockZ();
		double bottom = level.getMinY();
		double top = level.getMaxY() + 1;
		if (state.bool(ChunkBorders.OUTER)) {
			int argb = 0xFF000000 | state.rgb(ChunkBorders.OUTER_COLOR);
			float width = state.integer(ChunkBorders.OUTER_THICKNESS);
			for (int x = -16; x <= 32; x += 16) {
				for (int z = -16; z <= 32; z += 16) {
					if ((x == 0 || x == 16) && (z == 0 || z == 16)) {
						continue;
					}
					Gizmos.line(new Vec3(x0 + x, bottom, z0 + z), new Vec3(x0 + x, top, z0 + z), argb, width);
				}
			}
		}
		if (state.bool(ChunkBorders.GRID)) {
			int argb = 0xFF000000 | state.rgb(ChunkBorders.GRID_COLOR);
			float width = state.integer(ChunkBorders.GRID_THICKNESS);
			int step = state.integer(ChunkBorders.GRID_SIZE);
			for (int i = step; i < 16; i += step) {
				Gizmos.line(new Vec3(x0 + i, bottom, z0), new Vec3(x0 + i, top, z0), argb, width);
				Gizmos.line(new Vec3(x0 + i, bottom, z0 + 16), new Vec3(x0 + i, top, z0 + 16), argb, width);
				Gizmos.line(new Vec3(x0, bottom, z0 + i), new Vec3(x0, top, z0 + i), argb, width);
				Gizmos.line(new Vec3(x0 + 16, bottom, z0 + i), new Vec3(x0 + 16, top, z0 + i), argb, width);
			}
			int cy = camera.getBlockY();
			int from = Math.max(level.getMinY(), Math.floorDiv(cy - 32, step) * step);
			int to = Math.min(level.getMaxY() + 1, cy + 32);
			for (int y = from; y <= to; y += step) {
				Gizmos.line(new Vec3(x0, y, z0), new Vec3(x0 + 16, y, z0), argb, width);
				Gizmos.line(new Vec3(x0 + 16, y, z0), new Vec3(x0 + 16, y, z0 + 16), argb, width);
				Gizmos.line(new Vec3(x0 + 16, y, z0 + 16), new Vec3(x0, y, z0 + 16), argb, width);
				Gizmos.line(new Vec3(x0, y, z0 + 16), new Vec3(x0, y, z0), argb, width);
			}
		}
		if (state.bool(ChunkBorders.INNER)) {
			int argb = 0xFF000000 | state.rgb(ChunkBorders.INNER_COLOR);
			float width = state.integer(ChunkBorders.INNER_THICKNESS);
			for (int x = 0; x <= 16; x += 16) {
				for (int z = 0; z <= 16; z += 16) {
					Gizmos.line(new Vec3(x0 + x, bottom, z0 + z), new Vec3(x0 + x, top, z0 + z), argb, width);
				}
			}
		}
	}

	// ---------- Hitbox ----------

	/** From the level extraction, after Minecraft's own debug views (the per-frame gizmo collection is open). */
	public static void frame(Camera camera, float partialTicks) {
		if (!GUARD.on()) {
			return;
		}
		try {
			frameHitboxes(camera, partialTicks);
		} catch (RuntimeException e) {
			GUARD.fail(e);
		}
	}

	private static void frameHitboxes(Camera camera, float partialTicks) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null || !Mods.on(HITBOX) || mc.gui.hud.isHidden()) {
			return;
		}
		ModuleState state = Mods.state(HITBOX);
		double limit = state.bool(Hitbox.LIMIT) ? state.integer(Hitbox.DISTANCE) : Double.MAX_VALUE;
		double limit2 = limit * limit;
		Vec3 eye = player.getEyePosition(partialTicks);
		double reach = player.entityInteractionRange();
		boolean firstPerson = mc.options.getCameraType().isFirstPerson();
		int drawn = 0;
		for (Entity entity : level.entitiesForRendering()) {
			if (drawn >= MAX_HITBOXES) {
				break;
			}
			if (entity == player && firstPerson || entity.isInvisibleTo(player)) {
				continue;
			}
			Hitbox.Group group = groupOf(entity);
			if (!state.bool(group.enabled())) {
				continue;
			}
			double dx = entity.getX() - player.getX();
			double dy = entity.getY() - player.getY();
			double dz = entity.getZ() - player.getZ();
			if (dx * dx + dy * dy + dz * dz > limit2) {
				continue;
			}
			// Where the entity is this frame (between its last two ticks), as it's drawn.
			double ox = net.minecraft.util.Mth.lerp(partialTicks, entity.xOld, entity.getX()) - entity.getX();
			double oy = net.minecraft.util.Mth.lerp(partialTicks, entity.yOld, entity.getY()) - entity.getY();
			double oz = net.minecraft.util.Mth.lerp(partialTicks, entity.zOld, entity.getZ()) - entity.getZ();
			AABB box = entity.getBoundingBox().move(ox, oy, oz);
			if (!camera.getCullFrustum().isVisible(box)) {
				continue;
			}
			int rgb = state.rgb(group.color());
			if (group.hittable() != null) {
				boolean inReach = entity != player && box.distanceToSqr(eye) <= reach * reach;
				if (state.bool(group.onlyHittable()) && !inReach) {
					continue;
				}
				if (state.bool(group.damaged()) && entity instanceof LivingEntity living && living.hurtTime > 0) {
					rgb = state.rgb(group.damagedColor());
				} else if (state.bool(group.hittable()) && inReach) {
					rgb = state.rgb(group.hittableColor());
				}
			}
			float width = state.integer(group.width());
			Gizmos.cuboid(box, GizmoStyle.stroke(0xFF000000 | rgb, width));
			if (state.bool(group.look())) {
				Vec3 from = new Vec3(box.getCenter().x, box.minY + entity.getEyeHeight(), box.getCenter().z);
				Gizmos.line(from, from.add(entity.getViewVector(partialTicks).scale(2)), LOOK_COLOR, width);
			}
			drawn++;
		}
		hitboxesDrawn = drawn;
	}

	private static int hitboxesDrawn;

	/** How many hitboxes the last frame outlined (for the dev tour). */
	public static int hitboxes() {
		return hitboxesDrawn;
	}

	/** The settings group an entity's hitbox follows; the most specific kind wins. */
	static Hitbox.Group groupOf(Entity entity) {
		if (entity instanceof Player) {
			return Hitbox.PLAYERS;
		}
		if (entity instanceof ItemEntity) {
			return Hitbox.ITEMS;
		}
		if (entity instanceof ExperienceOrb) {
			return Hitbox.ORBS;
		}
		if (entity instanceof ItemFrame) {
			return Hitbox.FRAMES;
		}
		if (entity instanceof FireworkRocketEntity) {
			return Hitbox.FIREWORKS;
		}
		if (entity instanceof WitherSkull) {
			return Hitbox.WITHER_SKULLS;
		}
		if (entity instanceof Snowball) {
			return Hitbox.SNOWBALLS;
		}
		if (entity instanceof Fireball) {
			return Hitbox.FIREBALLS;
		}
		if (entity instanceof AbstractArrow) {
			return Hitbox.ARROWS;
		}
		if (entity instanceof Projectile) {
			return Hitbox.PROJECTILES;
		}
		if (entity instanceof Enemy) {
			return Hitbox.MONSTERS;
		}
		if (entity instanceof LivingEntity) {
			return Hitbox.PASSIVE;
		}
		return Hitbox.OTHER;
	}

	// ---------- Waypoints ----------

	/** Beams and highlighted blocks of this dimension's shown waypoints, within the view distance (plus a little). */
	private static void tickWaypoints() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null || !Mc263Features.markersShown()) {
			return;
		}
		String dimension = level.dimension().identifier().toString();
		ModuleState state = Mods.state(Mods.index(com.flintclient.client.control.model.mods.UtilityMods.Waypoints.ID));
		int max = state.integer(com.flintclient.client.control.model.mods.UtilityMods.Waypoints.MAX_DISTANCE);
		double view = mc.options.getEffectiveRenderDistance() * 16 + 32;
		double reach = max > 0 ? Math.min(max, view) : view;
		double bottom = level.getMinY();
		double top = level.getMaxY() + 1;
		for (com.flintclient.client.control.feature.Waypoint wp : com.flintclient.client.control.feature.Features.waypoints().all()) {
			if (!wp.visible() || !wp.dimension().equals(dimension) || !(wp.beam() || wp.highlight())) {
				continue;
			}
			double dx = wp.x() + 0.5 - player.getX();
			double dz = wp.z() + 0.5 - player.getZ();
			if (dx * dx + dz * dz > reach * reach) {
				continue;
			}
			int argb = 0xFF000000 | wp.rgb();
			if (wp.beam()) {
				Gizmos.line(new Vec3(wp.x() + 0.5, bottom, wp.z() + 0.5), new Vec3(wp.x() + 0.5, top, wp.z() + 0.5), (argb & 0xFFFFFF) | 0xB0000000,
					6.0F);
			}
			if (wp.highlight()) {
				Gizmos.cuboid(new AABB(wp.x(), wp.y(), wp.z(), wp.x() + 1, wp.y() + 1, wp.z() + 1),
					GizmoStyle.strokeAndFill(argb, 2.5F, (argb & 0xFFFFFF) | 0x50000000));
			}
		}
	}
}
