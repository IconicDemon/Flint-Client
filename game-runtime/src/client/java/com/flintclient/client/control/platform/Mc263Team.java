package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.LookMods.TeamTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;

/**
 * Minecraft 26.3's side of Team Tracker: your teammates (whoever is on your scoreboard team, as servers set teams for
 * Bedwars and the like), a marker over each (seen through walls, with how far away), and their bearings for the compass
 * on the HUD. Fault-isolated.
 */
public final class Mc263Team {
	private static final HookGuard GUARD = new HookGuard("team tracker",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final int TRACKER = Mods.index(TeamTracker.ID);
	public static final int MAX = 24;

	private Mc263Team() {
	}

	/** Each frame: a marker over each teammate in range. */
	public static void frame() {
		if (!GUARD.on() || !Mods.on(TRACKER)) {
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			LocalPlayer player = mc.player;
			ClientLevel level = mc.level;
			Team team = player == null ? null : player.getTeam();
			if (level == null || team == null) {
				return;
			}
			ModuleState s = Mods.state(TRACKER);
			if (!s.bool(TeamTracker.MARKERS)) {
				return;
			}
			float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
			double limit = s.bool(TeamTracker.LIMIT) ? s.integer(TeamTracker.DISTANCE) : Double.MAX_VALUE;
			float size = s.integer(TeamTracker.MARKER_SIZE) / 7.0F;
			boolean distanceText = s.bool(TeamTracker.DISTANCE_TEXT);
			Vec3 eye = player.getEyePosition(partial);
			for (Entity entity : level.entitiesForRendering()) {
				if (entity == player || !(entity instanceof LivingEntity) || entity.getTeam() != team || !entity.isAlive()) {
					continue;
				}
				Vec3 at = entity.getPosition(partial);
				double distance = eye.distanceTo(at);
				if (distance > limit) {
					continue;
				}
				int rgb = color(s, team);
				Vec3 top = at.add(0, entity.getBbHeight() + 0.75 + 0.06 * Math.min(distance, 60) / 10, 0);
				Gizmos.billboardText("▼", top, TextGizmo.Style.forColorAndCentered(0xFF000000 | rgb).withScale(TextGizmo.Style.DEFAULT_SCALE * 2.6F * size))
					.setAlwaysOnTop();
				if (distanceText) {
					Gizmos.billboardText(Math.round(distance) + "m", top.add(0, 0.45 * size, 0),
						TextGizmo.Style.forColorAndCentered(0xFFFFFFFF).withScale(TextGizmo.Style.DEFAULT_SCALE * 1.3F * size)).setAlwaysOnTop();
				}
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** A teammate's colour: the one color chosen, else the team's own (white for a team without one). */
	private static int color(ModuleState s, Team team) {
		if (s.bool(TeamTracker.OVERRIDE)) {
			return s.rgb(TeamTracker.COLOR);
		}
		return team instanceof PlayerTeam playerTeam ? com.flintclient.client.compat.McCompat.teamRgb(playerTeam, 0xFFFFFF) & 0xFFFFFF : 0xFFFFFF;
	}

	/**
	 * For the compass: each teammate's bearing from where you look (degrees, -180 to 180, positive to the right) and colour,
	 * nearest first; returns how many (at most {@code bearings.length}). 0 when the compass is off or there is no team.
	 */
	public static int bearings(float[] bearings, int[] colors) {
		if (!GUARD.on()) {
			return 0;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			LocalPlayer player = mc.player;
			ClientLevel level = mc.level;
			Team team = player == null ? null : player.getTeam();
			ModuleState s = Mods.state(TRACKER);
			if (level == null || team == null || !s.bool(TeamTracker.COMPASS)) {
				return 0;
			}
			double limit = s.bool(TeamTracker.LIMIT) ? s.integer(TeamTracker.DISTANCE) : Double.MAX_VALUE;
			int n = 0;
			for (Entity entity : level.entitiesForRendering()) {
				if (n == bearings.length) {
					break;
				}
				if (entity == player || !(entity instanceof LivingEntity) || entity.getTeam() != team || !entity.isAlive()
					|| entity.distanceTo(player) > limit) {
					continue;
				}
				double dx = entity.getX() - player.getX();
				double dz = entity.getZ() - player.getZ();
				// Minecraft's yaw: 0 faces +Z, 90 faces -X.
				float toward = (float) (Mth.atan2(-dx, dz) * Mth.RAD_TO_DEG);
				bearings[n] = Mth.wrapDegrees(toward - player.getYRot());
				colors[n] = 0xFF000000 | color(s, team);
				n++;
			}
			return n;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return 0;
		}
	}
}
