package com.flintclient.client.control.platform;

import com.flintclient.client.control.feature.Features;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.feature.Waypoint;
import com.flintclient.client.control.feature.WaypointShare;
import com.flintclient.client.control.feature.WaypointStore;
import com.flintclient.client.control.feature.map.MapRegion;
import com.flintclient.client.control.feature.map.MapWorld;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.MapMods.Minimap;
import com.flintclient.client.control.model.mods.MapMods.WorldMap;
import com.flintclient.client.control.model.mods.UtilityMods;
import com.flintclient.client.platform.Frame;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.FontFace;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.render.WaypointBadge;
import com.flintclient.client.ui.FlintFrame;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * Flint's World Map: everywhere you have explored with the maps on, full screen.
 *
 * <ul>
 * <li>Drag to move, scroll to zoom (smoothly, toward the mouse), arrows or WASD to move, + and - to zoom, Space to come back
 * to you, the map's key or Esc to close. The view can be kept between openings.</li>
 * <li>Dimension tabs show the other dimensions you've mapped in this world or server.</li>
 * <li>You (an arrow), the other players you can see (their faces) and your waypoints are on it. Right-click a place: make a
 * waypoint there, teleport there (where commands let you), copy or share its coordinates. Right-click a waypoint: edit,
 * teleport, share in chat, hide or delete it.</li>
 * <li>The side panel lists the waypoints of the dimension shown in its active set, switches and manages sets, and adds a
 * waypoint where you stand.</li>
 * <li>Under the mouse: its coordinates, height and biome, from the map. A scale bar, bottom right.</li>
 * </ul>
 * Drawn with Flint's canvas in physical pixels; textures fill in nearest first while you look (a coarser one shows until a
 * region's is ready). Render thread.
 */
public final class WorldMapScreen extends Screen {
	private static final int WAYPOINT_MOD = Mods.index(UtilityMods.Waypoints.ID);
	private static final int MINIMAP_MOD = Mods.index(Minimap.ID);
	/** At most this many mob heads a frame on the map; past it, dots. */
	private static final int MAX_MOB_ICONS = 128;
	// The Right Shift menu's colours (its MenuStyle): near-black surfaces, grey text, Flint orange.
	private static final int BG = 0xFF080808;
	private static final int PANEL = 0xF20F0F0F;
	private static final int EDGE = 0xFF1F1F1F;
	private static final int TEXT = 0xFFF0F0F0;
	private static final int MUTED = 0xFF9A9A9A;
	private static final int FAINT = 0xFF6E6E6E;
	private static final int ACCENT = 0xFFFF8139;
	private static final int HOVER = 0x16FFFFFF;
	private static final int DANGER = 0xFFFF5A5A;
	/** Titles: Minecraft's font a step larger than the rest. */
	private FontFace title() {
		return com.flintclient.client.render.PixelFace.fixed(Math.max(2, gs));
	}

	private static final FontFace BODY = FlintFonts.UI_BOLD;
	private static final FontFace SMALL = FlintFonts.SMALL_BOLD;
	private static final int[] PALETTE = {0xFF8139, 0xE53935, 0xFF5C8D, 0xAB47BC, 0x7E57C2, 0x3F51B5, 0x1E88E5, 0x00ACC1, 0x26A69A, 0x43A047,
		0x9CCC65, 0xFDD835, 0xFFB300, 0x8D6E63, 0xBDBDBD, 0xFFFFFF};
	private static final float MIN_ZOOM = 1 / 32.0F;
	private static final float MAX_ZOOM = 12;

	private static boolean remembered;
	private static double rememberedX;
	private static double rememberedZ;
	private static float rememberedZoom = 1;
	private static @Nullable String rememberedDimension;

	private final Mc263MapDraw.View view = new Mc263MapDraw.View();
	private final List<Hit> hits = new ArrayList<>();
	private final List<Hit> lastHits = new ArrayList<>();
	private final float[] at = new float[2];
	/** This frame's waypoints on screen, and the names placed so far (their boxes, in pixels). */
	private final List<Waypoint> onScreen = new ArrayList<>();
	private final List<int[]> labels = new ArrayList<>();
	private final List<Waypoint> labelled = new ArrayList<>();
	private final double[] world = new double[2];
	private double cx;
	private double cz;
	private float zoom = 1;
	private float zoomTarget = 1;
	private float anchorX = Float.NaN;
	private float anchorY;
	private String dimension = "minecraft:overworld";
	private @Nullable MapWorld other;
	private final List<String> dimensions = new ArrayList<>();
	private int gs = 1;
	private int mousePxX;
	private int mousePxY;
	private boolean dragging;
	private boolean dragMoved;
	private double dragX;
	private double dragY;
	private boolean panel = true;
	/** Another dimension's layer at the height shown (read only, from its folder), and its 16-block band. */
	private @Nullable MapWorld otherLayer;
	private int otherLayerBand = Integer.MIN_VALUE;
	/** The layer slider: being dragged, and the value under the mouse until it's let go (Integer.MIN_VALUE: none). */
	private boolean layerDragging;
	private int layerPending = Integer.MIN_VALUE;
	/** The layer slider as last drawn (pixels): its card, and where Auto, Surface, the top and the bottom of the world are. */
	private int layerX0;
	private int layerX1 = -1;
	private int layerY0;
	private int layerY1;
	private int layerAutoY;
	private int layerSurfaceY;
	private int layerTopY;
	private int layerBottomY;
	/** The handle's drawn place (eased toward the height shown), how lit the slider is under the pointer, and how shown the handle is. */
	private float layerThumb = Float.NaN;
	private float hotLayers;
	private float layerShown;
	private float listScroll;
	private int listTop;
	private int listBottom;
	private float listContent;
	private @Nullable Menu menu;
	private @Nullable Editor editor;
	private @Nullable Waypoint confirmDelete;
	private long confirmAt;
	private @Nullable String confirmSet;
	private long lastFrame;
	private boolean initDone;

	/** A clickable area drawn this frame (pixels), with what a left and a right click do, and its tooltip. */
	private record Hit(int x0, int y0, int x1, int y1, @Nullable Runnable left, @Nullable Runnable right, @Nullable String tip) {
		boolean contains(int x, int y) {
			return x >= x0 && x < x1 && y >= y0 && y < y1;
		}
	}

	/** A right-click menu: where it is, and its items. */
	private record Menu(int x, int y, String title, List<Item> items) {
	}

	private record Item(Icon icon, String label, Runnable action, boolean danger) {
	}

	public WorldMapScreen() {
		super(Component.literal("World Map"));
	}

	/** The world or dimension changed: a remembered view no longer applies. */
	static void worldChanged() {
		remembered = false;
	}

	private static ModuleState settings() {
		return Mods.state(Mc263Maps.WORLD_MAP);
	}

	@Override
	protected void init() {
		if (initDone) {
			if (editor != null) {
				editor.place();
			}
			return;
		}
		initDone = true;
		LocalPlayer player = minecraft.player;
		String here = Mc263Maps.dimension();
		dimension = here != null ? here : "minecraft:overworld";
		if (remembered && settings().bool(WorldMap.REMEMBER) && rememberedDimension != null) {
			cx = rememberedX;
			cz = rememberedZ;
			zoom = zoomTarget = rememberedZoom;
			if (!rememberedDimension.equals(dimension)) {
				show(rememberedDimension);
			}
		} else if (player != null) {
			cx = player.getX();
			cz = player.getZ();
			zoom = zoomTarget = 1;
		}
		listDimensions();
	}

	/** The dimensions with a map in this world's folder, the one you're in first. */
	private void listDimensions() {
		dimensions.clear();
		String here = Mc263Maps.dimension();
		if (here != null) {
			dimensions.add(here);
		}
		Path folder = Mc263Maps.worldFolder();
		if (folder == null || !Files.isDirectory(folder)) {
			return;
		}
		for (String id : List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end")) {
			if (!dimensions.contains(id) && Files.isDirectory(folder.resolve(Mc263Maps.folderName(id)))) {
				dimensions.add(id);
			}
		}
	}

	/** Shows another dimension's map (read only), or the one you're in. */
	private void show(String id) {
		if (id.equals(dimension) && (other == null) == id.equals(Mc263Maps.dimension())) {
			return;
		}
		if (other != null) {
			Mc263MapTiles.release(other);
			other.close();
			other = null;
		}
		closeOtherLayer();
		dimension = id;
		if (!id.equals(Mc263Maps.dimension())) {
			Path folder = Mc263Maps.worldFolder();
			other = folder == null ? null : new MapWorld(folder.resolve(Mc263Maps.folderName(id)));
		}
		menu = null;
	}

	private void closeOtherLayer() {
		if (otherLayer != null) {
			Mc263MapTiles.release(otherLayer);
			otherLayer.close();
			otherLayer = null;
			otherLayerBand = Integer.MIN_VALUE;
		}
	}

	/**
	 * The map shown, by the layer slider: Auto (as Xaero's world map: the layer the minimap reads while it is in cave mode,
	 * else the surface), the surface, or the layer under a height (layers are kept per 16 blocks of height; the one you're in
	 * is read round you while it shows, another dimension's comes from its folder). Another dimension's surface otherwise.
	 */
	private @Nullable MapWorld map() {
		int layer = settings().integer(WorldMap.LAYER);
		if (other != null) {
			return isHeight(layer) ? otherLayer(layer) : other;
		}
		if (!dimension.equals(Mc263Maps.dimension())) {
			return null;
		}
		MapWorld cave = Mc263Maps.caveLayer();
		if (layer == WorldMap.LAYER_SURFACE) {
			return Mc263Maps.surface();
		}
		if (isHeight(layer)) {
			// Read from the next tick on (the maps' upkeep picks the height up from this screen).
			return cave != null && cave.caveTop() == clampLayer(layer) ? cave : null;
		}
		return cave != null ? cave : Mc263Maps.surface();
	}

