package com.flintclient.client.control.platform;

import com.flintclient.client.control.feature.Features;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.feature.Waypoint;
import com.flintclient.client.control.feature.WaypointShare;
import com.flintclient.client.control.feature.map.MapRegion;
import com.flintclient.client.control.feature.map.MapWorld;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.MapMods.Minimap;
import com.flintclient.client.control.model.mods.UtilityMods;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FontFace;
import com.flintclient.client.render.Labels;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.render.WaypointBadge;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import org.jspecify.annotations.Nullable;

/**
 * Draws the Minimap module (its view lays it out): the terrain from the map's textures, cut round or square and turned with
 * you; the chunk grid and slime chunks; waypoints (ones beyond the edge wait on it, toward them); the entity radar (players
 * as their faces, mobs and items as dots, fainter far above or below); your arrow; the frame and compass. And its lines of
 * information. Render thread; the few objects it makes are per new name or label.
 */
public final class Mc263Minimap {
	private static final int WAYPOINTS = Mods.index(UtilityMods.Waypoints.ID);
	/** At most this many mob heads a frame; past it, dots (a crowd of mobs stays cheap). */
	private static final int MAX_ICONS = 64;
	private static final Mc263MapDraw.View VIEW = new Mc263MapDraw.View();
	private static final float[] AT = new float[2];
	private static final float[] SHAPE = new float[16];
	private static final double[] WORLD = new double[2];
	private static final String[] POINTS = {"N", "E", "S", "W"};
	/** The maps' small text: Minecraft's font, a pixel scale under the GUI's (as the menu's small text). */
	static FontFace small(Canvas c) {
		return com.flintclient.client.render.PixelFace.fixed(Math.max(1, c.guiScale() - 1));
	}
	private static String[] info = new String[0];
	private static long infoKey = Long.MIN_VALUE;
	private static int infoFlags = -1;
	private static @Nullable String summary;

	private Mc263Minimap() {
	}

	/** The enlarged minimap's size (GUI units, before the HUD's scale): Xaero's 250. */
	static final int ENLARGED = 250;
	/** Drawing the enlarged map square: north stays up (as Xaero's). */
	private static boolean northUp;

	/**
	 * The enlarged minimap (its key held): {@link #ENLARGED} across at most, in the middle of a screen {@code screenW} ×
	 * {@code screenH} pixels, north up when square. No information lines.
	 */
	public static void drawEnlarged(Canvas c, ModuleState s, int screenW, int screenH, float unit) {
		// Clear of the hotbar (about 30 GUI pixels at the bottom) with a margin round it.
		int hotbar = Math.round(32 * c.guiScale());
		int size = Math.min(Math.round(ENLARGED * unit), Math.min(screenW - Math.round(32 * unit), screenH - hotbar - Math.round(24 * unit)));
		if (size <= 0) {
			return;
		}
		northUp = s.option(Minimap.SHAPE) == 1;
		try {
			draw(c, s, (screenW - size) / 2, (screenH - hotbar - size) / 2, size, unit, true);
		} finally {
			northUp = false;
		}
	}

