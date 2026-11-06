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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
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
	private static final int WAYPOINTS = Mods.index(com.flintclient.client.control.model.mods.UtilityMods.Waypoints.ID);
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
		// Sound Physics' debug rays are world gizmos too, emitted in the same tick collection.
		com.flintclient.client.control.platform.sound.SoundPhysics.tickGizmos();
		if (!GUARD.on()) {
			return;
		}
		try {
			tickLight();
			tickChunks();
			Mc263Hitbox.tick(Mc263Features.gameplay());
		} catch (RuntimeException | LinkageError e) {
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
				com.flintclient.client.compat.McCompat.hud().setOverlayMessage(Component.literal(lightHidden ? "Light Overlay off" : "Light Overlay on"), false);
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
					if (!below.isValidSpawn(level, BELOW, com.flintclient.client.compat.McCompat.zombieType())) {
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
			Mc263Hitbox.frame();
			frameWaypoints();
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** Hitboxes drawn since the last call (for the dev tour). */
	public static int hitboxes() {
		return Mc263Hitbox.drawnAndReset();
	}

	/** The settings group an entity's hitbox follows. */
	static Hitbox.Group groupOf(Entity entity) {
		if (entity instanceof Player) {
			return Hitbox.PLAYERS;
		}
		if (entity instanceof ItemEntity) {
			return Hitbox.ITEMS;
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

	/**
	 * Each frame: this dimension's shown waypoints' blocks, as wWaypoints highlights them (an outline in the waypoint's colour
	 * and a light fill of it, fitted to the block's shape with Use block model, hidden behind other blocks unless Block through
	 * walls), within your render distance; and their beams (Flint's), a bright core in a soft glow from the bottom of the world
	 * to its top.
	 */
	private static void frameWaypoints() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null || !Mc263Features.markersShown() || com.flintclient.client.compat.McCompat.hud().isHidden()) {
			// F1 hides the blocks and beams with the labels, for a clean screenshot.
			return;
		}
		String dimension = level.dimension().identifier().toString();
		ModuleState state = Mods.state(WAYPOINTS);
		int display = state.option(com.flintclient.client.control.model.mods.UtilityMods.Waypoints.DISPLAY);
		boolean blocks = display != 1;
		boolean beams = state.bool(com.flintclient.client.control.model.mods.UtilityMods.Waypoints.SHOW_BEAM);
		double core = 0.09 * state.decimal(com.flintclient.client.control.model.mods.UtilityMods.Waypoints.BEAM_SCALE);
		boolean model = state.bool(com.flintclient.client.control.model.mods.UtilityMods.Waypoints.BLOCK_MODEL);
		boolean walls = state.bool(com.flintclient.client.control.model.mods.UtilityMods.Waypoints.THROUGH_WALLS);
		int outlineAlpha = Math.round(state.integer(com.flintclient.client.control.model.mods.UtilityMods.Waypoints.OUTLINE_OPACITY) * 2.55F);
		int fillAlpha = Math.round(state.integer(com.flintclient.client.control.model.mods.UtilityMods.Waypoints.FILL_OPACITY) * 2.55F);
		float thickness = state.decimal(com.flintclient.client.control.model.mods.UtilityMods.Waypoints.OUTLINE_THICKNESS);
		int max = state.integer(com.flintclient.client.control.model.mods.UtilityMods.Waypoints.MAX_DISTANCE);
		int within = Mc263Features.shownWithin();
		double view = Math.max(2, mc.options.getEffectiveRenderDistance() * 16);
		double reach = max > 0 ? Math.min(max, view) : view;
		if (within >= 0) {
			reach = Math.min(reach, within);
		}
		double bottom = level.getMinY();
		double top = level.getMaxY() + 1;
		boolean allSets = state.bool(com.flintclient.client.control.model.mods.UtilityMods.Waypoints.ALL_SETS);
		var store = com.flintclient.client.control.feature.Features.waypoints();
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (com.flintclient.client.control.feature.Waypoint wp : store.all()) {
			boolean block = blocks && wp.highlight() && (outlineAlpha > 0 || fillAlpha > 0);
			boolean beam = beams && wp.beam();
			if (!store.shown(wp, allSets) || !wp.dimension().equals(dimension) || !(block || beam)) {
				continue;
			}
			double dx = wp.x() + 0.5 - player.getX();
			double dy = wp.y() + 1 - player.getY();
			double dz = wp.z() + 0.5 - player.getZ();
			if (dx * dx + dy * dy + dz * dz > reach * reach) {
				continue;
			}
			int rgb = wp.rgb();
			if (beam) {
				double cx = wp.x() + 0.5;
				double cz = wp.z() + 0.5;
				Gizmos.cuboid(new AABB(cx - core, bottom, cz - core, cx + core, top, cz + core), GizmoStyle.fill(0xC0000000 | rgb));
				Gizmos.cuboid(new AABB(cx - core * 2.5, bottom, cz - core * 2.5, cx + core * 2.5, top, cz + core * 2.5), GizmoStyle.fill(0x38000000 | rgb));
			}
			if (block) {
				GizmoStyle look = thickness > 0 && outlineAlpha > 0 ? GizmoStyle.strokeAndFill(outlineAlpha << 24 | rgb, thickness, fillAlpha << 24 | rgb)
					: GizmoStyle.fill(fillAlpha << 24 | rgb);
				at.set(wp.x(), wp.y(), wp.z());
				java.util.List<AABB> boxes = model ? level.getBlockState(at).getShape(level, at).toAabbs() : java.util.List.of();
				if (boxes.isEmpty()) {
					var p = Gizmos.cuboid(new AABB(wp.x() - 0.002, wp.y() - 0.002, wp.z() - 0.002, wp.x() + 1.002, wp.y() + 1.002, wp.z() + 1.002), look);
					if (walls) {
						p.setAlwaysOnTop();
					}
				} else {
					for (AABB box : boxes) {
						var p = Gizmos.cuboid(box.move(wp.x(), wp.y(), wp.z()).inflate(0.002), look);
						if (walls) {
							p.setAlwaysOnTop();
						}
					}
				}
			}
		}
	}
}