	private static boolean isHeight(int layer) {
		return layer > WorldMap.LAYER_SURFACE;
	}

	/** The height {@code layer} reads from in the dimension shown (a height above its top starts at its top). */
	private int clampLayer(int layer) {
		int[] range = heights();
		return Math.clamp(layer, range[0], range[1]);
	}

	/** Another dimension's layer under {@code y}, read only from its folder (what was mapped there when you were in it). */
	private @Nullable MapWorld otherLayer(int y) {
		int top = clampLayer(y);
		int band = Math.floorDiv(top, 16);
		if (otherLayer == null || band != otherLayerBand) {
			closeOtherLayer();
			Path folder = Mc263Maps.worldFolder();
			if (folder == null) {
				return null;
			}
			otherLayer = new MapWorld(folder.resolve(Mc263Maps.folderName(dimension)).resolve("caves").resolve(Integer.toString(band)));
			otherLayer.maxFull(12);
			otherLayerBand = band;
		}
		return otherLayer.cave(top, Mods.state(MINIMAP_MOD).integer(Minimap.CAVE_DEPTH));
	}

	/** The lowest and highest block of the dimension shown, within the layer setting's range. */
	private int[] heights() {
		ClientLevel level = minecraft.level;
		int min;
		int max;
		if (viewingHere() && level != null) {
			min = Mc263Maps.bottomY(level);
			max = Mc263Maps.topY(level);
		} else if (dimension.equals("minecraft:the_nether") || dimension.equals("minecraft:the_end")) {
			min = 0;
			max = 255;
		} else {
			min = -64;
			max = 319;
		}
		return new int[] {Math.max(min, WorldMap.LAYER_SURFACE + 1), Math.min(max, WorldMap.LAYER.max())};
	}

	private boolean viewingHere() {
		return other == null && dimension.equals(Mc263Maps.dimension());
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// The map covers everything: no blur, no dim.
	}

	@Override
	public void onClose() {
		remembered = true;
		rememberedX = cx;
		rememberedZ = cz;
		rememberedZoom = zoomTarget;
		rememberedDimension = dimension;
		if (other != null) {
			Mc263MapTiles.release(other);
			other.close();
			other = null;
		}
		closeOtherLayer();
		Mc263Maps.worldMapLayer(Integer.MIN_VALUE);
		super.onClose();
	}

	@Override
	public void removed() {
		// However the screen goes (another screen opened over it, the world left), its height stops being read.
		Mc263Maps.worldMapLayer(Integer.MIN_VALUE);
		super.removed();
	}

	@Override
	public void tick() {
		if (other != null) {
			other.tick();
		}
		if (otherLayer != null) {
			otherLayer.tick();
		}
		if (confirmDelete != null && System.currentTimeMillis() - confirmAt > 3000) {
			confirmDelete = null;
		}
		if (confirmSet != null && System.currentTimeMillis() - confirmAt > 3000) {
			confirmSet = null;
		}
		if (editor == null && !dragging) {
			// Arrows and WASD move the view.
			float step = 12 / Math.max(zoom, 0.05F);
			double dx = 0;
			double dz = 0;
			if (held(com.mojang.blaze3d.platform.InputConstants.KEY_LEFT) || held(com.mojang.blaze3d.platform.InputConstants.KEY_A)) {
				dx -= step;
			}
			if (held(com.mojang.blaze3d.platform.InputConstants.KEY_RIGHT) || held(com.mojang.blaze3d.platform.InputConstants.KEY_D)) {
				dx += step;
			}
			if (held(com.mojang.blaze3d.platform.InputConstants.KEY_UP) || held(com.mojang.blaze3d.platform.InputConstants.KEY_W)) {
				dz -= step;
			}
			if (held(com.mojang.blaze3d.platform.InputConstants.KEY_DOWN) || held(com.mojang.blaze3d.platform.InputConstants.KEY_S)) {
				dz += step;
			}
			cx += dx;
			cz += dz;
		}
	}

	private static boolean held(int key) {
		return Mc263ControlPlatform.keyDown(key);
	}

