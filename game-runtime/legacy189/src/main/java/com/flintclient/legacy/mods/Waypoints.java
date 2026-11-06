package com.flintclient.legacy.mods;

import com.flintclient.client.control.model.mods.UtilityMods;
import com.flintclient.legacy.module.Module;
import com.flintclient.legacy.module.Setting;
import com.flintclient.legacy.waypoints.Waypoint;
import com.flintclient.legacy.waypoints.WaypointStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.living.player.ClientPlayerEntity;
import net.minecraft.client.options.ServerListEntry;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.HitResult;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * Waypoints on 1.8.9, as wWaypoints marks places: tap the Create key (N) and the block you stand on gets a waypoint (with Sneak:
 * the block you look at, up to 16 away); tap it on a waypoint's block again and it goes. Each one shows over the world as a
 * label (its first letter in a dark square, its distance under it while you look at it) that shrinks gently with distance, and
 * its block outlined and tinted in its colour while it is within your render distance. Death waypoints mark where you died.
 * Kept per world or server on this computer ({@link WaypointStore}); the menu's Waypoints tab lists and edits them. The 1.8.9
 * backend of Waypoints: its keys, death waypoints, colours, order, display, labels and blocks are the shared (wWaypoints)
 * settings.
 */
public final class Waypoints extends Module {
	// ---------- General ----------
	public final Setting.Bool deathWaypoints = bool(UtilityMods.Waypoints.ON_DEATH);
	public final Setting.Int deathLimit = integer(UtilityMods.Waypoints.DEATH_LIMIT);
	public final Setting.Bool randomColors = bool(UtilityMods.Waypoints.RANDOM_COLORS);
	public final Setting.Choice sort = option(UtilityMods.Waypoints.SORT);
	public final Setting.Int hideBeyond = bind(new Setting.Int(slot(UtilityMods.Waypoints.MAX_DISTANCE), UtilityMods.Waypoints.MAX_DISTANCE) {
		@Override
		public String format(int v) {
			return v == 0 ? "Any" : v + " blocks";
		}
	});
	// ---------- Display ----------
	public final Setting.Choice display = option(UtilityMods.Waypoints.DISPLAY);
	/** Icon, Text, their coloured forms, or Simple (drawn as Text on 1.8.9). */
	public final Setting.Choice style = option(UtilityMods.Waypoints.LABEL_STYLE);
	// ---------- Label ----------
	/** The label's size in percent (the shared size times 100). */
	public final Setting.Decimal labelScale = decimal(UtilityMods.Waypoints.LABEL_SCALE, 100, "×");
	public final Setting.Decimal labelHeight = decimal(UtilityMods.Waypoints.LABEL_HEIGHT, 10, " blocks");
	public final Setting.Int labelOpacity = integer(UtilityMods.Waypoints.LABEL_OPACITY);
	public final Setting.Bool showDistance = bool(UtilityMods.Waypoints.SHOW_DISTANCE);
	public final Setting.Bool distanceFacing = bool(UtilityMods.Waypoints.DISTANCE_FACING);
	public final Setting.Bool textShadow = bool(UtilityMods.Waypoints.TEXT_SHADOW);
	public final Setting.Int shadowOpacity = integer(UtilityMods.Waypoints.SHADOW_OPACITY);
	// ---------- Block ----------
	public final Setting.Bool throughWalls = bool(UtilityMods.Waypoints.THROUGH_WALLS);
	public final Setting.Int outlineOpacity = integer(UtilityMods.Waypoints.OUTLINE_OPACITY);
	public final Setting.Int fillOpacity = integer(UtilityMods.Waypoints.FILL_OPACITY);
	public final Setting.Decimal outlineThickness = decimal(UtilityMods.Waypoints.OUTLINE_THICKNESS, 10, "");
	// ---------- Keys ----------
	public final Setting.Key createKey = key(UtilityMods.Waypoints.CREATE_KEY);
	public final Setting.Bool askName = bool(UtilityMods.Waypoints.ASK_NAME);
	public final Setting.Key hideKey = key(UtilityMods.Waypoints.TOGGLE_KEY);
	public final Setting.Key listKey = key(UtilityMods.Waypoints.MENU_KEY);

	private static WaypointStore store;
	private static boolean hidden;
	private boolean createWas;
	private boolean hideWas;
	private boolean listWas;
	private boolean wasDead;

	public Waypoints() {
		super("waypoints", "waypoints");
	}

	/** This world's waypoints (made on first use, under the game folder). */
	public static WaypointStore store() {
		if (store == null) {
			store = new WaypointStore(Minecraft.getInstance().gameDir);
		}
		return store;
	}

	/** Whether the Hide key has hidden every waypoint. */
	public static boolean hidden() {
		return hidden;
	}