	/** Whether there is a map to draw (a world, and the maps running). */
	public static boolean ready() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null && mc.player != null && Mc263Maps.surface() != null;
	}

	/** A text line for {@code text} in {@code face}, kept for the next frames (labels, names, menu items). */
	static TextLine label(String text, FontFace face) {
		return Labels.get(text, face);
	}

	// ---------- the map ----------

	/**
	 * Draws the map {@code size} px square at ({@code x}, {@code y}) on {@code c}; {@code unit} is pixels per GUI unit at the
	 * module's scale. {@code hud}: the HUD itself (textures may be built within its budget), not a preview.
	 */
	public static void draw(Canvas c, ModuleState s, int x, int y, int size, float unit, boolean hud) {
		// Its words (labels, compass letters, information lines) in Minecraft's font, as all of Flint's in-game text.
		int font = com.flintclient.client.render.PixelFace.begin(com.flintclient.client.render.PixelFace.BY_SIZE);
		try {
			drawMap(c, s, x, y, size, unit, hud);
		} finally {
			com.flintclient.client.render.PixelFace.end(font);
		}
	}

	private static void drawMap(Canvas c, ModuleState s, int x, int y, int size, float unit, boolean hud) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		MapWorld world = Mc263Maps.surface();
		if (player == null || level == null || world == null) {
			drawSample(c, s, x, y, size, unit);
			return;
		}
		GuiGraphicsExtractor g = c.frame().graphics();
		int gs = c.guiScale();
		boolean round = s.option(Minimap.SHAPE) == 0;
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		double px = Mth.lerp(partial, player.xo, player.getX());
		double pz = Mth.lerp(partial, player.zo, player.getZ());
		double py = Mth.lerp(partial, player.yo, player.getY());
		float yaw = Mth.lerp(partial, player.yRotO, player.getYRot());
		boolean rotate = s.bool(Minimap.ROTATE) && !northUp;
		float angle = rotate ? (float) (Math.PI - Math.toRadians(yaw)) : 0;
		float ppb = Mc263Maps.minimapZoom() * unit;
		float cx = x + size / 2.0F;
		float cy = y + size / 2.0F;
		float radius = size / 2.0F;
		VIEW.at(px, pz, cx, cy, angle, ppb);
		if (round) {
			VIEW.circle(radius);
		} else {
			VIEW.rect(x, y, x + size, y + size);
		}
		Mc263MapDraw.scissor(g, VIEW, x, y, x + size, y + size, gs);
		if (VIEW.hidden) {
			return;
		}
		int opacity = s.integer(Minimap.OPACITY);
		float alpha = opacity / 100.0F;
		int corner = round ? size / 2 : Math.max(2, Math.round(unit * 3));
		c.roundRectCorners(x, y, size, size, corner, Palette.withAlpha(0xFF0C0D0F, Math.max(alpha, 0.6F)));
		MapWorld cave = Mc263Maps.caveLayer();
		int light = 255;
		if (cave == null && s.bool(Minimap.LIGHTING) && level.dimensionType().hasSkyLight()) {
			light = Math.round(255 * (1 - level.getSkyDarken() / 11.0F * 0.45F));
		}
		int tint = Math.round(alpha * 255) << 24 | light << 16 | light << 8 | light;
		Mc263MapTiles.budget(hud ? 1_500_000L : 600_000L);
		Mc263MapDraw.terrain(g, VIEW, cave != null ? cave : world, cave != null ? 1 : 0, tint, true);
		Mc263MapTiles.evict();
		if (s.bool(Minimap.SLIME_CHUNKS)) {
			slimeChunks(g, level);
		}
		if (s.bool(Minimap.CHUNK_GRID)) {
			grid(g, unit, 16);
		}
		if (s.bool(Minimap.WAYPOINTS)) {
			waypoints(c, s, unit, round, x, y, size);
		}
		if (s.bool(Minimap.RADAR)) {
			radar(c, s, level, player, py, partial, unit, round, x, y, size);
		}
		arrow(g, s, player, unit, yaw, rotate, cx, cy);
		frame(c, s, unit, round, x, y, size);
		compass(c, s, unit, round, cx, cy, radius, size);
		if (cave != null) {
			TextLine tag = label("CAVE", small(c));
			int w = c.width(tag);
			int ty = y + size - Math.round(unit * 9);
			c.roundRectCorners(Math.round(cx - w / 2.0F) - Math.round(unit * 3), ty - Math.round(unit * 4), w + Math.round(unit * 6),
				Math.round(unit * 8), Math.round(unit * 4), 0xB0000000);
			c.textCentered(tag, Math.round(cx), c.baselineFor(tag, ty), 0xFFFFD9A0);
		}
	}

	/** Whether screen point ({@code px}, {@code py}) is inside the map, {@code margin} px in from its edge. */
	private static boolean inside(boolean round, int x, int y, int size, float px, float py, float margin) {
		if (round) {
			float dx = px - (x + size / 2.0F);
			float dy = py - (y + size / 2.0F);
			float r = size / 2.0F - margin;
			return dx * dx + dy * dy <= r * r;
		}
		return px >= x + margin && px <= x + size - margin && py >= y + margin && py <= y + size - margin;
	}

	/** Moves ({@code AT}) onto the map's edge, {@code margin} px in, toward it from the centre. */
	private static void toEdge(boolean round, int x, int y, int size, float margin) {
		float cx = x + size / 2.0F;
		float cy = y + size / 2.0F;
		float dx = AT[0] - cx;
		float dy = AT[1] - cy;
		float half = size / 2.0F - margin;
		float t = round ? half / Math.max(1e-3F, (float) Math.sqrt(dx * dx + dy * dy)) : half / Math.max(1e-3F, Math.max(Math.abs(dx), Math.abs(dy)));
		AT[0] = cx + dx * t;
		AT[1] = cy + dy * t;
	}

	private static void grid(GuiGraphicsExtractor g, float unit, int step) {
		// The world area under the map, then a line on every chunk border in it.
		double minX = Double.MAX_VALUE;
		double minZ = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		double maxZ = -Double.MAX_VALUE;
		for (int i = 0; i < VIEW.clipN; i++) {
			VIEW.toWorld(VIEW.clip[2 * i], VIEW.clip[2 * i + 1], WORLD);
			minX = Math.min(minX, WORLD[0]);
			minZ = Math.min(minZ, WORLD[1]);
			maxX = Math.max(maxX, WORLD[0]);
			maxZ = Math.max(maxZ, WORLD[1]);
		}
		if ((maxX - minX) / step > 64) {
			return;
		}
		float half = Math.max(0.5F, unit * 0.25F) / VIEW.scale;
		int color = 0x40FFFFFF;
		for (int lx = (int) Math.floor(minX / step) * step; lx <= maxX; lx += step) {
			line(g, lx - half, minZ, lx + half, maxZ, color);
		}
		for (int lz = (int) Math.floor(minZ / step) * step; lz <= maxZ; lz += step) {
			line(g, minX, lz - half, maxX, lz + half, color);
		}
	}

	/** A world rectangle drawn on the map, cut to its outline. */
	private static void line(GuiGraphicsExtractor g, double x0, double z0, double x1, double z1, int argb) {
		VIEW.toScreen(x0, z0, AT);
		SHAPE[0] = AT[0];
		SHAPE[1] = AT[1];
		VIEW.toScreen(x0, z1, AT);
		SHAPE[2] = AT[0];
		SHAPE[3] = AT[1];
		VIEW.toScreen(x1, z1, AT);
		SHAPE[4] = AT[0];
		SHAPE[5] = AT[1];
		VIEW.toScreen(x1, z0, AT);
		SHAPE[6] = AT[0];
		SHAPE[7] = AT[1];
		Mc263MapDraw.clipped(g, VIEW, SHAPE, 4, argb);
	}

	private static void slimeChunks(GuiGraphicsExtractor g, ClientLevel level) {
		Long seed = Mc263MapWorldInfo.seed();
		if (seed == null || !level.dimension().identifier().toString().equals("minecraft:overworld")) {
			return;
		}
		double reach = 0;
		for (int i = 0; i < VIEW.clipN; i++) {
			VIEW.toWorld(VIEW.clip[2 * i], VIEW.clip[2 * i + 1], WORLD);
			reach = Math.max(reach, Math.max(Math.abs(WORLD[0] - VIEW.centerX()), Math.abs(WORLD[1] - VIEW.centerZ())));
		}
		int ccx = Mth.floor(VIEW.centerX()) >> 4;
		int ccz = Mth.floor(VIEW.centerZ()) >> 4;
		int r = Math.min(24, (int) Math.ceil(reach / 16) + 1);
		for (int dz = -r; dz <= r; dz++) {
			for (int dx = -r; dx <= r; dx++) {
				if (Mc263MapWorldInfo.slime(seed, ccx + dx, ccz + dz)) {
					double x0 = (ccx + dx) << 4;
					double z0 = (ccz + dz) << 4;
					line(g, x0, z0, x0 + 16, z0 + 16, 0x4844D04A);
				}
			}
		}
	}

	private static void waypoints(Canvas c, ModuleState s, float unit, boolean round, int x, int y, int size) {
		String dimension = Mc263Features.dimension();
		if (dimension == null || !Mc263Features.markersShown() && Mods.on(WAYPOINTS)) {
			return;
		}
		boolean allSets = Mods.state(WAYPOINTS).bool(UtilityMods.Waypoints.ALL_SETS);
		boolean names = s.bool(Minimap.WAYPOINT_NAMES);
		boolean edge = s.bool(Minimap.EDGE_WAYPOINTS);
		float scale = s.decimal(Minimap.WAYPOINT_SCALE);
		int placed = 0;
		List<Waypoint> all = Features.waypoints().all();
		for (Waypoint wp : all) {
			if (!wp.dimension().equals(dimension) || !Features.waypoints().shown(wp, allSets)) {
				continue;
			}
			VIEW.toScreen(wp.x() + 0.5, wp.z() + 0.5, AT);
			int box = Math.max(5, Math.round(unit * 7 * scale));
			boolean in = inside(round, x, y, size, AT[0], AT[1], box * 0.5F);
			if (!in) {
				if (!edge) {
					continue;
				}
				box = Math.max(4, box * 3 / 4);
				toEdge(round, x, y, size, box * 0.5F + 1);
			}
			marker(c, wp, AT[0], AT[1], box, in ? 1 : 0.85F);
			if (names && in) {
				// A name that would cover one already drawn waits (as on the world map) until you zoom in.
				TextLine name = label(wp.name(), small(c));
				int w = c.width(name);
				int ny = Math.round(AT[1]) + box / 2 + Math.round(unit * 2);
				int x0 = Math.round(AT[0] - w / 2.0F) - 2;
				int y0 = ny - 1;
				int x1 = x0 + w + 4;
				int y1 = y0 + Math.round(unit * 6) + 2;
				boolean free = true;
				for (int i = 0; i < placed && free; i++) {
					free = x0 >= PLACED[4 * i + 2] || PLACED[4 * i] >= x1 || y0 >= PLACED[4 * i + 3] || PLACED[4 * i + 1] >= y1;
				}
				if (!free || placed >= PLACED.length / 4) {
					continue;
				}
				PLACED[4 * placed] = x0;
				PLACED[4 * placed + 1] = y0;
				PLACED[4 * placed + 2] = x1;
				PLACED[4 * placed + 3] = y1;
				placed++;
				c.roundRectCorners(x0, y0, x1 - x0, y1 - y0, 2, 0x90000000);
				c.textCentered(name, Math.round(AT[0]), c.baselineFor(name, ny + Math.round(unit * 3)), 0xFFFFFFFF);
			}
		}
	}

	/** The minimap's waypoint names drawn this frame (boxes: x0, y0, x1, y1). */
	private static final int[] PLACED = new int[4 * 64];

	/** A waypoint's marker: its badge ({@link WaypointBadge}), the same everywhere. Used by the world map too. */
	static void marker(Canvas c, Waypoint wp, float px, float py, int box, float alpha) {
		WaypointBadge.draw(c, wp, px, py, box, alpha);
	}

	private static void radar(Canvas c, ModuleState s, ClientLevel level, LocalPlayer self, double py, float partial, float unit, boolean round,
		int x, int y, int size) {
		entities(c, s, VIEW, level, self, py, partial, unit, round, x, y, x + size, y + size, true);
	}

	/** This frame's entities to show, and how far each is from you (to draw the far ones first). */
	private static final java.util.ArrayList<Entity> SHOWN = new java.util.ArrayList<>();
	private static double selfX;
	private static double selfZ;
	private static final java.util.Comparator<Entity> FAR_FIRST = (a, b) -> {
		// Mobs before players (players on top), then the farthest first: the nearest heads sit on top of the clump.
		boolean pa = a instanceof AbstractClientPlayer;
		boolean pb = b instanceof AbstractClientPlayer;
		if (pa != pb) {
			return pa ? 1 : -1;
		}
		return Double.compare(dist(b), dist(a));
	};

	private static double dist(Entity e) {
		double dx = e.getX() - selfX;
		double dz = e.getZ() - selfZ;
		return dx * dx + dz * dz;
	}

	/**
	 * The entity radar, as Xaero's: per group (players, hostile mobs, friendly mobs, items, other entities) shown or not, as
	 * heads or as dots in the group's colour, names as set; fainter far above or below, left out past the height range. A
	 * head is the mob's own (EntityIcons), a player's is their face; each with a thin black outline and nothing behind it.
	 * The nearest are drawn last, so they sit on top. Shared by the minimap and the world map ({@code view}; a circle or the
	 * box [{@code x0}, {@code x1}) × [{@code y0}, {@code y1}) is where things show; {@code players}: other players too).
	 */
	static void entities(Canvas c, ModuleState s, Mc263MapDraw.View view, ClientLevel level, LocalPlayer self, double py, float partial,
		float unit, boolean round, float x0, float y0, float x1, float y1, boolean players) {
		boolean showPlayers = players && s.bool(Minimap.PLAYERS);
		boolean playerHeads = s.bool(Minimap.PLAYER_HEADS);
		boolean hostile = s.bool(Minimap.HOSTILE);
		boolean hostileHeads = s.option(Minimap.HOSTILE_ICONS) == 0;
		boolean passive = s.bool(Minimap.PASSIVE);
		boolean passiveHeads = s.option(Minimap.PASSIVE_ICONS) == 0;
		boolean items = s.bool(Minimap.ITEMS);
		boolean others = s.bool(Minimap.OTHERS);
		int names = s.option(Minimap.NAMES);
		boolean fade = s.bool(Minimap.HEIGHT_FADE);
		int range = s.integer(Minimap.HEIGHT_RANGE);
		float pixel = unit * s.decimal(Minimap.ICON_SCALE);
		float dot = Math.max(1.5F, unit * 1.7F * s.decimal(Minimap.DOT_SIZE));
		GuiGraphicsExtractor g = c.frame().graphics();
		if (hostile && hostileHeads || passive && passiveHeads) {
			EntityIcons.beginFrame();
		}
		selfX = self.getX();
		selfZ = self.getZ();
		SHOWN.clear();
		for (Entity e : level.entitiesForRendering()) {
			if (e == self || e.isInvisibleTo(self)) {
				continue;
			}
			boolean show = e instanceof AbstractClientPlayer ? showPlayers : e instanceof ItemEntity ? items : e instanceof Enemy ? hostile
				: e instanceof Mob ? passive : others;
			if (show && (range <= 0 || Math.abs(e.getY() - py) <= range)) {
				SHOWN.add(e);
			}
		}
		SHOWN.sort(FAR_FIRST);
		int iconsLeft = MAX_ICONS;
		float cx = (x0 + x1) / 2;
		float cy = (y0 + y1) / 2;
		float radius = Math.min(x1 - x0, y1 - y0) / 2;
		for (Entity e : SHOWN) {
			boolean player = e instanceof AbstractClientPlayer;
			boolean living = e instanceof net.minecraft.world.entity.LivingEntity;
			int color;
			boolean head;
			if (player) {
				color = s.rgb(Minimap.PLAYER_COLOR);
				head = playerHeads;
			} else if (e instanceof ItemEntity) {
				color = s.rgb(Minimap.ITEM_COLOR);
				head = false;
			} else if (e instanceof Enemy) {
				color = s.rgb(Minimap.HOSTILE_COLOR);
				head = hostileHeads && living;
			} else if (e instanceof Mob) {
				color = s.rgb(Minimap.PASSIVE_COLOR);
				head = passiveHeads && living;
			} else {
				color = s.rgb(Minimap.OTHER_COLOR);
				head = false;
			}
			double ey = Mth.lerp(partial, e.yo, e.getY());
			view.toScreen(Mth.lerp(partial, e.xo, e.getX()), Mth.lerp(partial, e.zo, e.getZ()), AT);
			float margin = head ? pixel * 3 : dot;
			boolean in = round ? (AT[0] - cx) * (AT[0] - cx) + (AT[1] - cy) * (AT[1] - cy) <= (radius - margin) * (radius - margin)
				: AT[0] >= x0 + margin && AT[0] <= x1 - margin && AT[1] >= y0 + margin && AT[1] <= y1 - margin;
			if (!in) {
				continue;
			}
			float a = fade && Math.abs(ey - py) > 8 ? 0.45F : 1;
			float below;
			if (player && head) {
				// The face (and its hat layer) eight model pixels across, with Xaero's thin black outline.
				int side = Math.max(4, Math.round(8 * pixel));
				int hx = Math.round(AT[0] - side / 2.0F);
				int hy = Math.round(AT[1] - side / 2.0F);
				int edge = Math.max(1, Math.round(unit / 2));
				c.fill(hx - edge, hy - edge, hx + side + edge, hy + side + edge, Palette.withAlpha(0xFF000000, a));
				PlayerFaceExtractor.extractRenderState(g, ((AbstractClientPlayer) e).getSkin(), hx, hy, side);
				below = side / 2.0F + edge;
			} else if (head && iconsLeft > 0 && EntityIcons.draw(g, e, partial, AT[0], AT[1], pixel, a)) {
				iconsLeft--;
				below = 4.5F * pixel;
			} else {
				int d = Math.round(2 * dot);
				int dx = Math.round(AT[0] - dot);
				int dy = Math.round(AT[1] - dot);
				c.roundRect(dx - 1, dy - 1, d + 2, d + 2, d / 2 + 1, Palette.withAlpha(0xC0000000, a));
				c.roundRect(dx, dy, d, d, d / 2, Palette.withAlpha(0xFF000000 | color, a));
				below = dot + 1;
			}
			if (names == 2 || names == 1 && player) {
				TextLine name = label(e.getName().getString(), small(c));
				int w = c.width(name);
				int ny = Math.round(AT[1] + below + unit);
				int lineH = Math.round(unit * 6);
				c.roundRectCorners(Math.round(AT[0] - w / 2.0F) - 2, ny - 1, w + 4, lineH + 2, 2, Palette.withAlpha(0x90000000, a));
				c.textCentered(name, Math.round(AT[0]), c.baselineFor(name, ny + lineH / 2), Palette.withAlpha(0xFFFFFFFF, a));
			}
		}
		SHOWN.clear();
	}

	/**
	 * Your arrow at the centre (Xaero's, {@link MapArrow}): straight up on a map that turns with you, else the way you face
	 * ({@code yaw}: Minecraft's, 0 south).
	 */
	private static void arrow(GuiGraphicsExtractor g, ModuleState s, @Nullable LocalPlayer player, float unit, float yaw, boolean rotate, float cx,
		float cy) {
		float fx = 0;
		float fy = -1;
		if (!rotate) {
			// Facing on screen (north up): Minecraft's yaw 0 faces +z (down), 90 faces -x (left).
			double rad = Math.toRadians(yaw);
			fx = (float) -Math.sin(rad);
			fy = (float) Math.cos(rad);
		}
		MapArrow.minimap(g, s, player, cx, cy, fx, fy, unit);
	}

	private static void frame(Canvas c, ModuleState s, float unit, boolean round, int x, int y, int size) {
		int style = s.option(Minimap.FRAME);
		if (style == 2) {
			return;
		}
		if (style == 3) {
			// Classic (Xaero's): a dark line in the frame colour, a light grey one inside it, and a faint shadow outside.
			int line = Math.max(1, Math.round(unit * 0.75F));
			int corner = round ? size / 2 : 0;
			c.ring(x - 2 * line, y - 2 * line, size + 4 * line, size + 4 * line, round ? corner + 2 * line : 0, line, 0x40000000);
			c.ring(x - line, y - line, size + 2 * line, size + 2 * line, round ? corner + line : 0, line, 0xFF000000 | s.rgb(Minimap.FRAME_COLOR));
			c.ring(x, y, size, size, corner, line, 0xFF9A9A9A);
			return;
		}
		int w = style == 1 ? Math.max(2, Math.round(unit * 3)) : Math.max(1, Math.round(unit * 1.5F));
		int color = 0xFF000000 | s.rgb(Minimap.FRAME_COLOR);
		int corner = round ? size / 2 : Math.max(2, Math.round(unit * 3));
		c.ring(x, y, size, size, corner, w, color);
		int inner = Math.max(1, Math.round(unit * 0.5F));
		c.ring(x + w, y + w, size - 2 * w, size - 2 * w, Math.max(0, corner - w), inner, 0x1EFFFFFF);
	}

	private static void compass(Canvas c, ModuleState s, float unit, boolean round, float cx, float cy, float radius, int size) {
		int where = s.option(Minimap.COMPASS);
		if (where == 0) {
			return;
		}
		int north = 0xFF000000 | s.rgb(Minimap.NORTH_COLOR);
		float in = where == 1 ? Math.max(1, Math.round(unit * 0.75F)) : unit * 6.5F;
		for (int i = 0; i < 4; i++) {
			// N (-z), E (+x), S (+z), W (-x).
			double wx = VIEW.centerX() + (i == 1 ? 100 : i == 3 ? -100 : 0);
			double wz = VIEW.centerZ() + (i == 0 ? -100 : i == 2 ? 100 : 0);
			VIEW.toScreen(wx, wz, AT);
			float dx = AT[0] - cx;
			float dy = AT[1] - cy;
			float len = Math.max(1e-3F, (float) Math.sqrt(dx * dx + dy * dy));
			float t = round ? (radius - in) / len : (radius - in) / Math.max(Math.abs(dx), Math.abs(dy));
			float px = cx + dx * t;
			float py = cy + dy * t;
			TextLine line = label(POINTS[i], small(c));
			int baseline = c.baselineFor(line, Math.round(py));
			if (s.option(Minimap.FRAME) == 3) {
				// Xaero's: the letters alone, with a dark shadow, North in its colour.
				int shift = Math.max(1, c.guiScale() - 1);
				c.textCentered(line, Math.round(px) + shift, baseline + shift, 0xC0000000);
				c.textCentered(line, Math.round(px), baseline, i == 0 ? north : 0xFFFFFFFF);
				continue;
			}
			int disc = Math.max(9, Math.round(unit * 9.5F));
			c.roundRect(Math.round(px - disc / 2.0F), Math.round(py - disc / 2.0F), disc, disc, disc / 2, 0xD0101012);
			c.textCentered(line, Math.round(px), baseline, i == 0 ? north : 0xFFE8E8E8);
		}
	}

	/** The still sample shown without a world (settings previews): a dark map with a grid, the compass and the arrow. */
	public static void drawSample(Canvas c, ModuleState s, int x, int y, int size, float unit) {
		// Its words (labels, compass letters, information lines) in Minecraft's font, as all of Flint's in-game text.
		int font = com.flintclient.client.render.PixelFace.begin(com.flintclient.client.render.PixelFace.BY_SIZE);
		try {
			drawSampleMap(c, s, x, y, size, unit);
		} finally {
			com.flintclient.client.render.PixelFace.end(font);
		}
	}

	private static void drawSampleMap(Canvas c, ModuleState s, int x, int y, int size, float unit) {
		boolean round = s.option(Minimap.SHAPE) == 0;
		int corner = round ? size / 2 : Math.max(2, Math.round(unit * 3));
		c.roundRectCorners(x, y, size, size, corner, 0xFF23331F);
		int inner = Math.round(size * 0.18F);
		c.roundRectCorners(x + inner, y + inner * 2, size / 3, size / 4, Math.round(unit * 6), 0xFF2F5C8C);
		c.roundRectCorners(x + size / 2, y + inner, size / 3, size / 3, Math.round(unit * 8), 0xFF3F6B33);
		VIEW.at(0, 0, x + size / 2.0F, y + size / 2.0F, 0, unit);
		MapArrow.minimap(c.frame().graphics(), s, null, x + size / 2.0F, y + size / 2.0F, 0, -1, unit);
		frame(c, s, unit, round, x, y, size);
		compass(c, s, unit, round, x + size / 2.0F, y + size / 2.0F, size / 2.0F, size);
	}

	// ---------- information ----------

	/** The lines under the map, as set: worked out again only when something in them changed. */
	public static String[] info(ModuleState s) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null) {
			return sampleInfo(s);
		}
		int flags = (s.bool(Minimap.COORDINATES) ? 1 : 0) | (s.bool(Minimap.BIOME) ? 2 : 0) | (s.bool(Minimap.FACING) ? 4 : 0)
			| (s.bool(Minimap.OTHER_DIMENSION) ? 8 : 0) | (s.bool(Minimap.TIME) ? 16 : 0);
		// A height chosen for the map (Xaero's manual cave mode status): always said, so the map is never mistaken for the surface.
		int layer = s.integer(Minimap.LAYER);
		int bx = Mth.floor(player.getX());
		int by = Mth.floor(player.getY());
		int bz = Mth.floor(player.getZ());
		int facing = Math.floorMod(Math.round(player.getYRot() / 45.0F), 8);
		long minute = (flags & 16) != 0 ? Mc263GameData.dayTicks() / 17 : 0;
		long key = (((long) bx * 31 + by) * 31 + bz) * 31 + facing + minute * 1_000_003L + layer * 7_919_000_000L;
		if (key == infoKey && flags == infoFlags) {
			return info;
		}
		infoKey = key;
		infoFlags = flags;
		java.util.ArrayList<String> out = new java.util.ArrayList<>(5);
		if ((flags & 1) != 0) {
			out.add(bx + ", " + by + ", " + bz);
		}
		if ((flags & 2) != 0) {
			String biome = Mc263HudData.biome(player);
			if (biome != null) {
				out.add(biome);
			}
		}
		if ((flags & 4) != 0) {
			out.add(FACING[facing]);
		}
		if ((flags & 8) != 0) {
			String dim = Mc263Features.dimension();
			if ("minecraft:overworld".equals(dim)) {
				out.add("Nether " + Math.floorDiv(bx, 8) + ", " + Math.floorDiv(bz, 8));
			} else if ("minecraft:the_nether".equals(dim)) {
				out.add("Overworld " + bx * 8 + ", " + bz * 8);
			}
		}
		if ((flags & 16) != 0) {
			long ticks = Mc263GameData.dayTicks();
			long ofDay = Math.floorMod(ticks + 6000, 24000L);
			int hours = (int) (ofDay / 1000);
			int minutes = (int) (ofDay % 1000 * 60 / 1000);
			out.add("Day " + (ticks / 24000L + 1) + " · " + String.format(Locale.ROOT, "%02d:%02d", hours, minutes));
		}
		if (layer != Minimap.LAYER_AUTO && mc.level != null) {
			out.add("Layer Y " + Mc263Maps.clampY(mc.level, layer));
		}
		info = out.toArray(new String[0]);
		summary = (flags & 1) != 0 ? bx + ", " + by + ", " + bz : out.isEmpty() ? "On" : out.get(0);
		return info;
	}

	private static final String[] FACING = {"Facing south (+Z)", "Facing south-west", "Facing west (-X)", "Facing north-west",
		"Facing north (-Z)", "Facing north-east", "Facing east (+X)", "Facing south-east"};

	/** Sample lines for previews. */
	public static String[] sampleInfo(ModuleState s) {
		java.util.ArrayList<String> out = new java.util.ArrayList<>(3);
		if (s.bool(Minimap.COORDINATES)) {
			out.add("128, 72, -340");
		}
		if (s.bool(Minimap.BIOME)) {
			out.add("Plains");
		}
		if (s.bool(Minimap.FACING)) {
			out.add("Facing north (-Z)");
		}
		return out.toArray(new String[0]);
	}

	/** The module list's live summary (your coordinates), or null outside a world. */
	public static @Nullable String summary() {
		return ready() ? summary : null;
	}

	/** For the world map and tests: the name of the dimension a waypoint is in. */
	static String dimensionName(String id) {
		return WaypointShare.dimensionName(id);
	}

	/** The packed map column under world ({@code x}, {@code z}) of the surface map, or 0 when unknown. */
	static int column(MapWorld world, int x, int z) {
		MapRegion r = world.region(x >> MapRegion.BITS, z >> MapRegion.BITS);
		return r == null ? 0 : r.color(x & MapRegion.SIZE - 1, z & MapRegion.SIZE - 1);
	}
}
