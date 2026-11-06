package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.CapeCloth;
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
import org.jspecify.annotations.Nullable;

/**
 * Minecraft's side of Wavy Capes (Flint's own code). Right before Minecraft draws a cape, its part is straightened and draws
 * Flint's cloth instead of its single box, textured exactly as Minecraft's cape box (so Mojang's and Flint Store capes, still or
 * animated, look as they do on the box), smooth or in blocky steps.
 * <ul>
 * <li>Realistic (the default): {@link CapeCloth}, the owner's Feather client's realistic cloaks, stepped 60 times a second on the
 * frame clock and drawn between its last two steps, so it moves on every frame. It reads the wearer's motion as it is drawn:
 * forward, up and down, sideways and the body's turn; crouching, swimming, underwater.</li>
 * <li>Simulation, Simulation 3D and Dungeons: Wavey Capes' cloth ({@link CapeSim}), moved every game tick by the wearer's motion as
 * Wavey Capes reads it, drawn between the last two ticks.</li>
 * <li>Vanilla: Minecraft's own cape; with waves on, Minecraft's lean drawn here so the waves can ripple down it.</li>
 * </ul>
 * A change of movement crossfades from the cape's last shape to the new one, and waves ease in and out, so nothing jumps. A cape in
 * a menu picture, a player without a cape and the elytra stay Minecraft's. Render thread; no allocation per frame once a cape is
 * known; fault-isolated (a failure leaves capes as Minecraft draws them).
 */