	// ---------- drawing ----------

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		FlintFrame.begin(graphics);
		Canvas c = Canvas.begin(Frame.of(graphics));
		// Minecraft's own font, as the Right Shift menu: each face at its nearest whole pixel scale.
		int fontMode = com.flintclient.client.render.PixelFace.begin(Math.max(1, c.guiScale() - 1));
		try {
			gs = c.guiScale();
			mousePxX = mouseX * gs;
			mousePxY = mouseY * gs;
			animate();
			lastHits.clear();
			lastHits.addAll(hits);
			hits.clear();
			int w = width * gs;
			int h = height * gs;
			c.fill(0, 0, w, h, BG);
			int layer = settings().integer(WorldMap.LAYER);
			Mc263Maps.worldMapLayer(viewingHere() && isHeight(layer) ? layer : Integer.MIN_VALUE);
			drawMap(c, graphics, w, h);
			drawTop(c, w);
			drawLayers(c, h);
			if (panel) {
				drawPanel(c, graphics, w, h);
			}
			drawZoomButtons(c, w, h);
			drawCursorInfo(c, h);
			drawScale(c, w, h);
			if (menu != null) {
				graphics.nextStratum();
				drawMenu(c, menu);
			}
			if (editor != null) {
				graphics.nextStratum();
				editor.paint(c, w, h);
			}
			drawTooltip(c, graphics);
		} finally {
			com.flintclient.client.render.PixelFace.end(fontMode);
			c.end();
			FlintFrame.end();
		}
		if (editor != null) {
			// Its text boxes over its card.
			graphics.nextStratum();
			super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		}
	}

	private void animate() {
		long now = System.nanoTime();
		float dt = lastFrame == 0 ? 0.016F : Math.min(0.1F, (now - lastFrame) / 1e9F);
		lastFrame = now;
		if (Math.abs(zoom - zoomTarget) < zoomTarget * 0.002F) {
			zoom = zoomTarget;
			anchorX = Float.NaN;
			return;
		}
		float step = 1 - (float) Math.exp(-16 * dt);
		double wx = 0;
		double wz = 0;
		boolean anchored = !Float.isNaN(anchorX);
		if (anchored) {
			view.at(cx, cz, width * gs / 2.0F, height * gs / 2.0F, 0, zoom * gs).toWorld(anchorX, anchorY, world);
			wx = world[0];
			wz = world[1];
		}
		zoom = (float) Math.exp(Math.log(zoom) + (Math.log(zoomTarget) - Math.log(zoom)) * step);
		if (anchored) {
			// The point under the mouse stays under it.
			cx = wx - (anchorX - width * gs / 2.0F) / (zoom * gs);
			cz = wz - (anchorY - height * gs / 2.0F) / (zoom * gs);
		}
	}

	private void drawMap(Canvas c, GuiGraphicsExtractor g, int w, int h) {
		MapWorld map = map();
		view.at(cx, cz, w / 2.0F, h / 2.0F, 0, zoom * gs).rect(0, 0, w, h);
		Mc263MapDraw.scissor(g, view, 0, 0, w, h, gs);
		if (map == null) {
			centred(c, isHeight(settings().integer(WorldMap.LAYER)) && viewingHere() ? "Reading this layer…" : "Nothing mapped here yet", w / 2,
				h / 2, MUTED, BODY);
			return;
		}
		Mc263MapTiles.budget(6_000_000L);
		Mc263MapDraw.terrain(g, view, map, map.caveDepth() > 0 ? 1 : 0, 0xFFFFFFFF, true);
		Mc263MapTiles.evict();
		ModuleState s = settings();
		if (s.bool(WorldMap.GRID)) {
			grid(g);
		}
		if (map.listed() && map.explored().isEmpty()) {
			centred(c, map.caveDepth() > 0 ? viewingHere() ? "Nothing mapped at this height yet: it fills in round you" : "Nothing mapped at this "
				+ "height in this dimension yet" : "Explore with the map on and this fills in", w / 2, h / 2 + 12 * gs, MUTED, BODY);
		}
		if (s.bool(WorldMap.WAYPOINTS)) {
			drawWaypoints(c, s);
		}
		if (viewingHere()) {
			if (s.bool(WorldMap.MOBS)) {
				drawMobs(c, g);
			}
			if (s.bool(WorldMap.PLAYERS)) {
				drawPlayers(c, g);
			}
			LocalPlayer player = minecraft.player;
			if (player != null) {
				drawArrow(g, player, w, h);
			}
		}
	}

	/**
	 * You, as Xaero's world map shows you: its arrow (a screen pixel per arrow unit, more on tall screens), or, when you're
	 * off the screen, its flat chevron on the nearest edge (or corner) pointing your way. The minimap's arrow color.
	 */
	private void drawArrow(GuiGraphicsExtractor g, LocalPlayer player, int w, int h) {
		int shortSide = Math.min(minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight());
		float unit = shortSide <= 1100 ? 1 : shortSide / 1080.0F;
		int rgb = MapArrow.color(Mods.state(MINIMAP_MOD), player);
		float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		view.toScreen(Mth.lerp(partial, player.xo, player.getX()), Mth.lerp(partial, player.zo, player.getZ()), at);
		float margin = 14 * unit;
		boolean left = at[0] < margin;
		boolean right = at[0] > w - margin;
		boolean up = at[1] < margin;
		boolean down = at[1] > h - margin;
		if (!left && !right && !up && !down) {
			double rad = Math.toRadians(player.getViewYRot(partial));
			MapArrow.worldMap(g, at[0], at[1], (float) -Math.sin(rad), (float) Math.cos(rad), unit, rgb);
			return;
		}
		// On the edge toward you: straight out of a side, or diagonally out of a corner.
		float fx = left ? -1 : right ? 1 : 0;
		float fy = up ? -1 : down ? 1 : 0;
		float len = (float) Math.sqrt(fx * fx + fy * fy);
		MapArrow.far(g, Math.clamp(at[0], margin, w - margin), Math.clamp(at[1], margin, h - margin), fx / len, fy / len, unit, rgb);
	}

	private void grid(GuiGraphicsExtractor g) {
		int step = zoom >= 1.5F ? 16 : MapRegion.SIZE;
		view.toWorld(0, 0, world);
		double x0 = world[0];
		double z0 = world[1];
		view.toWorld(width * gs, height * gs, world);
		double x1 = world[0];
		double z1 = world[1];
		if ((x1 - x0) / step > 200) {
			return;
		}
		float[] pts = new float[8];
		float half = 0.5F / (zoom * gs);
		int color = step == 16 ? 0x22FFFFFF : 0x3AFFFFFF;
		for (double x = Math.floor(x0 / step) * step; x <= x1; x += step) {
			rect(g, pts, x - half, z0, x + half, z1, color);
		}
		for (double z = Math.floor(z0 / step) * step; z <= z1; z += step) {
			rect(g, pts, x0, z - half, x1, z + half, color);
		}
	}

	private void rect(GuiGraphicsExtractor g, float[] pts, double x0, double z0, double x1, double z1, int argb) {
		view.toScreen(x0, z0, at);
		pts[0] = at[0];
		pts[1] = at[1];
		view.toScreen(x0, z1, at);
		pts[2] = at[0];
		pts[3] = at[1];
		view.toScreen(x1, z1, at);
		pts[4] = at[0];
		pts[5] = at[1];
		view.toScreen(x1, z0, at);
		pts[6] = at[0];
		pts[7] = at[1];
		Mc263MapDraw.clipped(g, view, pts, 4, argb);
	}

	private @Nullable Waypoint hoveredWaypoint() {
		ModuleState s = settings();
		if (!s.bool(WorldMap.WAYPOINTS)) {
			return null;
		}
		Waypoint best = null;
		float bestD = (7 * gs) * (7 * gs);
		boolean allSets = Mods.state(WAYPOINT_MOD).bool(UtilityMods.Waypoints.ALL_SETS);
		for (Waypoint wp : Features.waypoints().all()) {
			if (!wp.dimension().equals(dimension) || !Features.waypoints().shown(wp, allSets)) {
				continue;
			}
			view.toScreen(wp.x() + 0.5, wp.z() + 0.5, at);
			float dx = at[0] - mousePxX;
			float dy = at[1] - mousePxY;
			if (dx * dx + dy * dy < bestD) {
				bestD = dx * dx + dy * dy;
				best = wp;
			}
		}
		return best;
	}

	private void drawWaypoints(Canvas c, ModuleState s) {
		boolean allSets = Mods.state(WAYPOINT_MOD).bool(UtilityMods.Waypoints.ALL_SETS);
		boolean always = s.option(WorldMap.WAYPOINT_NAMES) == 1;
		Waypoint hovered = menu == null && editor == null && !overPanel() ? hoveredWaypoint() : null;
		int box = Math.round(9 * gs);
		onScreen.clear();
		for (Waypoint wp : Features.waypoints().all()) {
			if (!wp.dimension().equals(dimension) || !Features.waypoints().shown(wp, allSets)) {
				continue;
			}
			view.toScreen(wp.x() + 0.5, wp.z() + 0.5, at);
			if (at[0] < -box || at[1] < -box || at[0] > width * gs + box || at[1] > height * gs + box) {
				continue;
			}
			onScreen.add(wp);
			if (wp != hovered) {
				Mc263Minimap.marker(c, wp, at[0], at[1], box, 1);
			}
		}
		if (hovered != null && onScreen.contains(hovered)) {
			view.toScreen(hovered.x() + 0.5, hovered.z() + 0.5, at);
			Mc263Minimap.marker(c, hovered, at[0], at[1], box + 2 * gs, 1);
		}
		// Names: the hovered one always; with Always, the rest nearest you first, each only where it covers no name
		// already drawn (close waypoints show one name until you zoom in and they part).
		labels.clear();
		if (hovered != null && onScreen.contains(hovered)) {
			nameBox(c, hovered, box, true);
		}
		if (always) {
			LocalPlayer player = minecraft.player;
			double px = player != null && viewingHere() ? player.getX() : cx;
			double pz = player != null && viewingHere() ? player.getZ() : cz;
			onScreen.sort(java.util.Comparator.comparingDouble(wp -> (wp.x() - px) * (wp.x() - px) + (wp.z() - pz) * (wp.z() - pz)));
			for (Waypoint wp : onScreen) {
				if (wp != hovered) {
					nameBox(c, wp, box, false);
				}
			}
		}
		for (int i = labels.size() - 1; i >= 0; i--) {
			// Drawn in reverse, so the hovered name (placed first) is on top.
			int[] l = labels.get(i);
			Waypoint wp = labelled.get(i);
			boolean hot = wp == hovered;
			TextLine name = Mc263Minimap.label(wp.name(), SMALL);
			c.roundRectCorners(l[0], l[1], l[2] - l[0], l[3] - l[1], 3 * gs, hot ? 0xE0000000 : 0xA0000000);
			c.text(name, l[0] + 3 * gs, c.baselineFor(name, l[1] + gs + Math.round(3.5F * gs)), hot ? TEXT : 0xFFE6E6E6);
		}
		labelled.clear();
	}

	/** Places {@code wp}'s name under its marker unless it would cover a name placed before ({@code force}: always). */
	private void nameBox(Canvas c, Waypoint wp, int box, boolean force) {
		view.toScreen(wp.x() + 0.5, wp.z() + 0.5, at);
		TextLine name = Mc263Minimap.label(wp.name(), SMALL);
		int tw = c.width(name);
		int x0 = Math.round(at[0] - tw / 2.0F) - 3 * gs;
		int y0 = Math.round(at[1]) + box / 2 + 2 * gs;
		int x1 = x0 + tw + 6 * gs;
		int y1 = y0 + 9 * gs;
		if (!force) {
			for (int[] l : labels) {
				if (x0 < l[2] + gs && l[0] < x1 + gs && y0 < l[3] + gs && l[1] < y1 + gs) {
					return;
				}
			}
		}
		labels.add(new int[] {x0, y0, x1, y1});
		labelled.add(wp);
	}

	/**
	 * The mobs and items around you, as the minimap's entity radar shows them ({@link Mc263Minimap#entities}): its groups,
	 * heads or dots per group, names, head size and height range. Players are drawn after, by {@link #drawPlayers}.
	 */
	private void drawMobs(Canvas c, GuiGraphicsExtractor g) {
		ClientLevel level = minecraft.level;
		LocalPlayer self = minecraft.player;
		if (level == null || self == null) {
			return;
		}
		ModuleState radar = Mods.state(MINIMAP_MOD);
		if (!radar.bool(Minimap.RADAR)) {
			return;
		}
		float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Mc263Minimap.entities(c, radar, view, level, self, Mth.lerp(partial, self.yo, self.getY()), partial, gs, false, 0, 0, width * gs, height * gs,
			false);
	}

	private void drawPlayers(Canvas c, GuiGraphicsExtractor g) {
		ClientLevel level = minecraft.level;
		LocalPlayer self = minecraft.player;
		if (level == null || self == null) {
			return;
		}
		int side = 8 * gs;
		for (AbstractClientPlayer p : level.players()) {
			if (p == self || p.isInvisibleTo(self)) {
				continue;
			}
			view.toScreen(p.getX(), p.getZ(), at);
			int x = Math.round(at[0] - side / 2.0F);
			int y = Math.round(at[1] - side / 2.0F);
			// Their face with Xaero's thin black outline, nothing behind it.
			int edge = Math.max(1, gs / 2);
			c.fill(x - edge, y - edge, x + side + edge, y + side + edge, 0xFF000000);
			PlayerFaceExtractor.extractRenderState(g, p.getSkin(), x, y, side);
			TextLine name = Mc263Minimap.label(p.getName().getString(), SMALL);
			int tw = c.width(name);
			int ny = y + side + 3 * gs;
			c.roundRectCorners(Math.round(at[0] - tw / 2.0F) - 3 * gs, ny - gs, tw + 6 * gs, 9 * gs, 3 * gs, 0xB0000000);
			c.textCentered(name, Math.round(at[0]), c.baselineFor(name, ny + Math.round(3.5F * gs)), TEXT);
		}
	}

	// ---------- chrome ----------

	private TextLine line(String text, FontFace face) {
		return Mc263Minimap.label(text, face);
	}

	private void centred(Canvas c, String text, int x, int y, int argb, FontFace face) {
		TextLine l = line(text, face);
		c.textCentered(l, x, c.baselineFor(l, y), argb);
	}

	private void card(Canvas c, int x, int y, int w, int h) {
		c.roundRectCorners(x, y, w, h, 6 * gs, PANEL);
		c.ring(x, y, w, h, 6 * gs, Math.max(1, gs / 2), EDGE);
	}

	private boolean hovering(int x0, int y0, int x1, int y1) {
		return menu == null && (editor == null) && mousePxX >= x0 && mousePxX < x1 && mousePxY >= y0 && mousePxY < y1;
	}

	/** An icon button {@code size} px square; returns its right edge. */
	private int iconButton(Canvas c, Icon icon, int x, int y, int size, String tip, Runnable action, boolean active) {
		boolean hot = hovering(x, y, x + size, y + size);
		// On a solid backing, so it reads over a bright map as well as in the panel.
		c.roundRectCorners(x, y, size, size, 5 * gs, PANEL);
		c.roundRectCorners(x, y, size, size, 5 * gs, active ? 0x33FF8139 : hot ? 0x26FFFFFF : 0x12FFFFFF);
		int iconSize = Math.round(size * 0.55F);
		c.icon(icon, x + (size - iconSize) / 2, y + (size - iconSize) / 2, iconSize, active ? ACCENT : hot ? TEXT : 0xFFD0D0D4);
		hits.add(new Hit(x, y, x + size, y + size, action, null, tip));
		return x + size;
	}

	private void drawTop(Canvas c, int w) {
		int pad = 8 * gs;
		int h = 22 * gs;
		// Title and dimension tabs.
		TextLine title = line("World Map", title());
		int x = pad;
		int tabsW = 0;
		for (String id : dimensions) {
			tabsW += c.width(line(WaypointShare.dimensionName(id), BODY)) + 16 * gs + 4 * gs;
		}
		int cardW = 10 * gs + 12 * gs + 6 * gs + c.width(title) + 12 * gs + tabsW + 4 * gs;
		card(c, x, pad, cardW, h);
		c.icon(Icon.GLOBE, x + 9 * gs, pad + (h - 12 * gs) / 2, 12 * gs, ACCENT);
		int tx = x + 27 * gs;
		c.text(title, tx, c.baselineFor(title, pad + h / 2), TEXT);
		tx += c.width(title) + 12 * gs;
		for (String id : dimensions) {
			TextLine name = line(WaypointShare.dimensionName(id), BODY);
			int tw = c.width(name) + 16 * gs;
			boolean on = id.equals(dimension);
			boolean hot = hovering(tx, pad + 3 * gs, tx + tw, pad + h - 3 * gs);
			c.roundRectCorners(tx, pad + 3 * gs, tw, h - 6 * gs, 4 * gs, on ? 0x33FF8139 : hot ? 0x1CFFFFFF : 0);
			c.text(name, tx + 8 * gs, c.baselineFor(name, pad + h / 2), on ? ACCENT : hot ? TEXT : MUTED);
			if (id.equals(Mc263Maps.dimension())) {
				c.roundRectCorners(tx + tw - 6 * gs, pad + 6 * gs, 3 * gs, 3 * gs, gs + gs / 2, on ? ACCENT : MUTED);
			}
			String target = id;
			hits.add(new Hit(tx, pad + 3 * gs, tx + tw, pad + h - 3 * gs, () -> show(target), null, null));
			tx += tw + 4 * gs;
		}
		// Buttons, top right.
		int bx = w - pad - h;
		iconButton(c, Icon.CLOSE, bx, pad, h, "Close (Esc)", this::onClose, false);
		bx -= h + 4 * gs;
		iconButton(c, Icon.COMPASS, bx, pad, h, "Back to you (Space)", this::centerOnPlayer, false);
		bx -= h + 4 * gs;
		iconButton(c, Icon.FLAG, bx, pad, h, panel ? "Hide waypoints" : "Show waypoints", () -> panel = !panel, panel);
	}

	// ---------- the layer slider ----------

	/** The layer shown: the one being dragged to, else the setting. */
	private int shownLayer() {
		return layerPending != Integer.MIN_VALUE ? layerPending : settings().integer(WorldMap.LAYER);
	}

	private static String layerName(int layer) {
		return layer == WorldMap.LAYER_AUTO ? "Auto" : layer == WorldMap.LAYER_SURFACE ? "Surface" : "Y " + layer;
	}

	/** Where a height sits on the slider's track (pixels). */
	private int layerPixel(int y, int[] range) {
		float k = range[1] == range[0] ? 0 : (range[1] - y) / (float) (range[1] - range[0]);
		return layerTopY + Math.round(Math.clamp(k, 0, 1) * (layerBottomY - layerTopY));
	}

	/** The slider's value at pixel row {@code py}: Auto and Surface at its top, then the world's heights, top to bottom. */
	private int layerAt(int py) {
		if (py < (layerAutoY + layerSurfaceY) / 2) {
			return WorldMap.LAYER_AUTO;
		}
		if (py < (layerSurfaceY + layerTopY) / 2) {
			return WorldMap.LAYER_SURFACE;
		}
		int[] range = heights();
		float k = Math.clamp((py - layerTopY) / (float) Math.max(1, layerBottomY - layerTopY), 0, 1);
		return Math.round(range[1] - k * (range[1] - range[0]));
	}

	private boolean overLayers(int px, int py) {
		return px >= layerX0 && px < layerX1 && py >= layerY0 && py < layerY1;
	}

	/** Sets the layer shown (the World Map's Map layer setting). */
	private void setLayer(int layer) {
		settings().set(WorldMap.LAYER, layer);
	}

	/**
	 * The layer {@code by} blocks up or down (Page Up / Page Down, the minimap's layer keys, the wheel over the slider): from
	 * Auto or the surface it starts where you stand (down) or stays (up); past the top of the world, Auto again.
	 */
	private void stepLayer(int by) {
		int[] range = heights();
		int now = settings().integer(WorldMap.LAYER);
		int next;
		if (!isHeight(now)) {
			if (by > 0) {
				return;
			}
			MapWorld cave = viewingHere() ? Mc263Maps.caveLayer() : null;
			next = cave != null ? cave.caveTop() + by : viewingHere() ? playerY() : range[1];
		} else {
			next = now + by;
		}
		setLayer(next > range[1] ? WorldMap.LAYER_AUTO : Math.max(range[0], next));
	}

	/**
	 * The layer slider, a compact card (176 tall at most) centred on the map's left edge (as Xaero's Cave Mode Top Y): Auto and Surface as two
	 * small chips at its top, then a rounded track from the world's top to its bottom that fills in the accent below the
	 * height shown (what is being looked under), quiet ticks every 64 blocks, a small mark where you stand, and a pill handle
	 * that glides to its place; the value (Y 64, Auto, Surface) in a chip at its foot. Drag it or scroll over it (Page Up and
	 * Page Down step 16).
	 */
	private void drawLayers(Canvas c, int h) {
		int cardW = 44 * gs;
		int cardH = Math.min(h - 80 * gs, 176 * gs);
		if (cardH < 130 * gs) {
			layerX1 = -1;
			return;
		}
		layerX0 = 8 * gs;
		layerX1 = layerX0 + cardW;
		layerY0 = Math.max(36 * gs, (h - cardH) / 2);
		layerY1 = layerY0 + cardH;
		int[] range = heights();
		int layer = shownLayer();
		boolean hot = hovering(layerX0, layerY0, layerX1, layerY1) || layerDragging;
		hotLayers += ((hot ? 1 : 0) - hotLayers) * Math.min(1, frameSeconds() * 12);
		card(c, layerX0, layerY0, cardW, cardH);
		if (hotLayers > 0.01F) {
			c.ring(layerX0, layerY0, cardW, cardH, 6 * gs, Math.max(1, gs / 2), com.flintclient.client.render.Palette.withAlpha(ACCENT, 0.35F * hotLayers));
		}
		int cx = (layerX0 + layerX1) / 2;
		// Auto and Surface: two chips, the one in use lit.
		layerAutoY = layerY0 + 13 * gs;
		layerSurfaceY = layerAutoY + 17 * gs;
		chip(c, cx, layerAutoY, cardW - 10 * gs, "Auto", layer == WorldMap.LAYER_AUTO);
		chip(c, cx, layerSurfaceY, cardW - 10 * gs, "Surface", layer == WorldMap.LAYER_SURFACE);
		// The track, from the world's top to its bottom.
		layerTopY = layerSurfaceY + 19 * gs;
		layerBottomY = layerY1 - 26 * gs;
		int tw = 4 * gs;
		int tx = cx - tw / 2;
		c.roundRect(tx, layerTopY, tw, layerBottomY - layerTopY, tw / 2, 0x1FFFFFFF);
		int line = Math.max(1, gs / 2);
		for (int y = range[1] - Math.floorMod(range[1], 64); y > range[0]; y -= 64) {
			int py = layerPixel(y, range);
			if (py - layerTopY > 6 * gs && layerBottomY - py > 6 * gs) {
				c.fill(tx - 4 * gs, py - line / 2, tx - 2 * gs, py - line / 2 + line, 0x40FFFFFF);
				c.fill(tx + tw + 2 * gs, py - line / 2, tx + tw + 4 * gs, py - line / 2 + line, 0x40FFFFFF);
			}
		}
		// The handle glides to the height shown; below it, the track fills in the accent.
		boolean height = isHeight(layer);
		float target = height ? layerPixel(Math.clamp(layer, range[0], range[1]), range) : layerTopY;
		if (Float.isNaN(layerThumb) || layerDragging) {
			layerThumb = target;
		} else {
			layerThumb += (target - layerThumb) * Math.min(1, frameSeconds() * 14);
		}
		layerShown += ((height ? 1 : 0) - layerShown) * Math.min(1, frameSeconds() * 12);
		int ty = Math.round(layerThumb);
		if (layerShown > 0.01F) {
			c.roundRect(tx, ty, tw, Math.max(tw, layerBottomY - ty), tw / 2, com.flintclient.client.render.Palette.withAlpha(ACCENT, 0.55F * layerShown));
		}
		// Where you stand: a small mark beside the track.
		LocalPlayer player = minecraft.player;
		if (viewingHere() && player != null) {
			int py = layerPixel(Math.clamp(playerY(), range[0], range[1]), range);
			int m = 3 * gs;
			c.roundRect(tx + tw + 3 * gs, py - m / 2, m, m, m / 2, TEXT);
		}
		if (layerShown > 0.01F) {
			int hw = 14 * gs + Math.round(2 * gs * hotLayers);
			int hh = 8 * gs;
			c.roundRect(cx - hw / 2, ty - hh / 2, hw, hh, hh / 2, com.flintclient.client.render.Palette.withAlpha(ACCENT, layerShown));
			c.roundRect(cx - hw / 2 + gs, ty - hh / 2 + gs, hw - 2 * gs, Math.max(1, gs), gs / 2 + 1, com.flintclient.client.render.Palette.withAlpha(0xFFFFFFFF, 0.35F * layerShown));
		}
		// The value, at the foot.
		chip(c, cx, layerY1 - 13 * gs, cardW - 10 * gs, layerName(layer), true);
		hits.add(new Hit(layerX0, layerY0, layerX1, layerY1, null, null, "Map layer: drag or scroll here. Page Up and Page Down: 16 blocks"));
	}

	/** A small chip centred on ({@code cx}, {@code cy}): lit in the accent while {@code on}, else quiet. */
	private void chip(Canvas c, int cx, int cy, int w, String label, boolean on) {
		int h = 13 * gs;
		c.roundRect(cx - w / 2, cy - h / 2, w, h, 4 * gs, on ? 0x40FF8139 : 0x12FFFFFF);
		centred(c, label, cx, cy, on ? TEXT : MUTED, SMALL);
	}

	/** This frame's time in seconds (for the slider's easing). */
	private static float frameSeconds() {
		return com.flintclient.client.ui.Motion.frameSeconds();
	}

	private void centerOnPlayer() {
		LocalPlayer player = minecraft.player;
		if (player != null) {
			show(Mc263Maps.dimension() == null ? dimension : Mc263Maps.dimension());
			cx = player.getX();
			cz = player.getZ();
		}
	}

	private int panelX(int w) {
		return w - 8 * gs - panelWidth();
	}

	private int panelWidth() {
		return 168 * gs;
	}

	private boolean overPanel() {
		if (!panel) {
			return false;
		}
		int w = width * gs;
		return mousePxX >= panelX(w) && mousePxY >= 36 * gs && mousePxY < height * gs - 8 * gs;
	}

	private void drawPanel(Canvas c, GuiGraphicsExtractor g, int w, int h) {
		int x = panelX(w);
		int y = 36 * gs;
		int pw = panelWidth();
		int ph = h - y - 8 * gs;
		card(c, x, y, pw, ph);
		WaypointStore store = Features.waypoints();
		int pad = 8 * gs;
		TextLine head = line("Waypoints", title());
		c.text(head, x + pad, c.baselineFor(head, y + 13 * gs), TEXT);
		TextLine dim = line(WaypointShare.dimensionName(dimension), SMALL);
		c.textRight(dim, x + pw - pad, c.baselineFor(dim, y + 13 * gs), MUTED);
		// The set row: ‹ name › + rename delete.
		int sy = y + 25 * gs;
		int sh = 18 * gs;
		List<String> sets = store.sets(dimension);
		String active = store.activeSet(dimension);
		int index = Math.max(0, sets.indexOf(active));
		int bx = x + pad;
		iconButton(c, Icon.CHEVRON_LEFT, bx, sy, sh, "Previous set", () -> cycleSet(-1), false);
		int nameX = bx + sh + 4 * gs;
		int buttons = 3 * (sh + 3 * gs);
		int nameW = pw - 2 * pad - 2 * (sh + 4 * gs) - buttons;
		c.roundRectCorners(nameX, sy, nameW, sh, 4 * gs, 0x10FFFFFF);
		TextLine setName = line(setTitle(active), BODY);
		c.textCentered(setName, nameX + nameW / 2, c.baselineFor(setName, sy + sh / 2), TEXT, nameW - 8 * gs);
		TextLine count = line((index + 1) + "/" + sets.size(), SMALL);
		hits.add(new Hit(nameX, sy, nameX + nameW, sy + sh, () -> cycleSet(1), null, "The active set: only its waypoints show when "
			+ "Show all sets is off. " + (index + 1) + " of " + sets.size()));
		bx = nameX + nameW + 4 * gs;
		iconButton(c, Icon.CHEVRON_RIGHT, bx, sy, sh, "Next set", () -> cycleSet(1), false);
		bx += sh + 6 * gs;
		iconButton(c, Icon.PLUS, bx, sy, sh, "New set", this::newSet, false);
		bx += sh + 3 * gs;
		iconButton(c, Icon.EDIT, bx, sy, sh, active.isEmpty() ? "The default set can't be renamed" : "Rename set", () -> {
			if (!active.isEmpty()) {
				editor = new Editor(active);
			}
		}, false);
		bx += sh + 3 * gs;
		boolean asking = active.equals(confirmSet);
		iconButton(c, Icon.TRASH, bx, sy, sh, asking ? "Press again to delete the set and its waypoints" : "Delete set", () -> {
			if (active.equals(confirmSet)) {
				store.removeSet(dimension, active);
				confirmSet = null;
			} else {
				confirmSet = active;
				confirmAt = System.currentTimeMillis();
			}
		}, asking);
		c.text(count, x + pad, c.baselineFor(count, sy + sh + 8 * gs), FAINT);
		// The list.
		listTop = sy + sh + 16 * gs;
		listBottom = y + ph - 30 * gs;
		List<Waypoint> rows = new ArrayList<>();
		for (Waypoint wp : store.all()) {
			if (wp.dimension().equals(dimension) && (wp.group().equals(active) || wp.death())) {
				rows.add(wp);
			}
		}
		int rowH = 24 * gs;
		listContent = rows.size() * rowH;
		listScroll = Math.clamp(listScroll, 0, Math.max(0, listContent - (listBottom - listTop)));
		c.pushClip(x, listTop, x + pw, listBottom);
		try {
			int ry = listTop - Math.round(listScroll);
			LocalPlayer player = minecraft.player;
			for (Waypoint wp : rows) {
				if (ry + rowH >= listTop && ry < listBottom) {
					row(c, wp, x + 4 * gs, ry, pw - 8 * gs, rowH, player);
				}
				ry += rowH;
			}
			if (rows.isEmpty()) {
				centred(c, "No waypoints in this set", x + pw / 2, listTop + 20 * gs, MUTED, SMALL);
			}
		} finally {
			c.popClip();
		}
		// Footer: add where you stand.
		int fy = y + ph - 26 * gs;
		boolean canAdd = viewingHere() && minecraft.player != null;
		boolean hot = canAdd && hovering(x + pad, fy, x + pw - pad, fy + 20 * gs);
		c.roundRectCorners(x + pad, fy, pw - 2 * pad, 20 * gs, 5 * gs, canAdd ? hot ? 0xFFFF9452 : ACCENT : 0x14FFFFFF);
		TextLine add = line("Add waypoint here", BODY);
		int iconSize = 10 * gs;
		int total = iconSize + 5 * gs + c.width(add);
		int ax = x + pw / 2 - total / 2;
		c.icon(Icon.PLUS, ax, fy + 5 * gs, iconSize, canAdd ? 0xFF1A0E05 : FAINT);
		c.text(add, ax + iconSize + 5 * gs, c.baselineFor(add, fy + 10 * gs), canAdd ? 0xFF1A0E05 : FAINT);
		if (canAdd) {
			hits.add(new Hit(x + pad, fy, x + pw - pad, fy + 20 * gs, () -> {
				int[] pos = Mc263Features.blockPosition();
				if (pos != null) {
					editor = new Editor(null, pos[0], pos[1], pos[2]);
				}
			}, null, "Where you stand, in the active set"));
		}
	}

	private static String setTitle(String set) {
		return set.isEmpty() ? "Default" : set;
	}

	private void cycleSet(int by) {
		WaypointStore store = Features.waypoints();
		List<String> sets = store.sets(dimension);
		int i = Math.max(0, sets.indexOf(store.activeSet(dimension)));
		store.setActiveSet(dimension, sets.get(Math.floorMod(i + by, sets.size())));
		listScroll = 0;
	}

	private void newSet() {
		WaypointStore store = Features.waypoints();
		List<String> sets = store.sets(dimension);
		int n = sets.size() + 1;
		String name = "Set " + n;
		while (sets.contains(name)) {
			name = "Set " + ++n;
		}
		store.setActiveSet(dimension, name);
		editor = new Editor(name);
	}

	private void row(Canvas c, Waypoint wp, int x, int y, int w, int h, @Nullable LocalPlayer player) {
		boolean inList = mousePxY >= listTop && mousePxY < listBottom;
		boolean hot = inList && hovering(x, y, x + w, y + h);
		if (hot) {
			c.roundRectCorners(x, y + gs, w, h - 2 * gs, 5 * gs, HOVER);
		}
		int box = 12 * gs;
		Mc263Minimap.marker(c, wp, x + 6 * gs + box / 2.0F, y + h / 2.0F, box, wp.visible() ? 1 : 0.4F);
		int tx = x + 12 * gs + box;
		TextLine name = line(wp.name(), BODY);
		int actions = hot ? 4 * (14 * gs + 2 * gs) : 0;
		c.text(name, tx, c.baselineFor(name, y + h / 2 - 4 * gs), wp.visible() ? TEXT : MUTED, w - (tx - x) - actions - 4 * gs);
		String sub;
		if (player != null && viewingHere()) {
			double d = wp.distanceTo(player.getX(), player.getY(), player.getZ());
			sub = distance(d) + " · " + wp.x() + ", " + wp.y() + ", " + wp.z();
		} else {
			sub = wp.x() + ", " + wp.y() + ", " + wp.z();
		}
		TextLine subLine = line(sub, SMALL);
		c.text(subLine, tx, c.baselineFor(subLine, y + h / 2 + 5 * gs), FAINT, w - (tx - x) - actions - 4 * gs);
		if (inList && y >= listTop - h && y < listBottom) {
			hits.add(new Hit(x, Math.max(y, listTop), x + w, Math.min(y + h, listBottom), () -> {
				cx = wp.x() + 0.5;
				cz = wp.z() + 0.5;
				if (zoomTarget < 1) {
					zoomTarget = 1;
				}
			}, () -> menu = waypointMenu(wp, mousePxX, mousePxY), null));
		}
		if (hot) {
			int s = 14 * gs;
			int bx = x + w - 4 * gs - s;
			int by = y + (h - s) / 2;
			boolean asking = wp == confirmDelete;
			iconButton(c, Icon.TRASH, bx, by, s, asking ? "Press again to delete" : "Delete", () -> delete(wp), asking);
			bx -= s + 2 * gs;
			iconButton(c, wp.visible() ? Icon.EYE : Icon.EYE_OFF, bx, by, s, wp.visible() ? "Hide" : "Show", () -> toggleVisible(wp), false);
			bx -= s + 2 * gs;
			iconButton(c, Icon.MESSAGE, bx, by, s, "Share in chat", () -> share(wp), false);
			bx -= s + 2 * gs;
			iconButton(c, Icon.EDIT, bx, by, s, "Edit", () -> editor = new Editor(wp), false);
		}
	}

	/** The distance as the waypoint labels show it: "[37m]", "[1.2K]", "[12K]". */
	static String distance(double blocks) {
		return WaypointBadge.distanceText(blocks);
	}

	private void drawZoomButtons(Canvas c, int w, int h) {
		int size = 20 * gs;
		int x = (panel ? panelX(w) - 6 * gs : w - 8 * gs) - size;
		int y = h - 8 * gs - 2 * size - 4 * gs - 18 * gs;
		iconButton(c, Icon.PLUS, x, y, size, "Zoom in (+)", () -> zoomBy(2, Float.NaN, 0), false);
		iconButton(c, Icon.MINUS, x, y + size + 4 * gs, size, "Zoom out (-)", () -> zoomBy(0.5F, Float.NaN, 0), false);
	}

	private void zoomBy(float factor, float atX, float atY) {
		zoomTarget = Math.clamp(zoomTarget * factor, MIN_ZOOM, MAX_ZOOM);
		anchorX = atX;
		anchorY = atY;
	}

	private void drawCursorInfo(Canvas c, int h) {
		if (overPanel() || menu != null || editor != null) {
			return;
		}
		view.toWorld(mousePxX, mousePxY, world);
		int bx = Mth.floor(world[0]);
		int bz = Mth.floor(world[1]);
		StringBuilder text = new StringBuilder();
		text.append("X ").append(bx).append("   Z ").append(bz);
		MapWorld map = map();
		if (map != null) {
			MapRegion r = map.region(bx >> MapRegion.BITS, bz >> MapRegion.BITS);
			if (r != null && r.full() && r.color(bx & MapRegion.SIZE - 1, bz & MapRegion.SIZE - 1) != 0) {
				text.append("   Y ").append(r.height(bx & MapRegion.SIZE - 1, bz & MapRegion.SIZE - 1));
				String biome = Mc263MapScanner.biomeTitle(map.biomeName(r.biome(bx & MapRegion.SIZE - 1, bz & MapRegion.SIZE - 1)));
				if (!biome.isEmpty()) {
					text.append("   ").append(biome);
				}
			}
		}
		TextLine l = line(text.toString(), BODY);
		int tw = c.width(l);
		int ph = 20 * gs;
		int y = h - 8 * gs - ph;
		card(c, 8 * gs, y, tw + 20 * gs, ph);
		c.text(l, 18 * gs, c.baselineFor(l, y + ph / 2), TEXT);
	}

	private void drawScale(Canvas c, int w, int h) {
		// A round length about 70 units long.
		float blocksPer70 = 70 / zoom;
		double magnitude = Math.pow(10, Math.floor(Math.log10(blocksPer70)));
		double nice = magnitude;
		for (double m : new double[] {1, 2, 5, 10}) {
			if (m * magnitude <= blocksPer70) {
				nice = m * magnitude;
			}
		}
		int len = (int) Math.round(nice * zoom * gs);
		int right = (panel ? panelX(w) - 6 * gs : w - 8 * gs);
		int y = h - 8 * gs - 14 * gs;
		String label = nice >= 1000 ? String.format(Locale.ROOT, "%.0f km", nice / 1000) : String.format(Locale.ROOT, "%.0f blocks", nice);
		TextLine l = line(label, SMALL);
		int cardW = Math.max(len, c.width(l)) + 16 * gs;
		card(c, right - cardW, y, cardW, 14 * gs);
		int x0 = right - 8 * gs - len;
		int line = Math.max(1, gs);
		c.fill(x0, y + 10 * gs, x0 + len, y + 10 * gs + line, TEXT);
		c.fill(x0, y + 8 * gs, x0 + line, y + 11 * gs, TEXT);
		c.fill(x0 + len - line, y + 8 * gs, x0 + len, y + 11 * gs, TEXT);
		c.textRight(l, right - 8 * gs, c.baselineFor(l, y + 5 * gs), MUTED);
	}

	private void drawMenu(Canvas c, Menu m) {
		int pad = 4 * gs;
		int itemH = 18 * gs;
		int w = 0;
		TextLine title = line(m.title(), SMALL);
		w = Math.max(w, c.width(title));
		for (Item item : m.items()) {
			w = Math.max(w, c.width(line(item.label(), BODY)) + 18 * gs);
		}
		w += 2 * pad + 12 * gs;
		int h = 2 * pad + 14 * gs + m.items().size() * itemH;
		int x = Math.min(m.x(), width * gs - w - 4 * gs);
		int y = Math.min(m.y(), height * gs - h - 4 * gs);
		card(c, x, y, w, h);
		c.text(title, x + pad + 6 * gs, c.baselineFor(title, y + pad + 6 * gs), FAINT, w - 2 * pad);
		int iy = y + pad + 14 * gs;
		for (Item item : m.items()) {
			boolean hot = mousePxX >= x && mousePxX < x + w && mousePxY >= iy && mousePxY < iy + itemH;
			if (hot) {
				c.roundRectCorners(x + pad, iy, w - 2 * pad, itemH, 4 * gs, item.danger() ? 0x30FF5A5A : 0x20FFFFFF);
			}
			int color = item.danger() ? DANGER : hot ? TEXT : 0xFFDADADF;
			c.icon(item.icon(), x + pad + 5 * gs, iy + (itemH - 10 * gs) / 2, 10 * gs, item.danger() ? DANGER : hot ? ACCENT : MUTED);
			TextLine label = line(item.label(), BODY);
			c.text(label, x + pad + 21 * gs, c.baselineFor(label, iy + itemH / 2), color);
			Runnable action = item.action();
			hits.add(new Hit(x, iy, x + w, iy + itemH, () -> {
				menu = null;
				action.run();
			}, null, null));
			iy += itemH;
		}
		// Clicks anywhere else close it.
		hits.add(0, new Hit(0, 0, width * gs, height * gs, () -> menu = null, () -> menu = null, null));
	}

	private void drawTooltip(Canvas c, GuiGraphicsExtractor g) {
		if (menu != null || editor != null || dragging) {
			return;
		}
		String tip = null;
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit hit = hits.get(i);
			if (hit.contains(mousePxX, mousePxY)) {
				tip = hit.tip();
				break;
			}
		}
		if (tip == null) {
			return;
		}
		g.nextStratum();
		TextLine l = line(tip, SMALL);
		int tw = Math.min(c.width(l), 220 * gs);
		int x = Math.min(mousePxX + 8 * gs, width * gs - tw - 14 * gs);
		int y = mousePxY + 12 * gs;
		if (y + 14 * gs > height * gs) {
			y = mousePxY - 20 * gs;
		}
		c.roundRectCorners(x, y, tw + 12 * gs, 14 * gs, 4 * gs, 0xF0000000);
		c.text(l, x + 6 * gs, c.baselineFor(l, y + 7 * gs), TEXT, tw);
	}

	// ---------- actions ----------

	private Menu placeMenu(int px, int py) {
		view.toWorld(px, py, world);
		int x = Mth.floor(world[0]);
		int z = Mth.floor(world[1]);
		int y = heightAt(x, z);
		String place = x + ", " + (y == Integer.MIN_VALUE ? "~" : Integer.toString(y)) + ", " + z;
		List<Item> items = new ArrayList<>();
		int wy = y == Integer.MIN_VALUE ? playerY() : y + 1;
		items.add(new Item(Icon.PLUS, "Create waypoint here", () -> editor = new Editor(null, x, wy, z), false));
		if (canTeleport()) {
			items.add(new Item(Icon.PLAY, "Teleport here", () -> teleport(x, y == Integer.MIN_VALUE ? Integer.MIN_VALUE : y + 1, z), false));
		}
		items.add(new Item(Icon.COPY, "Copy coordinates", () -> minecraft.keyboardHandler.setClipboard(x + " " + wy + " " + z), false));
		items.add(new Item(Icon.MESSAGE, "Share in chat", () -> share(new Waypoint("Spot", x, wy, z, dimension, 0xFF8139, true)), false));
		return new Menu(px, py, place, items);
	}

	private Menu waypointMenu(Waypoint wp, int px, int py) {
		List<Item> items = new ArrayList<>();
		items.add(new Item(Icon.EDIT, "Edit", () -> editor = new Editor(wp), false));
		if (canTeleport()) {
			items.add(new Item(Icon.PLAY, "Teleport", () -> teleport(wp.x(), wp.y(), wp.z()), false));
		}
		items.add(new Item(Icon.MESSAGE, "Share in chat", () -> share(wp), false));
		items.add(new Item(Icon.COPY, "Copy coordinates", () -> minecraft.keyboardHandler.setClipboard(wp.x() + " " + wp.y() + " " + wp.z()), false));
		items.add(new Item(wp.visible() ? Icon.EYE_OFF : Icon.EYE, wp.visible() ? "Hide" : "Show", () -> toggleVisible(wp), false));
		items.add(new Item(Icon.TRASH, "Delete", () -> {
			int i = Features.waypoints().indexOf(wp);
			if (i >= 0) {
				Features.waypoints().remove(i);
			}
		}, true));
		return new Menu(px, py, wp.name(), items);
	}

	private int heightAt(int x, int z) {
		MapWorld map = map();
		if (map == null) {
			return Integer.MIN_VALUE;
		}
		MapRegion r = map.region(x >> MapRegion.BITS, z >> MapRegion.BITS);
		if (r == null || !r.full() || r.color(x & MapRegion.SIZE - 1, z & MapRegion.SIZE - 1) == 0) {
			return Integer.MIN_VALUE;
		}
		return r.height(x & MapRegion.SIZE - 1, z & MapRegion.SIZE - 1);
	}

	private int playerY() {
		LocalPlayer player = minecraft.player;
		return player == null ? 64 : Mth.floor(player.getY());
	}

	/** Whether teleporting is on and the server lets you use its teleport command (it only sends commands you may use). */
	private boolean canTeleport() {
		if (!settings().bool(WorldMap.TELEPORT)) {
			return false;
		}
		ClientPacketListener connection = minecraft.getConnection();
		if (connection == null) {
			return false;
		}
		String command = settings().text(WorldMap.TELEPORT_COMMAND).strip();
		String first = command.startsWith("/") ? command.substring(1) : command;
		first = first.split(" ", 2)[0];
		return !first.isEmpty() && connection.getCommands().getRoot().getChild(first) != null
			&& (viewingHere() || connection.getCommands().getRoot().getChild("execute") != null);
	}

	private void teleport(int x, int y, int z) {
		ClientPacketListener connection = minecraft.getConnection();
		if (connection == null) {
			return;
		}
		String command = settings().text(WorldMap.TELEPORT_COMMAND).strip();
		if (command.startsWith("/")) {
			command = command.substring(1);
		}
		command = command.replace("{x}", Integer.toString(x)).replace("{y}", y == Integer.MIN_VALUE ? "~" : Integer.toString(y))
			.replace("{z}", Integer.toString(z));
		if (!viewingHere()) {
			command = "execute in " + dimension + " run " + command;
		}
		connection.sendCommand(command);
		onClose();
	}

	private void share(Waypoint wp) {
		ClientPacketListener connection = minecraft.getConnection();
		if (connection != null) {
			connection.sendChat(WaypointShare.line(wp));
		}
		onClose();
	}

	private void toggleVisible(Waypoint wp) {
		int i = Features.waypoints().indexOf(wp);
		if (i >= 0) {
			Features.waypoints().set(i, wp.withVisible(!wp.visible()));
		}
	}

	private void delete(Waypoint wp) {
		if (wp == confirmDelete) {
			int i = Features.waypoints().indexOf(wp);
			if (i >= 0) {
				Features.waypoints().remove(i);
			}
			confirmDelete = null;
		} else {
			confirmDelete = wp;
			confirmAt = System.currentTimeMillis();
		}
	}

	// ---------- input ----------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int px = (int) Math.round(event.x() * gs);
		int py = (int) Math.round(event.y() * gs);
		mousePxX = px;
		mousePxY = py;
		if (editor != null) {
			if (editor.click(px, py)) {
				return true;
			}
			return super.mouseClicked(event, doubleClick);
		}
		if (menu == null && overLayers(px, py)) {
			// The layer slider: the value follows the mouse while it's held, and is set when it's let go.
			if (event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
				layerDragging = true;
				layerPending = layerAt(py);
			}
			return true;
		}
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit hit = hits.get(i);
			if (hit.contains(px, py)) {
				Runnable action = event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT ? hit.right() : hit.left();
				if (action != null) {
					action.run();
					return true;
				}
				if (menu != null) {
					return true;
				}
				break;
			}
		}
		if (overPanel()) {
			return true;
		}
		if (event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT) {
			Waypoint wp = hoveredWaypoint();
			menu = wp != null ? waypointMenu(wp, px, py) : placeMenu(px, py);
			return true;
		}
		if (event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
			dragging = true;
			dragMoved = false;
			dragX = event.x();
			dragY = event.y();
			if (doubleClick) {
				view.toWorld(px, py, world);
				cx = world[0];
				cz = world[1];
				zoomBy(2, Float.NaN, 0);
			}
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (editor != null) {
			return super.mouseDragged(event, dx, dy);
		}
		if (layerDragging) {
			layerPending = layerAt((int) Math.round(event.y() * gs));
			return true;
		}
		if (dragging) {
			if (Math.abs(event.x() - dragX) + Math.abs(event.y() - dragY) > 2) {
				dragMoved = true;
			}
			cx -= dx / zoom;
			cz -= dy / zoom;
			anchorX = Float.NaN;
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (layerDragging && event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
			layerDragging = false;
			if (layerPending != Integer.MIN_VALUE) {
				setLayer(layerPending);
			}
			layerPending = Integer.MIN_VALUE;
			return true;
		}
		if (dragging && event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
			dragging = false;
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (editor != null || menu != null || scrollY == 0) {
			return true;
		}
		int px = (int) Math.round(x * gs);
		int py = (int) Math.round(y * gs);
		if (overLayers(px, py)) {
			stepLayer(scrollY > 0 ? 4 : -4);
			return true;
		}
		if (overPanel()) {
			listScroll -= (float) scrollY * 24 * gs;
			return true;
		}
		float factor = (float) Math.pow(1.35, scrollY);
		boolean toMouse = settings().bool(WorldMap.SCROLL_ZOOM_TO_MOUSE);
		zoomBy(factor, toMouse ? px : Float.NaN, py);
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (editor != null) {
			if (event.isEscape()) {
				editor.close();
				return true;
			}
			if (event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN || event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER) {
				editor.save();
				return true;
			}
			return super.keyPressed(event);
		}
		if (menu != null && event.isEscape()) {
			menu = null;
			return true;
		}
		if (event.isEscape() || keyIsMapKey(event)) {
			onClose();
			return true;
		}
		int key = event.key();
		if (key == com.mojang.blaze3d.platform.InputConstants.KEY_EQUALS || key == com.mojang.blaze3d.platform.InputConstants.KEY_ADD) {
			zoomBy(2, Float.NaN, 0);
			return true;
		}
		if (key == com.mojang.blaze3d.platform.InputConstants.KEY_MINUS) {
			zoomBy(0.5F, Float.NaN, 0);
			return true;
		}
		if (key == com.mojang.blaze3d.platform.InputConstants.KEY_SPACE) {
			centerOnPlayer();
			return true;
		}
		// The layer: Page Up / Page Down, or the minimap's own layer keys.
		ModuleState mini = Mods.state(MINIMAP_MOD);
		if (key == com.mojang.blaze3d.platform.InputConstants.KEY_PAGEUP || isKey(event, mini.text(Minimap.LAYER_UP_KEY))) {
			stepLayer(16);
			return true;
		}
		if (key == com.mojang.blaze3d.platform.InputConstants.KEY_PAGEDOWN || isKey(event, mini.text(Minimap.LAYER_DOWN_KEY))) {
			stepLayer(-16);
			return true;
		}
		return super.keyPressed(event);
	}

	private static boolean isKey(KeyEvent event, String name) {
		if (name.isEmpty()) {
			return false;
		}
		try {
			com.mojang.blaze3d.platform.InputConstants.Key key = com.mojang.blaze3d.platform.InputConstants.getKey(name);
			return key.getType() == com.flintclient.client.compat.McCompat.KEYBOARD && key.getValue() == event.key();
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	private boolean keyIsMapKey(KeyEvent event) {
		String name = settings().text(WorldMap.OPEN_KEY);
		if (name.isEmpty()) {
			return false;
		}
		try {
			com.mojang.blaze3d.platform.InputConstants.Key key = com.mojang.blaze3d.platform.InputConstants.getKey(name);
			return key.getType() == com.flintclient.client.compat.McCompat.KEYBOARD && key.getValue() == event.key();
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		return editor != null && super.charTyped(event);
	}

	// ---------- the waypoint editor ----------

	/** The card that makes or edits a waypoint, or renames a set. */
	private final class Editor {
		private final @Nullable Waypoint original;
		private final @Nullable String set;
		private final List<EditBox> boxes = new ArrayList<>();
		private @Nullable EditBox name;
		private @Nullable EditBox letters;
		private @Nullable EditBox x;
		private @Nullable EditBox y;
		private @Nullable EditBox z;
		private int color;
		private boolean visible = true;
		private String group = "";
		private int cardX;
		private int cardY;
		private int cardW;
		private int cardH;

		/** Edits {@code wp}. */
		Editor(Waypoint wp) {
			this.original = wp;
			this.set = null;
			this.color = wp.rgb();
			this.visible = wp.visible();
			this.group = wp.group();
			make(wp.name(), wp.initials(), wp.x(), wp.y(), wp.z());
		}

		/** A new waypoint at a block (null original). */
		Editor(@Nullable Waypoint none, int bx, int by, int bz) {
			this.original = none;
			this.set = null;
			this.color = PALETTE[Features.waypoints().all().size() % PALETTE.length];
			this.group = Features.waypoints().activeSet(dimension);
			make(Features.waypoints().nextName("Waypoint"), "", bx, by, bz);
		}

		/** Renames the set {@code setName}. */
		Editor(String setName) {
			this.original = null;
			this.set = setName;
			name = box(setName, Waypoint.MAX_GROUP, "Set name");
			place();
			setFocused(name);
			name.setFocused(true);
		}

		private void make(String n, String l, int bx, int by, int bz) {
			name = box(n, Waypoint.MAX_NAME, "Name");
			letters = box(l, Waypoint.MAX_INITIALS, "Auto");
			x = number(bx);
			y = number(by);
			z = number(bz);
			place();
			setFocused(name);
			name.setFocused(true);
		}

		private EditBox box(String value, int max, String hint) {
			EditBox box = new EditBox(font, 0, 0, 100, 12, Component.literal(hint));
			box.setMaxLength(max);
			box.setValue(value);
			box.setBordered(false);
			box.setTextColor(0xFFF3F3F3);
			box.setHint(Component.literal(hint).withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
			addRenderableWidget(box);
			boxes.add(box);
			return box;
		}

		private EditBox number(int value) {
			EditBox box = box(Integer.toString(value), 9, "0");
			box.setResponder(text -> {
				String clean = text.replaceAll("[^0-9-]", "");
				if (!clean.equals(text)) {
					box.setValue(clean);
				}
			});
			return box;
		}

		/** Lays the card and its boxes out (GUI units for the boxes). */
		void place() {
			cardW = (set != null ? 220 : 260);
			cardH = set != null ? 106 : 196;
			cardX = (width - cardW) / 2;
			cardY = (height - cardH) / 2;
			int pad = 14;
			if (set != null) {
				layout(name, cardX + pad, cardY + 42, cardW - 2 * pad);
				return;
			}
			layout(name, cardX + pad, cardY + 44, cardW - 2 * pad - 52);
			layout(letters, cardX + cardW - pad - 44, cardY + 44, 44);
			int third = (cardW - 2 * pad - 2 * 8) / 3;
			layout(x, cardX + pad, cardY + 82, third);
			layout(y, cardX + pad + third + 8, cardY + 82, third);
			layout(z, cardX + pad + 2 * (third + 8), cardY + 82, third);
		}

		private void layout(@Nullable EditBox box, int bx, int by, int bw) {
			if (box != null) {
				box.setX(bx + 5);
				box.setY(by + 4);
				box.setWidth(bw - 10);
			}
		}

		void close() {
			for (EditBox box : boxes) {
				removeWidget(box);
			}
			editor = null;
		}

		private int value(@Nullable EditBox box, int fallback) {
			try {
				return box == null ? fallback : Integer.parseInt(box.getValue().strip());
			} catch (NumberFormatException e) {
				return fallback;
			}
		}

		void save() {
			WaypointStore store = Features.waypoints();
			if (set != null) {
				String to = name == null ? set : name.getValue().strip();
				if (!to.isEmpty() && !to.equals(set)) {
					store.renameSet(dimension, set, to);
				}
				close();
				return;
			}
			String n = name == null ? "Waypoint" : name.getValue();
			int bx = value(x, 0);
			int by = value(y, 64);
			int bz = value(z, 0);
			if (original != null) {
				int i = store.indexOf(original);
				if (i >= 0) {
					store.set(i, original.withName(n).withPosition(bx, by, bz).withColor(color).withVisible(visible).withGroup(group)
						.withInitials(letters == null ? "" : letters.getValue()));
				}
			} else {
				store.add(new Waypoint(n, bx, by, bz, dimension, color, visible).withGroup(group)
					.withInitials(letters == null ? "" : letters.getValue()));
			}
			close();
		}

		private void label(Canvas c, String text, int gx, int gy) {
			TextLine l = line(text, SMALL);
			c.text(l, gx * gs, c.baselineFor(l, gy * gs), MUTED);
		}

		private void field(Canvas c, @Nullable EditBox box) {
			if (box == null) {
				return;
			}
			int bx = (box.getX() - 5) * gs;
			int by = (box.getY() - 4) * gs;
			int bw = (box.getWidth() + 10) * gs;
			int bh = 18 * gs;
			c.roundRectCorners(bx, by, bw, bh, 4 * gs, 0xFF19191C);
			c.ring(bx, by, bw, bh, 4 * gs, Math.max(1, gs / 2), box.isFocused() ? ACCENT : 0x30FFFFFF);
		}

		void paint(Canvas c, int w, int h) {
			c.fill(0, 0, w, h, 0x99000000);
			int px = cardX * gs;
			int py = cardY * gs;
			card(c, px, py, cardW * gs, cardH * gs);
			TextLine title = line(set != null ? "Rename set" : original == null ? "New waypoint" : "Edit waypoint", title());
			c.text(title, px + 14 * gs, c.baselineFor(title, py + 18 * gs), TEXT);
			if (set != null) {
				label(c, "NAME", cardX + 14, cardY + 36);
				field(c, name);
				buttons(c, false);
				return;
			}
			TextLine where = line(WaypointShare.dimensionName(dimension) + " · " + setTitle(group), SMALL);
			c.textRight(where, px + (cardW - 14) * gs, c.baselineFor(where, py + 18 * gs), MUTED);
			label(c, "NAME", cardX + 14, cardY + 38);
			label(c, "LETTERS", cardX + cardW - 58, cardY + 38);
			field(c, name);
			field(c, letters);
			label(c, "X", cardX + 14, cardY + 76);
			int third = (cardW - 28 - 16) / 3;
			label(c, "Y", cardX + 14 + third + 8, cardY + 76);
			label(c, "Z", cardX + 14 + 2 * (third + 8), cardY + 76);
			field(c, x);
			field(c, y);
			field(c, z);
			// Colours.
			label(c, "COLOR", cardX + 14, cardY + 114);
			int sw = 12;
			int sx = cardX + 14;
			int sy = cardY + 120;
			for (int i = 0; i < PALETTE.length; i++) {
				int rgb = PALETTE[i];
				int bx = (sx + i * (sw + 2)) * gs;
				int by = sy * gs;
				boolean on = (color & 0xFFFFFF) == rgb;
				if (on) {
					c.roundRectCorners(bx - gs, by - gs, (sw + 2) * gs, (sw + 2) * gs, 4 * gs, TEXT);
				}
				c.roundRectCorners(bx, by, sw * gs, sw * gs, 3 * gs, 0xFF000000 | rgb);
				hits.add(new Hit(bx, by, bx + sw * gs, by + sw * gs, () -> color = rgb, null, null));
			}
			// Shown, and the preview marker.
			int toggleY = (cardY + 142) * gs;
			int tx = (cardX + 14) * gs;
			c.roundRectCorners(tx, toggleY, 24 * gs, 13 * gs, 13 * gs / 2, visible ? ACCENT : 0x40FFFFFF);
			int knob = 9 * gs;
			c.roundRectCorners(visible ? tx + 13 * gs : tx + 2 * gs, toggleY + 2 * gs, knob, knob, knob / 2, 0xFFFFFFFF);
			TextLine shown = line("Shown on the maps", BODY);
			c.text(shown, tx + 30 * gs, c.baselineFor(shown, toggleY + 13 * gs / 2), TEXT);
			hits.add(new Hit(tx, toggleY, tx + 30 * gs + c.width(shown), toggleY + 13 * gs, () -> visible = !visible, null, null));
			Waypoint preview = new Waypoint(name == null ? "W" : name.getValue().isBlank() ? "W" : name.getValue(), 0, 0, 0, dimension, color, true)
				.withInitials(letters == null ? "" : letters.getValue());
			Mc263Minimap.marker(c, preview, (cardX + cardW - 26) * gs, toggleY + 6 * gs, 14 * gs, 1);
			buttons(c, original != null);
		}

		private void buttons(Canvas c, boolean delete) {
			int by = (cardY + cardH - 30) * gs;
			int bh = 20 * gs;
			int right = (cardX + cardW - 14) * gs;
			TextLine save = line("Save", BODY);
			int saveW = c.width(save) + 24 * gs;
			boolean hot = mousePxX >= right - saveW && mousePxX < right && mousePxY >= by && mousePxY < by + bh;
			c.roundRectCorners(right - saveW, by, saveW, bh, 5 * gs, hot ? 0xFFFF9452 : ACCENT);
			c.textCentered(save, right - saveW / 2, c.baselineFor(save, by + bh / 2), 0xFF1A0E05);
			hits.add(new Hit(right - saveW, by, right, by + bh, this::save, null, null));
			TextLine cancel = line("Cancel", BODY);
			int cancelW = c.width(cancel) + 24 * gs;
			int cx0 = right - saveW - 6 * gs - cancelW;
			boolean hotC = mousePxX >= cx0 && mousePxX < cx0 + cancelW && mousePxY >= by && mousePxY < by + bh;
			c.roundRectCorners(cx0, by, cancelW, bh, 5 * gs, hotC ? 0x30FFFFFF : 0x18FFFFFF);
			c.textCentered(cancel, cx0 + cancelW / 2, c.baselineFor(cancel, by + bh / 2), TEXT);
			hits.add(new Hit(cx0, by, cx0 + cancelW, by + bh, this::close, null, null));
			if (delete && original != null) {
				int dx = (cardX + 14) * gs;
				TextLine del = line(original == confirmDelete ? "Press again" : "Delete", BODY);
				int delW = c.width(del) + 24 * gs;
				boolean hotD = mousePxX >= dx && mousePxX < dx + delW && mousePxY >= by && mousePxY < by + bh;
				c.roundRectCorners(dx, by, delW, bh, 5 * gs, hotD || original == confirmDelete ? 0x40FF5A5A : 0x18FF5A5A);
				c.textCentered(del, dx + delW / 2, c.baselineFor(del, by + bh / 2), DANGER);
				Waypoint target = original;
				hits.add(new Hit(dx, by, dx + delW, by + bh, () -> {
					if (target == confirmDelete) {
						delete(target);
						close();
					} else {
						delete(target);
					}
				}, null, null));
			}
		}

		/** A click on the card's own controls (colours, the switch, buttons); false lets the text boxes take it. */
		boolean click(int px, int py) {
			for (int i = hits.size() - 1; i >= 0; i--) {
				Hit hit = hits.get(i);
				int cardRight = (cardX + cardW) * gs;
				int cardBottom = (cardY + cardH) * gs;
				boolean onCard = hit.x0() >= cardX * gs && hit.x1() <= cardRight && hit.y0() >= cardY * gs && hit.y1() <= cardBottom;
				if (onCard && hit.contains(px, py) && hit.left() != null) {
					hit.left().run();
					return true;
				}
			}
			return false;
		}
	}
}
