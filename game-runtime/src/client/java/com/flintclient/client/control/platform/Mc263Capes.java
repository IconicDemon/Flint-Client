package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.CapeSim;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.VisualMods.WavyCapes;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Minecraft's side of Wavy Capes (Wavey Capes' behaviour; Flint's own code). Each player whose cape is drawn gets a
 * {@link CapeSim}, moved every game tick by that player's motion as Wavey Capes reads it (the cloak's lag behind the
 * body, falling, jumping, strafing, crouching, swimming); right before Minecraft draws a cape, its part is straightened and
 * draws the cloth instead of its single box: sixteen rows, one pixel each, each turned to its link's angle and leaning six
 * degrees out as Wavey Capes draws them, textured exactly as Minecraft's cape box, smooth or in blocky steps. Waves (wind)
 * ripple down it, with any movement. A cape in a menu picture, a player without a cape and the elytra stay Minecraft's.
 * Render thread; fault-isolated (a failure leaves capes as Minecraft draws them).
 */
public final class Mc263Capes {
	private static final int MODULE = Mods.index(WavyCapes.ID);
	private static final HookGuard GUARD = new HookGuard("wavy capes",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	/** A cape not drawn for this many ticks is forgotten (Wavey Capes' idle limit). */
	private static final int IDLE = 100;
	private static final int ROWS = CapeSim.SEGMENTS;
	private static final int VANILLA = 0;

	private static final Int2ObjectOpenHashMap<Cape> CAPES = new Int2ObjectOpenHashMap<>();
	private static final float[] PX = new float[ROWS + 1];
	private static final float[] PY = new float[ROWS + 1];
	private static final float[] PZ = new float[ROWS + 1];
	/** Each row's four edge corners: x, y, z of (left, outside), (right, outside), (left, inside), (right, inside). */
	private static final float[][] CORNERS = new float[(ROWS + 1) * 2][12];
	private static final Vector3f POS = new Vector3f();
	private static final Vector3f NORMAL = new Vector3f();

	private Mc263Capes() {
	}

	/** One player's cape: the cloth, and how this frame draws it. */
	private static final class Cape implements PartDrawing {
		CapeSim sim;
		int idle;
		float partial;
		float back;
		boolean blocky;
		boolean waves;
		boolean underwater;
		int movement;
		float lean;
		float lean2;

		@Override
		public void draw(PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay, int color) {
			Mc263Capes.draw(this, pose, buffer, light, overlay, color);
		}
	}

	private static int kind(int movement) {
		return switch (movement) {
			case 1 -> CapeSim.FLAT;
			case 3 -> CapeSim.DUNGEONS;
			default -> CapeSim.THREE_D;
		};
	}

	/** From the client tick: every simulated cape moves one tick; ones not drawn for a while are dropped. */
	public static void tick() {
		if (CAPES.isEmpty() || !GUARD.on()) {
			return;
		}
		try {
			ClientLevel level = Minecraft.getInstance().level;
			if (level == null || !Mods.on(MODULE)) {
				CAPES.clear();
				return;
			}
			ModuleState settings = Mods.state(MODULE);
			int movement = settings.option(WavyCapes.MOVEMENT);
			var it = CAPES.int2ObjectEntrySet().fastIterator();
			while (it.hasNext()) {
				var entry = it.next();
				Cape cape = entry.getValue();
				Entity entity = level.getEntity(entry.getIntKey());
				if (++cape.idle > IDLE || !(entity instanceof LivingEntity living)) {
					it.remove();
					continue;
				}
				if (movement == VANILLA) {
					cape.sim = null;
					continue;
				}
				if (cape.sim == null || cape.sim.kind() != kind(movement)) {
					// A new cloth settles first: pushed once, then five ticks with the wearer's motion (as Wavey Capes starts one).
					cape.sim = new CapeSim(kind(movement));
					CapeSim.Settings s = settings(settings, living);
					cape.sim.step(1, 1, 0, false, s);
					for (int i = 0; i < 5; i++) {
						step(cape.sim, living, settings);
					}
				}
				step(cape.sim, living, settings);
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			CAPES.clear();
		}
	}

	private static CapeSim.Settings settings(ModuleState settings, LivingEntity living) {
		float gravity = settings.integer(WavyCapes.GRAVITY) / (living.isUnderWater() ? 10F : 1F);
		float gx = 0;
		float gy = -1;
		if (settings.bool(WavyCapes.GRAVITY_VECTOR)) {
			// Down along the body, however it's turned (flying, swimming): its pitch from the model's.
			float pitch = bodyPitch(living);
			double r = Math.toRadians(pitch);
			gx = (float) (Math.sin(r));
			gy = (float) (-Math.cos(r));
		} else if (living.isVisuallySwimming()) {
			double r = Math.toRadians(living.getXRot() + 90);
			gx = (float) (-Math.sin(r) * -1);
			gy = (float) (Math.cos(r) * -1);
		}
		// The sim's x grows towards the body: gravity "away" is its negative x.
		return new CapeSim.Settings(gravity, -gx, -gy, 0);
	}

	/** How far the body is pitched forward (degrees): flying with an elytra, swimming. */
	private static float bodyPitch(LivingEntity living) {
		if (living.isFallFlying() || living.isVisuallySwimming()) {
			return living.getXRot() + 90;
		}
		return 0;
	}

	/** One tick of Wavey Capes' motion for {@code living}'s cape: the cloak's lag along the body, the rise and fall, strafing. */
	private static void step(CapeSim sim, LivingEntity living, ModuleState settings) {
		float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
		double cloakX = living.getX();
		double cloakZ = living.getZ();
		if (living instanceof ClientAvatarEntity avatar) {
			cloakX = avatar.avatarState().getInterpolatedCloakX(partial);
			cloakZ = avatar.avatarState().getInterpolatedCloakZ(partial);
		}
		double d = cloakX - living.getX();
		double m = cloakZ - living.getZ();
		float bodyYaw = living.yBodyRotO + (living.yBodyRot - living.yBodyRotO);
		double o = Math.sin(Math.toRadians(bodyYaw));
		double p = -Math.cos(Math.toRadians(bodyYaw));
		float height = settings.integer(WavyCapes.HEIGHT);
		if (living.isUnderWater()) {
			height *= 2;
		}
		double fall = Math.max(0, Math.min(1, (living.yo - living.getY()) * 10));
		float yaw = (float) Math.toRadians(-living.getYRot());
		float strave = (float) (Math.cos(yaw) * (living.getX() - living.xo) - Math.sin(yaw) * (living.getZ() - living.zo));
		double back = d * o + m * p + fall;
		double up = (living.getY() - living.yo) * height;
		double left = -strave * settings.integer(WavyCapes.SIDEWAYS);
		if (!settings.bool(WavyCapes.GRAVITY_VECTOR) && living.isVisuallySwimming()) {
			double r = Math.toRadians(living.getXRot() + 90);
			double bx = Math.cos(r) * back - Math.sin(r) * up;
			double by = Math.sin(r) * back + Math.cos(r) * up;
			back = bx;
			up = by;
		}
		sim.step((float) back, (float) up, (float) left, living.isCrouching(), settings(settings, living));
	}

	/** From the end of the cape model's setup, right before it is drawn: the cloth instead of Minecraft's box, or not. */
	public static void setup(ModelPart part, AvatarRenderState state) {
		CustomPart custom = (CustomPart) (Object) part;
		if (!GUARD.on()) {
			custom.flint$drawing(null);
			return;
		}
		try {
			if (!Mods.on(MODULE) || Mc263Skins.inGuiPicture()) {
				custom.flint$drawing(null);
				return;
			}
			ModuleState settings = Mods.state(MODULE);
			LocalPlayer self = Minecraft.getInstance().player;
			boolean mine = self != null && state.id == self.getId();
			if (!mine && !settings.bool(WavyCapes.OTHERS)) {
				custom.flint$drawing(null);
				return;
			}
			int movement = settings.option(WavyCapes.MOVEMENT);
			boolean waves = settings.option(WavyCapes.WIND_MODE) == 1;
			if (movement == VANILLA && !waves) {
				// Minecraft's own cape, as Wavey Capes leaves it.
				custom.flint$drawing(null);
				return;
			}
			Cape cape = CAPES.get(state.id);
			if (cape == null) {
				cape = new Cape();
				CAPES.put(state.id, cape);
			}
			cape.idle = 0;
			if (movement != VANILLA && cape.sim == null) {
				// Its first tick hasn't come yet.
				custom.flint$drawing(null);
				return;
			}
			ClientLevel level = Minecraft.getInstance().level;
			Entity entity = level == null ? null : level.getEntity(state.id);
			cape.partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
			cape.back = Mc263Skins.jacketDepth(state);
			cape.blocky = settings.option(WavyCapes.STYLE) == 1;
			cape.waves = waves;
			cape.underwater = entity != null && entity.isUnderWater();
			cape.movement = movement;
			cape.lean = 6 + state.capeLean / 2 + state.capeFlap;
			cape.lean2 = state.capeLean2 / 2;
			// The cloth hangs and bends itself: no lean of Minecraft's on top.
			part.setRotation(0, (float) Math.PI, 0);
			custom.flint$drawing(cape);
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			custom.flint$drawing(null);
		}
	}

	/** How many capes are being simulated (for the dev tour). */
	public static int simulated() {
		return CAPES.size();
	}

	/** Your cape's tip now, behind and below its top (for the dev tour), or why there is none. */
	public static String yourTip() {
		LocalPlayer self = Minecraft.getInstance().player;
		Cape cape = self == null ? null : CAPES.get(self.getId());
		if (cape == null || cape.sim == null) {
			return "no cape for you";
		}
		int last = CapeSim.POINTS - 1;
		return String.format(java.util.Locale.ROOT, "tip away %.2f down %.2f side %.2f yaw=%.1f", cape.sim.away(last, 1), cape.sim.down(last, 1),
			cape.sim.side(last, 1), self.yBodyRot);
	}

	/** Wavey Capes' waves: a ripple running down the cape, degrees for part {@code part}. */
	private static float wave(int part, boolean underwater) {
		long phase = System.currentTimeMillis() / (underwater ? 9 : 3) % 360L;
		float along = (part + 1) / 16.0F;
		return (float) (Math.sin(Math.toRadians(along * 360.0F - phase)) * 3.0);
	}

	/**
	 * The cloth in the cape part's space (pixels; x to the wearer's right, y down, z towards the body): each row from its
	 * point, turned to its link's angle and leaning out by six degrees (plus the waves), one pixel thick and ten wide.
	 */
	private static void draw(Cape cape, PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay, int color) {
		float t = cape.partial;
		CapeSim sim = cape.sim;
		for (int part = 0; part < ROWS; part++) {
			float side;
			float down;
			float away;
			float theta;
			float tilt;
			if (sim == null) {
				// Minecraft's lean for the whole cape, rippled by the waves part by part.
				side = 0;
				down = part;
				away = 0;
				theta = 0;
				tilt = cape.lean + (cape.waves ? wave(part, cape.underwater) : 0);
			} else {
				side = sim.side(part, t);
				down = sim.down(part, t);
				away = sim.away(part, t);
				theta = sim.linkAngle(part, t);
				tilt = 6 + (cape.waves ? wave(part, cape.underwater) : 0);
			}
			double th = Math.toRadians(theta);
			float bottomDown = down + (float) Math.cos(th);
			float bottomAway = away + (float) Math.sin(th);
			double tr = Math.toRadians(tilt);
			float c = (float) Math.cos(tr);
			float s = (float) Math.sin(tr);
			float sideTilt = sim == null ? (float) Math.sin(Math.toRadians(cape.lean2)) : 0;
			PX[part] = side + down * sideTilt;
			PY[part] = down * c - away * s;
			PZ[part] = -(down * s + away * c) - 0.5F - cape.back;
			if (part == ROWS - 1) {
				PX[ROWS] = side + bottomDown * sideTilt;
				PY[ROWS] = bottomDown * c - bottomAway * s;
				PZ[ROWS] = -(bottomDown * s + bottomAway * c) - 0.5F - cape.back;
			}
		}
		Matrix4f matrix = pose.pose();
		if (cape.blocky) {
			for (int k = 0; k < ROWS; k++) {
				// Each row a box of its own, square to its own slope: steps, pixel by pixel.
				frame(k, k + 1, k, 2 * k);
				frame(k, k + 1, k + 1, 2 * k + 1);
				row(pose, matrix, buffer, light, overlay, color, 2 * k, 2 * k + 1, k, true, true);
			}
			return;
		}
		for (int i = 0; i <= ROWS; i++) {
			frame(Math.max(0, i - 1), Math.min(ROWS, i + 1), i, i);
		}
		for (int k = 0; k < ROWS; k++) {
			row(pose, matrix, buffer, light, overlay, color, k, k + 1, k, k == 0, k == ROWS - 1);
		}
	}

	/**
	 * The four corners at point {@code at} into {@code CORNERS[slot]}, square to the line from point {@code from} to point
	 * {@code to}: five pixels to each side, half a pixel out and in.
	 */
	private static void frame(int from, int to, int at, int slot) {
		float tx = PX[to] - PX[from];
		float ty = PY[to] - PY[from];
		float tz = PZ[to] - PZ[from];
		float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
		if (tl < 1e-5F) {
			tx = 0;
			ty = 1;
			tz = 0;
		} else {
			tx /= tl;
			ty /= tl;
			tz /= tl;
		}
		// Across: the wearer's right, made square to the line.
		float ax = 1 - tx * tx;
		float ay = -tx * ty;
		float az = -tx * tz;
		float al = (float) Math.sqrt(ax * ax + ay * ay + az * az);
		if (al < 1e-5F) {
			ax = 1;
			ay = 0;
			az = 0;
		} else {
			ax /= al;
			ay /= al;
			az /= al;
		}
		// Through: across × along, towards the body.
		float nx = ay * tz - az * ty;
		float ny = az * tx - ax * tz;
		float nz = ax * ty - ay * tx;
		float[] c = CORNERS[slot];
		for (int k = 0; k < 4; k++) {
			float side = (k & 1) == 0 ? -5 : 5;
			float depth = k < 2 ? -0.5F : 0.5F;
			c[k * 3] = PX[at] + ax * side + nx * depth;
			c[k * 3 + 1] = PY[at] + ay * side + ny * depth;
			c[k * 3 + 2] = PZ[at] + az * side + nz * depth;
		}
	}

	/**
	 * Row {@code row} of the cape between the corners in slots {@code top} and {@code bottom}: outside, inside and both
	 * edges, and the top or bottom edge when asked; textured as Minecraft's cape box (64 by 32).
	 */
	private static void row(PoseStack.Pose pose, Matrix4f m, VertexConsumer b, int light, int overlay, int color, int top, int bottom, int row,
		boolean cap, boolean foot) {
		float[] tp = CORNERS[top];
		float[] bt = CORNERS[bottom];
		float v0 = (1 + row) / 32F;
		float v1 = (2 + row) / 32F;
		// Corners: 0 left-outside, 1 right-outside, 2 left-inside, 3 right-inside (Minecraft's t0, t1, l0, l1 at the top).
		// Outside (Minecraft's north face): t1, t0, t3, t2.
		quad(pose, m, b, light, overlay, color, tp, 1, tp, 0, bt, 0, bt, 1, 11, 1, 1, 11, v0, v1);
		// Inside (south): l0, l1, l2, l3.
		quad(pose, m, b, light, overlay, color, tp, 2, tp, 3, bt, 3, bt, 2, 22, 12, 12, 22, v0, v1);
		// Left edge (west): t0, l0, l3, t3.
		quad(pose, m, b, light, overlay, color, tp, 0, tp, 2, bt, 2, bt, 0, 1, 0, 0, 1, v0, v1);
		// Right edge (east): l1, t1, t2, l2.
		quad(pose, m, b, light, overlay, color, tp, 3, tp, 1, bt, 1, bt, 3, 12, 11, 11, 12, v0, v1);
		if (cap) {
			// The top edge (down): l1, l0, t0, t1; a step's top in blocky rows reads its row's colour.
			float tv0 = top == 0 ? 0 : v0 + 0.5F / 32;
			float tv1 = top == 0 ? 1 / 32F : v0 + 0.5F / 32;
			quadUv(pose, m, b, light, overlay, color, tp, 3, tp, 2, tp, 0, tp, 1, 11 / 64F, tv0, 1 / 64F, tv0, 1 / 64F, tv1, 11 / 64F, tv1);
		}
		if (foot) {
			// The bottom edge (up): t2, t3, l3, l2.
			boolean last = row == ROWS - 1;
			float uA = last ? 21 / 64F : 11 / 64F;
			float uB = last ? 11 / 64F : 1 / 64F;
			float fv0 = last ? 1 / 32F : v1 - 0.5F / 32;
			float fv1 = last ? 0 : v1 - 0.5F / 32;
			quadUv(pose, m, b, light, overlay, color, bt, 1, bt, 0, bt, 2, bt, 3, uA, fv0, uB, fv0, uB, fv1, uA, fv1);
		}
	}

	/** A quad of four corners (slot arrays and corner indices) with Minecraft's box texture order: u per corner, v top or bottom. */
	private static void quad(PoseStack.Pose pose, Matrix4f m, VertexConsumer b, int light, int overlay, int color, float[] p0, int c0, float[] p1, int c1,
		float[] p2, int c2, float[] p3, int c3, int u0, int u1, int u2, int u3, float vTop, float vBottom) {
		quadUv(pose, m, b, light, overlay, color, p0, c0, p1, c1, p2, c2, p3, c3, u0 / 64F, vTop, u1 / 64F, vTop, u2 / 64F, vBottom, u3 / 64F, vBottom);
	}

	private static void quadUv(PoseStack.Pose pose, Matrix4f m, VertexConsumer b, int light, int overlay, int color, float[] p0, int c0, float[] p1,
		int c1, float[] p2, int c2, float[] p3, int c3, float u0, float v0, float u1, float v1, float u2, float v2, float u3, float v3) {
		// The normal from the diagonals (robust for a slightly bent quad), in the part's space, then to the pose's.
		float ax = p2[c2 * 3] - p0[c0 * 3];
		float ay = p2[c2 * 3 + 1] - p0[c0 * 3 + 1];
		float az = p2[c2 * 3 + 2] - p0[c0 * 3 + 2];
		float bx = p3[c3 * 3] - p1[c1 * 3];
		float by = p3[c3 * 3 + 1] - p1[c1 * 3 + 1];
		float bz = p3[c3 * 3 + 2] - p1[c1 * 3 + 2];
		NORMAL.set(ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx);
		if (NORMAL.lengthSquared() < 1e-12F) {
			NORMAL.set(0, 0, 1);
		}
		NORMAL.normalize();
		pose.transformNormal(NORMAL, NORMAL);
		vertex(m, b, p0, c0, u0, v0, light, overlay, color);
		vertex(m, b, p1, c1, u1, v1, light, overlay, color);
		vertex(m, b, p2, c2, u2, v2, light, overlay, color);
		vertex(m, b, p3, c3, u3, v3, light, overlay, color);
	}

	private static void vertex(Matrix4f m, VertexConsumer b, float[] p, int c, float u, float v, int light, int overlay, int color) {
		m.transformPosition(p[c * 3] / 16, p[c * 3 + 1] / 16, p[c * 3 + 2] / 16, POS);
		b.addVertex(POS.x(), POS.y(), POS.z(), color, u, v, overlay, light, NORMAL.x(), NORMAL.y(), NORMAL.z());
	}
}