public final class Mc263Capes {
	private static final int MODULE = Mods.index(WavyCapes.ID);
	private static final HookGuard GUARD = new HookGuard("wavy capes",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	/** A cape not drawn for this many ticks is forgotten (Wavey Capes' idle limit). */
	private static final int IDLE = 100;
	private static final int ROWS = CapeSim.SEGMENTS;
	private static final int POINTS = ROWS + 1;
	/**
	 * The body's crouch lean (Minecraft's half a radian), which the cape shares here: it hangs from the body, so Realistic's
	 * crouching angle (Feather's, from straight down) is less this. 1.21.1 hangs the cape upright from the model instead.
	 */
	private static final float CROUCH_LEAN = 28.647888F;
	/** A new Realistic cloth settles into the wearer's motion for this many steps (three quarters of a second) before it is drawn. */
	private static final int SETTLE_STEPS = 45;
	/** A move further than this in one tick (blocks) is a teleport, not motion. */
	private static final double TELEPORT = 10;
	/** How long a change of movement crossfades, and how long the waves take to come and go (seconds). */
	private static final float BLEND_SECONDS = 0.3F;
	private static final float WAVE_SECONDS = 0.4F;

	private static final Int2ObjectOpenHashMap<Cape> CAPES = new Int2ObjectOpenHashMap<>();
	private static final CapeCloth.Motion MOTION = new CapeCloth.Motion();
	/** The cape's centre line, per chain (0 the edges', 1 the middle's), in the part's pixels: x, y, z of each point. */
	private static final int EDGES = 0;
	private static final int MIDDLE = 1;
	private static final float[] LINE = new float[2 * POINTS * 3];
	/** Smooth: four columns across (edge, middle, middle, edge, as Feather draws its cloaks), their centres, outsides and insides. */
	private static final int COLUMNS = 4;
	private static final float[] COLUMN_X = {-5, -5F / 3, 5F / 3, 5};
	private static final int[] COLUMN_CHAIN = {EDGES, MIDDLE, MIDDLE, EDGES};
	private static final float[] ACROSS = new float[POINTS * 3];
	private static final float[] CENTRE = new float[COLUMNS * POINTS * 3];
	private static final float[] OUTER = new float[COLUMNS * POINTS * 3];
	private static final float[] INNER = new float[COLUMNS * POINTS * 3];
	/** Blocky: the middle line, and each row's four edge corners: (left, outside), (right, outside), (left, inside), (right, inside). */
	private static final float[] PX = new float[POINTS];
	private static final float[] PY = new float[POINTS];
	private static final float[] PZ = new float[POINTS];
	private static final float[][] CORNERS = new float[POINTS * 2][12];
	private static final Vector3f POS = new Vector3f();
	private static final Vector3f NORMAL = new Vector3f();
	/** Your cape as last drawn (dev tour): its tip's x, y, z and its middle row's, in the cape part's pixels; and how many draws. */
	private static final float[] DEV_TIP = new float[6];
	private static int devDraws;

	private Mc263Capes() {
	}

	/** One player's cape: its cloth, its last shape, and how this frame draws it. */
	private static final class Cape implements PartDrawing {
		@Nullable CapeSim sim;
		@Nullable CapeCloth cloth;
		int idle;
		/** The movement it was last set up with (-1: none yet). */
		int movement = -1;
		/** When it was last set up (System.nanoTime), 0 never. */
		long seen;
		/** 0 to 1 from {@link #from} (its shape when the movement changed) to the new movement's. */
		float blend = 1;
		/** How strong the waves are now, 0 to 1. */
		float waves;
		/** Whether {@link #last} holds a shape drawn here (not Minecraft's own). */
		boolean drawn;
		final float[] last = new float[LINE.length];
		final float[] from = new float[LINE.length];
		float partial;
		float back;
		boolean blocky;
		boolean underwater;
		boolean mine;
		float lean;
		float lean2;

		@Override
		public void draw(PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay, int color) {
			Mc263Capes.draw(this, pose, buffer, light, overlay, color);
		}
	}

	private static boolean wavey(int movement) {
		return movement == WavyCapes.SIMULATION || movement == WavyCapes.SIMULATION_3D || movement == WavyCapes.DUNGEONS;
	}

	private static int kind(int movement) {
		return switch (movement) {
			case WavyCapes.SIMULATION -> CapeSim.FLAT;
			case WavyCapes.DUNGEONS -> CapeSim.DUNGEONS;
			default -> CapeSim.THREE_D;
		};
	}

	/** From the client tick: Wavey Capes' cloths move one tick; capes not drawn for a while are dropped. */
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
			// Paused, the wearers keep their last tick's motion: the cloth would be pushed by it over and over.
			boolean paused = Minecraft.getInstance().isPaused();
			var it = CAPES.int2ObjectEntrySet().fastIterator();
			while (it.hasNext()) {
				var entry = it.next();
				Cape cape = entry.getValue();
				Entity entity = level.getEntity(entry.getIntKey());
				if (++cape.idle > IDLE || !(entity instanceof LivingEntity living)) {
					it.remove();
					continue;
				}
				CapeSim sim = cape.sim;
				if (sim != null && !paused && wavey(movement) && sim.kind() == kind(movement)) {
					step(sim, living, settings);
				}
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			CAPES.clear();
		}
	}

	/** Wavey Capes' cloth of {@code movement}'s kind for {@code cape}: a new one starts settled, as Wavey Capes starts one. */
	private static void ensureSim(Cape cape, LivingEntity living, ModuleState settings, int movement) {
		int kind = kind(movement);
		if (cape.sim != null && cape.sim.kind() == kind) {
			return;
		}
		// Pushed once, then five ticks with the wearer's motion.
		CapeSim sim = new CapeSim(kind);
		sim.step(1, 1, 0, false, settings(settings, living));
		for (int i = 0; i < 5; i++) {
			step(sim, living, settings);
		}
		cape.sim = sim;
	}