	/** Opens the waypoints of the world or server you are in (none outside a world). */
	public static void refreshWorld() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.world == null || mc.player == null) {
			store().open(null);
			return;
		}
		String key;
		IntegratedServer server = mc.getServer();
		ServerListEntry entry = mc.getCurrentServerEntry();
		if (mc.isIntegratedServerRunning() && server != null) {
			key = "sp-" + server.getWorldSaveName();
		} else if (entry != null && entry.ip != null) {
			key = "mp-" + entry.ip;
		} else {
			key = "mp-unknown";
		}
		store().open(key);
	}

	@Override
	public void tick() {
		Minecraft mc = Minecraft.getInstance();
		refreshWorld();
		ClientPlayerEntity player = mc.player;
		boolean play = mc.screen == null;
		boolean create = play && down(createKey.code());
		if (create && !createWas) {
			toggleHere(player);
		}
		createWas = create;
		boolean hide = play && down(hideKey.code());
		if (hide && !hideWas) {
			hidden = !hidden;
			mc.gui.setOverlayMessage(hidden ? "Waypoints hidden" : "Showing waypoints", false);
		}
		hideWas = hide;
		boolean list = play && down(listKey.code());
		if (list && !listWas) {
			com.flintclient.client.control.rshift.ModMenuScreen.showWaypoints();
			mc.openScreen(new com.flintclient.client.control.rshift.ModMenuScreen(null));
		}
		listWas = list;
		boolean dead = player.getHealth() <= 0;
		if (dead && !wasDead && deathWaypoints.on()) {
			store().addDeath(floor(player.x), floor(player.y), floor(player.z), player.dimension, deathLimit.value() == 0 ? Integer.MAX_VALUE : deathLimit.value());
		}
		wasDead = dead;
		if (!dead) {
			reachDeaths(player);
		}
	}

	/** A death waypoint goes once you get back to it (after having been more than 12 blocks away). */
	private void reachDeaths(ClientPlayerEntity player) {
		for (Waypoint w : store().array()) {
			if (w.death() && w.dimension() == player.dimension) {
				double d = w.distanceTo(player.x, player.y, player.z);
				if (d > 12) {
					AWAY.add(w);
				} else if (d < 2.5 && AWAY.remove(w)) {
					store().remove(w);
					mc().gui.setOverlayMessage("Reached your death point", false);
					return;
				}
			}
		}
	}

	/** Death waypoints you have been away from since they were made. */
	private static final java.util.Set<Waypoint> AWAY = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Waypoint, Boolean>());

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	private static boolean down(int code) {
		return code != 0 && (code > 0 ? Keyboard.isKeyDown(code) : Mouse.isButtonDown(code + 100));
	}

	private static int floor(double v) {
		return (int) Math.floor(v);
	}

	/**
	 * wWaypoints' quick create: the block you stand on (the one under your feet; in the air, the one below you), or with Sneak
	 * the block you look at up to 16 away. A waypoint already there is removed instead.
	 */
	private void toggleHere(ClientPlayerEntity player) {
		Minecraft mc = Minecraft.getInstance();
		int x;
		int y;
		int z;
		if (player.isSneaking()) {
			HitResult hit = player.rayTrace(16, 1);
			if (hit == null || hit.type != HitResult.Type.BLOCK) {
				mc.gui.setOverlayMessage("Look at a block within 16 blocks", false);
				return;
			}
			BlockPos pos = hit.getPos();
			x = pos.getX();
			y = pos.getY();
			z = pos.getZ();
		} else {
			x = floor(player.x);
			z = floor(player.z);
			int feet = floor(player.y);
			y = player.onGround && player.y - feet >= 0.5 ? feet : feet - 1;
		}
		WaypointStore s = store();
		Waypoint there = s.at(x, y, z, player.dimension);
		if (there != null) {
			s.remove(there);
			mc.gui.setOverlayMessage("Waypoint removed", false);
			return;
		}
		int rgb = randomColors.on() ? s.nextColor() : 0xFFFFFF;
		int dimension = player.dimension;
		if (askName.on()) {
			// The shared window asks for the name and colour first (Create or Cancel); the place is the one picked now.
			int fx = x;
			int fy = y;
			int fz = z;
			com.flintclient.client.platform.MenuHost.show(new com.flintclient.client.control.rshift.WaypointPromptScreen(null,
				"Waypoint " + (s.all().size() + 1), x, y, z, placeName(dimension), rgb, (name, color) -> {
					if (s.add(new Waypoint(name, fx, fy, fz, dimension, color))) {
						mc.gui.setOverlayMessage("Added " + name + " at " + fx + ", " + fy + ", " + fz, false);
					}
				}));
			return;
		}
		if (s.add(new Waypoint("", x, y, z, dimension, rgb))) {
			mc.gui.setOverlayMessage("Waypoint at " + x + ", " + y + ", " + z, false);
		}
	}

	/** 1.8.9's dimensions by name. */
	private static String placeName(int dimension) {
		return dimension == -1 ? "The Nether" : dimension == 1 ? "The End" : "Overworld";
	}

	/** "Add here" from the list: a waypoint on the block you stand on, named after the count. */
	public Waypoint addHere(String name) {
		Minecraft mc = Minecraft.getInstance();
		ClientPlayerEntity player = mc.player;
		if (player == null) {
			return null;
		}
		refreshWorld();
		int feet = floor(player.y);
		int y = player.onGround && player.y - feet >= 0.5 ? feet : feet - 1;
		Waypoint w = new Waypoint(name, floor(player.x), y, floor(player.z), player.dimension, randomColors.on() ? store().nextColor() : 0xFFFFFF);
		return store().add(w) ? w : null;
	}

	// ---------- QA (dev tours call these by reflection) ----------

	/** Adds a waypoint at ({@code x}, {@code y}, {@code z}) in your dimension. */
	public static String qaAdd(String name, String x, String y, String z) {
		Minecraft mc = Minecraft.getInstance();
		refreshWorld();
		Waypoint w = new Waypoint(name.replace('_', ' '), Integer.parseInt(x), Integer.parseInt(y), Integer.parseInt(z), mc.player.dimension,
			store().nextColor());
		return store().add(w) ? "added " + w.title() : "not added";
	}

	/** Dies for a moment as far as death waypoints go: one where you stand. */
	public static String qaDeath() {
		Minecraft mc = Minecraft.getInstance();
		refreshWorld();
		store().addDeath(floor(mc.player.x), floor(mc.player.y), floor(mc.player.z), mc.player.dimension, 8);
		return "death waypoint";
	}

	/** Presses Create (as a tap of N would). */
	public static String qaCreate() {
		com.flintclient.legacy.Flint.modules().waypoints.toggleHere(Minecraft.getInstance().player);
		return store().array().length + " waypoints";
	}
}