	private static CapeSim.Settings settings(ModuleState settings, LivingEntity living) {
		float gravity = settings.integer(WavyCapes.GRAVITY) / (living.isUnderWater() ? 10F : 1F);
		float gx = 0;
		float gy = -1;
		if (settings.bool(WavyCapes.GRAVITY_VECTOR)) {
			// Down along the body, however it's turned (crouching, flying, swimming): its pitch from the model's.
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

	/** How far the body (and the cape hanging from it) is pitched forward, degrees: crouching, flying with an elytra, swimming. */
	private static float bodyPitch(LivingEntity living) {
		if (living.isFallFlying() || living.isVisuallySwimming()) {
			return living.getXRot() + 90;
		}
		return living.isCrouching() ? CROUCH_LEAN : 0;
	}

	/**
	 * One tick of Wavey Capes' motion for {@code living}'s cape: the cloak's lag along the body, the rise and fall, strafing. The
	 * cloak is read as the last tick left it (Wavey Capes read it through the frame's partial tick, which made each tick's push
	 * vary at random by up to a quarter: the cloth shivered while walking).
	 */
	private static void step(CapeSim sim, LivingEntity living, ModuleState settings) {
		double cloakX = living.getX();
		double cloakZ = living.getZ();
		if (living instanceof ClientAvatarEntity avatar) {
			cloakX = avatar.avatarState().getInterpolatedCloakX(1);
			cloakZ = avatar.avatarState().getInterpolatedCloakZ(1);
		}
		double d = cloakX - living.getX();
		double m = cloakZ - living.getZ();
		float bodyYaw = living.yBodyRot;
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

	/**
	 * What moves Realistic's cloth this frame: {@code living}'s motion over its last tick (in its body's frame), how it hangs and
	 * the settings (Wavey Capes' at their defaults are Feather's: gravity 25, height 6, strafe 2).
	 */
	private static CapeCloth.Motion motion(@Nullable LivingEntity living, ModuleState settings) {
		CapeCloth.Motion m = MOTION.still();
		m.gravity = settings.integer(WavyCapes.GRAVITY) / 25F;
		m.height = settings.integer(WavyCapes.HEIGHT) / 6F;
		m.sideways = settings.integer(WavyCapes.SIDEWAYS) / 2F;
		if (living == null) {
			return m;
		}
		double dx = living.getX() - living.xo;
		double dy = living.getY() - living.yo;
		double dz = living.getZ() - living.zo;
		if (Math.abs(dx) > TELEPORT || Math.abs(dy) > TELEPORT || Math.abs(dz) > TELEPORT) {
			dx = dy = dz = 0;
		}
		double yaw = Math.toRadians(living.yBodyRot);
		double forwardX = -Math.sin(yaw);
		double forwardZ = Math.cos(yaw);
		m.forward = (float) (dx * forwardX + dz * forwardZ);
		m.right = (float) (-dx * forwardZ + dz * forwardX);
		m.up = (float) dy;
		float turn = (living.yBodyRot - living.yBodyRotO) % 360;
		m.turn = turn > 180 ? turn - 360 : turn < -180 ? turn + 360 : turn;
		m.pitch = living.getXRot();
		m.swimming = living.isVisuallySwimming();
		m.underwater = living.isUnderWater();
		if (settings.bool(WavyCapes.GRAVITY_VECTOR)) {
			// Towards the ground however the body is turned: hanging from a body pitched forward, the ground is towards the back.
			double r = Math.toRadians(bodyPitch(living));
			m.hangAway = (float) -Math.sin(r);
			m.hangDown = (float) Math.cos(r);
		} else if (living.isCrouching()) {
			m.angle = CapeCloth.CROUCH - CROUCH_LEAN;
		}
		return m;
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
			Minecraft mc = Minecraft.getInstance();
			LocalPlayer self = mc.player;
			boolean mine = self != null && state.id == self.getId();
			if (!mine && !settings.bool(WavyCapes.OTHERS)) {
				custom.flint$drawing(null);
				return;
			}
			int movement = settings.option(WavyCapes.MOVEMENT);
			boolean waves = settings.option(WavyCapes.WIND_MODE) == 1;
			Cape cape = CAPES.get(state.id);
			if (cape == null) {
				cape = new Cape();
				CAPES.put(state.id, cape);
			}
			cape.idle = 0;
			long now = System.nanoTime();
			float dt = cape.seen == 0 ? 0 : Math.min(0.25F, Math.max(0, (now - cape.seen) / 1e9F));
			cape.seen = now;
			ClientLevel level = mc.level;
			Entity entity = level == null ? null : level.getEntity(state.id);
			LivingEntity living = entity instanceof LivingEntity l ? l : null;
			cape.partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
			cape.back = Mc263Skins.jacketDepth(state);
			cape.blocky = settings.option(WavyCapes.STYLE) == 1;
			cape.underwater = entity != null && entity.isUnderWater();
			cape.mine = mine;
			cape.lean = 6 + state.capeLean / 2 + state.capeFlap;
			cape.lean2 = state.capeLean2 / 2;
			if (movement != cape.movement) {
				if (cape.movement >= 0) {
					// Crossfade from what was on screen: the last shape drawn here, or Minecraft's own lean.
					if (!cape.drawn) {
						vanillaLine(cape, 0);
						System.arraycopy(LINE, 0, cape.last, 0, LINE.length);
					}
					System.arraycopy(cape.last, 0, cape.from, 0, LINE.length);
					cape.blend = 0;
				}
				cape.movement = movement;
			}
			cape.blend = Math.min(1, cape.blend + dt / BLEND_SECONDS);
			cape.waves = waves ? Math.min(1, cape.waves + dt / WAVE_SECONDS) : Math.max(0, cape.waves - dt / WAVE_SECONDS);
			if (movement != WavyCapes.REALISTIC) {
				cape.cloth = null;
			}
			if (!wavey(movement)) {
				cape.sim = null;
			}
			if (movement == WavyCapes.VANILLA && cape.waves <= 0 && cape.blend >= 1) {
				// Minecraft's own cape, as Wavey Capes leaves it.
				cape.drawn = false;
				custom.flint$drawing(null);
				return;
			}
			if (movement == WavyCapes.REALISTIC) {
				CapeCloth.Motion motion = motion(living, settings);
				CapeCloth cloth = cape.cloth;
				if (cloth == null) {
					cloth = new CapeCloth();
					cloth.settle(motion, SETTLE_STEPS);
					cloth.advance(now, motion);
					cape.cloth = cloth;
				} else if (mc.isPaused()) {
					cloth.hold(now);
				} else {
					cloth.advance(now, motion);
				}
			} else if (wavey(movement) && living != null) {
				ensureSim(cape, living, settings, movement);
			}
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
		if (cape == null || cape.sim == null && cape.cloth == null) {
			return "no cape for you";
		}
		if (cape.cloth != null) {
			CapeCloth cloth = cape.cloth;
			float a = cloth.alpha();
			int last = CapeCloth.POINTS - 1;
			return String.format(java.util.Locale.ROOT, "realistic tip away %.2f down %.2f side %.2f edge away %.2f yaw=%.1f",
				cloth.away(CapeCloth.MIDDLE, last, a), cloth.down(CapeCloth.MIDDLE, last, a), cloth.side(CapeCloth.MIDDLE, last, a),
				cloth.away(CapeCloth.EDGE, last, a), self.yBodyRot);
		}
		int last = CapeSim.POINTS - 1;
		return String.format(java.util.Locale.ROOT, "tip away %.2f down %.2f side %.2f yaw=%.1f", cape.sim.away(last, 1), cape.sim.down(last, 1),
			cape.sim.side(last, 1), self.yBodyRot);
	}

	/**
	 * Your cape as last drawn, for the dev tour's frame-by-frame trace: its tip's x, y, z and its middle row's (the cape part's
	 * pixels) into {@code out} (six floats); answers how many times it has been drawn (unchanged: not drawn since).
	 */
	public static int devTip(float[] out) {
		System.arraycopy(DEV_TIP, 0, out, 0, DEV_TIP.length);
		return devDraws;
	}

	/**
	 * Wavey Capes' waves: a ripple running down the cape, degrees for part {@code part}; once round every 1.08 seconds (3.24
	 * underwater), read from the frame clock so it moves on every frame.
	 */
	private static float wave(int part, boolean underwater, long now) {
		long period = underwater ? 3_240_000_000L : 1_080_000_000L;
		double phase = Math.floorMod(now, period) * 360.0 / period;
		float along = (part + 1) / 16.0F;
		return (float) (Math.sin(Math.toRadians(along * 360.0F - phase)) * 3.0);
	}

	/** Eased 0 to 1 (smoothstep). */
	private static float ease(float t) {
		return t * t * (3 - 2 * t);
	}

	/**
	 * Point {@code i} of chain {@code chain} into {@link #LINE}: {@code down} below the top, {@code away} behind it and {@code side}
	 * to the right (pixels), the whole leaned out by {@code tilt} degrees round the top (and sideways by {@code sideTilt}), in the
	 * part's space (x to the wearer's right, y down, z towards the body), behind the back and any 3D jacket.
	 */
	private static void put(int chain, int i, float side, float down, float away, float tilt, float sideTilt, float back) {
		double tr = Math.toRadians(tilt);
		float c = (float) Math.cos(tr);
		float s = (float) Math.sin(tr);
		int k = (chain * POINTS + i) * 3;
		LINE[k] = side + down * sideTilt;
		LINE[k + 1] = down * c - away * s;
		LINE[k + 2] = -(down * s + away * c) - 0.5F - back;
	}

	/** Minecraft's lean for the whole cape (both chains), rippled by the waves ({@code waves}: how strong) part by part. */
	private static void vanillaLine(Cape cape, float waves) {
		float sideTilt = (float) Math.sin(Math.toRadians(cape.lean2));
		for (int i = 0; i < POINTS; i++) {
			float tilt = cape.lean + (waves > 0 ? waves * wave(Math.min(i, ROWS - 1), cape.underwater, cape.seen) : 0);
			put(MIDDLE, i, 0, i, 0, tilt, sideTilt, cape.back);
		}
		System.arraycopy(LINE, MIDDLE * POINTS * 3, LINE, EDGES * POINTS * 3, POINTS * 3);
	}

	/** This frame's centre line of {@code cape} into {@link #LINE}, from whatever moves it now. */
	private static void line(Cape cape) {
		float waves = ease(cape.waves);
		CapeCloth cloth = cape.cloth;
		CapeSim sim = cape.sim;
		if (cloth != null) {
			// Realistic: both chains, between the cloth's last two steps; its hanging angle is its own (no lean on top).
			float a = cloth.alpha();
			for (int chain = 0; chain < 2; chain++) {
				int c = chain == EDGES ? CapeCloth.EDGE : CapeCloth.MIDDLE;
				for (int i = 0; i < POINTS; i++) {
					float tilt = waves > 0 ? waves * wave(Math.min(i, ROWS - 1), cape.underwater, cape.seen) : 0;
					put(chain, i, cloth.side(c, i, a), cloth.down(c, i, a), cloth.away(c, i, a), tilt, 0, cape.back);
				}
			}
			return;
		}
		if (sim != null) {
			// Wavey Capes' cloth between its last two ticks, each part leaning out six degrees as Wavey Capes draws it; the bottom
			// continues the last link.
			float t = cape.partial;
			for (int part = 0; part < ROWS; part++) {
				float side = sim.side(part, t);
				float down = sim.down(part, t);
				float away = sim.away(part, t);
				float tilt = 6 + (waves > 0 ? waves * wave(part, cape.underwater, cape.seen) : 0);
				put(MIDDLE, part, side, down, away, tilt, 0, cape.back);
				if (part == ROWS - 1) {
					double th = Math.toRadians(sim.linkAngle(part, t));
					put(MIDDLE, ROWS, side, down + (float) Math.cos(th), away + (float) Math.sin(th), tilt, 0, cape.back);
				}
			}
			System.arraycopy(LINE, MIDDLE * POINTS * 3, LINE, EDGES * POINTS * 3, POINTS * 3);
			return;
		}
		vanillaLine(cape, waves);
	}

	/** The cloth in the cape part's space: its line (crossfaded after a change of movement), then smooth or blocky. */
	private static void draw(Cape cape, PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay, int color) {
		line(cape);
		if (cape.blend < 1) {
			float k = ease(cape.blend);
			float[] from = cape.from;
			for (int i = 0; i < LINE.length; i++) {
				LINE[i] = from[i] + (LINE[i] - from[i]) * k;
			}
		}
		System.arraycopy(LINE, 0, cape.last, 0, LINE.length);
		cape.drawn = true;
		int tip = (MIDDLE * POINTS + ROWS) * 3;
		int mid = (MIDDLE * POINTS + ROWS / 2) * 3;
		if (cape.mine) {
			System.arraycopy(LINE, tip, DEV_TIP, 0, 3);
			System.arraycopy(LINE, mid, DEV_TIP, 3, 3);
			devDraws++;
		}
		Matrix4f matrix = pose.pose();
		if (cape.blocky) {
			for (int i = 0; i < POINTS; i++) {
				int k = (MIDDLE * POINTS + i) * 3;
				PX[i] = LINE[k];
				PY[i] = LINE[k + 1];
				PZ[i] = LINE[k + 2];
			}
			for (int k = 0; k < ROWS; k++) {
				// Each row a box of its own, square to its own slope: steps, pixel by pixel.
				frame(k, k + 1, k, 2 * k);
				frame(k, k + 1, k + 1, 2 * k + 1);
				row(pose, matrix, buffer, light, overlay, color, 2 * k, 2 * k + 1, k, true, true);
			}
			return;
		}
		smooth(pose, matrix, buffer, light, overlay, color);
	}

	/**
	 * The smooth cloth: four columns of points across each row (edge, middle, middle, edge), five pixels to each side of the
	 * line at the edges, square to the middle line there; out and in by half a pixel along the cloth's own normal at each point;
	 * then its faces, textured as Minecraft's cape box (64 by 32): outside, inside, both edges, the top and the bottom.
	 */
	private static void smooth(PoseStack.Pose pose, Matrix4f m, VertexConsumer b, int light, int overlay, int color) {
		int middle = MIDDLE * POINTS * 3;
		for (int r = 0; r < POINTS; r++) {
			int from = middle + Math.max(0, r - 1) * 3;
			int to = middle + Math.min(ROWS, r + 1) * 3;
			float tx = LINE[to] - LINE[from];
			float ty = LINE[to + 1] - LINE[from + 1];
			float tz = LINE[to + 2] - LINE[from + 2];
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
			ACROSS[r * 3] = ax;
			ACROSS[r * 3 + 1] = ay;
			ACROSS[r * 3 + 2] = az;
		}
		for (int c = 0; c < COLUMNS; c++) {
			for (int r = 0; r < POINTS; r++) {
				int src = (COLUMN_CHAIN[c] * POINTS + r) * 3;
				int dst = (c * POINTS + r) * 3;
				for (int j = 0; j < 3; j++) {
					CENTRE[dst + j] = LINE[src + j] + ACROSS[r * 3 + j] * COLUMN_X[c];
				}
			}
		}
		for (int c = 0; c < COLUMNS; c++) {
			for (int r = 0; r < POINTS; r++) {
				int at = (c * POINTS + r) * 3;
				int down = (c * POINTS + Math.min(ROWS, r + 1)) * 3;
				int up = (c * POINTS + Math.max(0, r - 1)) * 3;
				int right = (Math.min(COLUMNS - 1, c + 1) * POINTS + r) * 3;
				int left = (Math.max(0, c - 1) * POINTS + r) * 3;
				float vx = CENTRE[down] - CENTRE[up];
				float vy = CENTRE[down + 1] - CENTRE[up + 1];
				float vz = CENTRE[down + 2] - CENTRE[up + 2];
				float hx = CENTRE[right] - CENTRE[left];
				float hy = CENTRE[right + 1] - CENTRE[left + 1];
				float hz = CENTRE[right + 2] - CENTRE[left + 2];
				// Through: across × along, towards the body.
				float nx = hy * vz - hz * vy;
				float ny = hz * vx - hx * vz;
				float nz = hx * vy - hy * vx;
				float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
				if (nl < 1e-5F) {
					nx = 0;
					ny = 0;
					nz = 1;
				} else {
					nx /= nl;
					ny /= nl;
					nz /= nl;
				}
				for (int j = 0; j < 3; j++) {
					float n = j == 0 ? nx : j == 1 ? ny : nz;
					OUTER[at + j] = CENTRE[at + j] - n * 0.5F;
					INNER[at + j] = CENTRE[at + j] + n * 0.5F;
				}
			}
		}
		for (int k = 0; k < ROWS; k++) {
			float v0 = (1 + k) / 32F;
			float v1 = (2 + k) / 32F;
			for (int s = 0; s < COLUMNS - 1; s++) {
				int l0 = (s * POINTS + k) * 3;
				int l1 = l0 + 3;
				int r0 = ((s + 1) * POINTS + k) * 3;
				int r1 = r0 + 3;
				float xl = COLUMN_X[s];
				float xr = COLUMN_X[s + 1];
				// Outside (Minecraft's north face): u 1 at the left edge to 11 at the right.
				quad(pose, m, b, light, overlay, color, OUTER, r0, OUTER, l0, OUTER, l1, OUTER, r1, (6 + xr) / 64, v0, (6 + xl) / 64, v0,
					(6 + xl) / 64, v1, (6 + xr) / 64, v1);
				// Inside (south): u 22 at the left edge to 12 at the right.
				quad(pose, m, b, light, overlay, color, INNER, l0, INNER, r0, INNER, r1, INNER, l1, (17 - xl) / 64, v0, (17 - xr) / 64, v0,
					(17 - xr) / 64, v1, (17 - xl) / 64, v1);
			}
			int l0 = k * 3;
			int l1 = l0 + 3;
			int r0 = ((COLUMNS - 1) * POINTS + k) * 3;
			int r1 = r0 + 3;
			// Left edge (west) and right edge (east).
			quad(pose, m, b, light, overlay, color, OUTER, l0, INNER, l0, INNER, l1, OUTER, l1, 1 / 64F, v0, 0, v0, 0, v1, 1 / 64F, v1);
			quad(pose, m, b, light, overlay, color, INNER, r0, OUTER, r0, OUTER, r1, INNER, r1, 12 / 64F, v0, 11 / 64F, v0, 11 / 64F, v1,
				12 / 64F, v1);
		}
		for (int s = 0; s < COLUMNS - 1; s++) {
			float xl = COLUMN_X[s];
			float xr = COLUMN_X[s + 1];
			int lt = (s * POINTS) * 3;
			int rt = ((s + 1) * POINTS) * 3;
			int lb = lt + ROWS * 3;
			int rb = rt + ROWS * 3;
			// The top edge (down) and the bottom edge (up).
			quad(pose, m, b, light, overlay, color, INNER, rt, INNER, lt, OUTER, lt, OUTER, rt, (6 + xr) / 64, 0, (6 + xl) / 64, 0, (6 + xl) / 64,
				1 / 32F, (6 + xr) / 64, 1 / 32F);
			quad(pose, m, b, light, overlay, color, OUTER, rb, OUTER, lb, INNER, lb, INNER, rb, (16 + xr) / 64, 1 / 32F, (16 + xl) / 64, 1 / 32F,
				(16 + xl) / 64, 0, (16 + xr) / 64, 0);
		}
	}

	/**
	 * Blocky: the four corners at point {@code at} into {@code CORNERS[slot]}, square to the line from point {@code from} to point
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
	 * Blocky: row {@code row} of the cape between the corners in slots {@code top} and {@code bottom}: outside, inside and both
	 * edges, and the top or bottom edge when asked; textured as Minecraft's cape box (64 by 32).
	 */
	private static void row(PoseStack.Pose pose, Matrix4f m, VertexConsumer b, int light, int overlay, int color, int top, int bottom, int row,
		boolean cap, boolean foot) {
		float[] tp = CORNERS[top];
		float[] bt = CORNERS[bottom];
		float v0 = (1 + row) / 32F;
		float v1 = (2 + row) / 32F;
		// Corners (offsets): 0 left-outside, 3 right-outside, 6 left-inside, 9 right-inside.
		// Outside (Minecraft's north face).
		quad(pose, m, b, light, overlay, color, tp, 3, tp, 0, bt, 0, bt, 3, 11 / 64F, v0, 1 / 64F, v0, 1 / 64F, v1, 11 / 64F, v1);
		// Inside (south).
		quad(pose, m, b, light, overlay, color, tp, 6, tp, 9, bt, 9, bt, 6, 22 / 64F, v0, 12 / 64F, v0, 12 / 64F, v1, 22 / 64F, v1);
		// Left edge (west).
		quad(pose, m, b, light, overlay, color, tp, 0, tp, 6, bt, 6, bt, 0, 1 / 64F, v0, 0, v0, 0, v1, 1 / 64F, v1);
		// Right edge (east).
		quad(pose, m, b, light, overlay, color, tp, 9, tp, 3, bt, 3, bt, 9, 12 / 64F, v0, 11 / 64F, v0, 11 / 64F, v1, 12 / 64F, v1);
		if (cap) {
			// The top edge (down); a step's top in blocky rows reads its row's colour.
			float tv0 = top == 0 ? 0 : v0 + 0.5F / 32;
			float tv1 = top == 0 ? 1 / 32F : v0 + 0.5F / 32;
			quad(pose, m, b, light, overlay, color, tp, 9, tp, 6, tp, 0, tp, 3, 11 / 64F, tv0, 1 / 64F, tv0, 1 / 64F, tv1, 11 / 64F, tv1);
		}
		if (foot) {
			// The bottom edge (up).
			boolean last = row == ROWS - 1;
			float uA = last ? 21 / 64F : 11 / 64F;
			float uB = last ? 11 / 64F : 1 / 64F;
			float fv0 = last ? 1 / 32F : v1 - 0.5F / 32;
			float fv1 = last ? 0 : v1 - 0.5F / 32;
			quad(pose, m, b, light, overlay, color, bt, 3, bt, 0, bt, 6, bt, 9, uA, fv0, uB, fv0, uB, fv1, uA, fv1);
		}
	}

	/** A quad of four points (each an array and the offset of its x), with its texture coordinates. */
	private static void quad(PoseStack.Pose pose, Matrix4f m, VertexConsumer b, int light, int overlay, int color, float[] p0, int o0, float[] p1,
		int o1, float[] p2, int o2, float[] p3, int o3, float u0, float v0, float u1, float v1, float u2, float v2, float u3, float v3) {
		// The normal from the diagonals (robust for a slightly bent quad), in the part's space, then to the pose's.
		float ax = p2[o2] - p0[o0];
		float ay = p2[o2 + 1] - p0[o0 + 1];
		float az = p2[o2 + 2] - p0[o0 + 2];
		float bx = p3[o3] - p1[o1];
		float by = p3[o3 + 1] - p1[o1 + 1];
		float bz = p3[o3 + 2] - p1[o1 + 2];
		NORMAL.set(ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx);
		if (NORMAL.lengthSquared() < 1e-12F) {
			NORMAL.set(0, 0, 1);
		}
		NORMAL.normalize();
		pose.transformNormal(NORMAL, NORMAL);
		vertex(m, b, p0, o0, u0, v0, light, overlay, color);
		vertex(m, b, p1, o1, u1, v1, light, overlay, color);
		vertex(m, b, p2, o2, u2, v2, light, overlay, color);
		vertex(m, b, p3, o3, u3, v3, light, overlay, color);
	}

	private static void vertex(Matrix4f m, VertexConsumer b, float[] p, int o, float u, float v, int light, int overlay, int color) {
		m.transformPosition(p[o] / 16, p[o + 1] / 16, p[o + 2] / 16, POS);
		b.addVertex(POS.x(), POS.y(), POS.z(), color, u, v, overlay, light, NORMAL.x(), NORMAL.y(), NORMAL.z());
	}
}
