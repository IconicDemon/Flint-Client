package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.ToolMods.SoundFilters;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;

/**
 * Minecraft 26.3's side of Sound Filters, through OpenAL's effects extension.
 *
 * <p><b>Reverb.</b> Each second, on the game thread, the air around your head is measured: the open blocks you could walk
 * through (up to the room size limit), how many of them see the sky, and the walls around them, hard (stone-like, they
 * echo) or soft (wool-like, they absorb). A small, closed, hard room gives a short bright echo; a large cave a long one;
 * open sky little. That becomes one reverb effect that every sound sends to.
 *
 * <p><b>Muffling.</b> As each sound starts, on the sound thread, the blocks on the line between you and it make its
 * direct sound duller (a low-pass filter), and being under water or lava dulls everything.
 *
 * <p>Sounds relative to you (menus) are left alone, as is everything while the mod is off. If the sound device has no
 * effects extension, the mod does nothing. Fault-isolated.
 */
public final class Mc263SoundFilters {
	private static final HookGuard GUARD = new HookGuard("sound filters",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final int FILTERS = Mods.index(SoundFilters.ID);

	private Mc263SoundFilters() {
	}

	// ---------- the room (game thread) ----------

	/** How much the room echoes (0..1) and how big it is (0..1), and what you're under; read by the sound thread. */
	private static volatile float echo;
	private static volatile float roomSize;
	/** 0 air, 1 water, 2 lava. */
	private static volatile int liquid;
	private static volatile double listenerX;
	private static volatile double listenerY;
	private static volatile double listenerZ;
	/** Bumped when the room changes, so the sound thread updates its reverb. */
	private static volatile int revision;
	private static int measuredAt = -100;

	/** Each client tick: where you hear from, what you're under, and (each second) the room around you. */
	public static void tick() {
		if (!GUARD.on() || !Mods.on(FILTERS)) {
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			LocalPlayer player = mc.player;
			ClientLevel level = mc.level;
			if (player == null || level == null) {
				return;
			}
			Vec3 ear = com.flintclient.client.compat.McCompat.mainCamera().position();
			listenerX = ear.x;
			listenerY = ear.y;
			listenerZ = ear.z;
			liquid = player.isEyeInFluid(FluidTags.LAVA) ? 2 : player.isEyeInFluid(FluidTags.WATER) ? 1 : 0;
			if (player.tickCount - measuredAt >= 20 || player.tickCount < measuredAt) {
				measuredAt = player.tickCount;
				measure(level, BlockPos.containing(ear), Mods.state(FILTERS));
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** Walks the open blocks from {@code start} (breadth first) and sums up the room they make. */
	private static void measure(ClientLevel level, BlockPos start, ModuleState s) {
		int limit = s.integer(SoundFilters.MAX_BLOCKS);
		float denseHardness = s.decimal(SoundFilters.DENSE_HARDNESS);
		float denseBlast = s.decimal(SoundFilters.DENSE_BLAST);
		float softHardness = s.decimal(SoundFilters.SOFT_HARDNESS);
		float softBlast = s.decimal(SoundFilters.SOFT_BLAST);
		boolean checkSky = s.bool(SoundFilters.CHECK_SKY);
		LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
		LongOpenHashSet seen = new LongOpenHashSet();
		queue.enqueue(start.asLong());
		seen.add(start.asLong());
		int open = 0;
		int sky = 0;
		float walls = 0;
		float reflect = 0;
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
		while (!queue.isEmpty() && open < limit) {
			at.set(queue.dequeueLong());
			open++;
			if (checkSky && level.canSeeSky(at)) {
				sky++;
			}
			for (Direction direction : Direction.values()) {
				next.setWithOffset(at, direction);
				long key = next.asLong();
				if (seen.contains(key)) {
					continue;
				}
				BlockState state = level.getBlockState(next);
				if (!state.isCollisionShapeFullBlock(level, next)) {
					seen.add(key);
					queue.enqueue(key);
					continue;
				}
				// A wall: hard ones echo, soft ones swallow, the rest a little of both.
				float hardness = state.getDestroySpeed(level, next);
				float blast = state.getBlock().getExplosionResistance();
				walls++;
				reflect += hardness >= denseHardness || blast >= denseBlast ? 1.0F : hardness <= softHardness && blast <= softBlast ? 0.15F : 0.6F;
			}
		}
		float enclosed = open == 0 ? 0 : 1 - (checkSky ? sky / (float) open : 0);
		if (open >= limit) {
			// Too big to find its walls: a huge cave or the outdoors.
			enclosed *= 0.6F;
		}
		float hard = walls == 0 ? 0 : reflect / walls;
		float raw = enclosed * (0.35F + 0.65F * hard);
		float amount = Math.clamp(s.decimal(SoundFilters.BASE_REVERB) + s.decimal(SoundFilters.REVERB_PERCENT) * raw, 0, 1);
		float size = Math.clamp(open / (float) limit, 0, 1);
		if (Math.abs(amount - echo) > 0.01F || Math.abs(size - roomSize) > 0.02F) {
			echo = amount;
			roomSize = size;
			revision++;
		}
	}

	// ---------- the sounds (sound thread) ----------

	/** 0 not tried, 1 ready, -1 no effects extension. */
	private static int efx;
	private static int slot;
	private static int effect;
	private static int filter;
	private static int applied = -1;
	private static int appliedSettings = -1;

	/** As a sound starts playing (sound thread): its echo and muffling, or none (mod off, a menu sound). */
	public static void play(int source, double x, double y, double z, boolean relative) {
		if (!GUARD.on()) {
			return;
		}
		try {
			boolean on = Mods.on(FILTERS);
			if (efx == 0) {
				if (!on) {
					return;
				}
				init();
			}
			if (efx != 1) {
				return;
			}
			if (!on || relative) {
				AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, EXTEfx.AL_EFFECTSLOT_NULL, 0, EXTEfx.AL_FILTER_NULL);
				AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
				return;
			}
			ModuleState s = Mods.state(FILTERS);
			reverb(s);
			AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, s.bool(SoundFilters.REVERB) ? slot : EXTEfx.AL_EFFECTSLOT_NULL, 0,
				EXTEfx.AL_FILTER_NULL);
			float gain = 1;
			float gainHf = 1;
			if (s.bool(SoundFilters.OBSTRUCTION)) {
				float blocked = Math.min(s.decimal(SoundFilters.OBSTRUCTION_MAX), blocksBetween(x, y, z) * s.decimal(SoundFilters.OBSTRUCTION_STEP));
				gain *= 1 - blocked * 0.35F;
				gainHf *= 1 - blocked;
			}
			int under = liquid;
			if (s.bool(SoundFilters.LIQUID) && under != 0) {
				gain *= s.decimal(under == 1 ? SoundFilters.WATER_GAIN : SoundFilters.LAVA_GAIN);
				gainHf *= s.decimal(under == 1 ? SoundFilters.WATER_GAIN_HF : SoundFilters.LAVA_GAIN_HF);
			}
			if (gain >= 0.999F && gainHf >= 0.999F) {
				AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
			} else {
				// A source keeps a copy of the filter as it was when attached, so one filter serves every sound.
				EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAIN, Math.clamp(gain, 0, 1));
				EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAINHF, Math.clamp(gainHf, 0, 1));
				AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, filter);
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	private static void init() {
		efx = -1;
		long device = ALC10.alcGetContextsDevice(ALC10.alcGetCurrentContext());
		if (device == 0 || !ALC.createCapabilities(device).ALC_EXT_EFX) {
			FlintClient.LOGGER.info("Flint: this sound device has no effects extension; Sound Filters does nothing");
			return;
		}
		slot = EXTEfx.alGenAuxiliaryEffectSlots();
		effect = EXTEfx.alGenEffects();
		EXTEfx.alEffecti(effect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_EAXREVERB);
		filter = EXTEfx.alGenFilters();
		EXTEfx.alFilteri(filter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
		if (AL10.alGetError() != AL10.AL_NO_ERROR) {
			FlintClient.LOGGER.info("Flint: the sound device refused the reverb; Sound Filters does nothing");
			return;
		}
		efx = 1;
		FlintClient.LOGGER.info("Flint: Sound Filters is ready (reverb and filters on this sound device)");
	}

	/** Brings the reverb in line with the room and the settings (only when either changed). */
	private static void reverb(ModuleState s) {
		int room = revision;
		if (room == applied && s.revision() == appliedSettings) {
			return;
		}
		applied = room;
		appliedSettings = s.revision();
		float a = echo;
		float size = roomSize;
		float decay = Math.clamp(Math.max(s.decimal(SoundFilters.MIN_DECAY), 0.1F + 5.5F * a * (0.35F + 0.65F * size)), 0.1F, 20);
		EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DENSITY, s.decimal(SoundFilters.DENSITY));
		EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DIFFUSION, s.decimal(SoundFilters.DIFFUSION));
		EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_GAIN, Math.clamp(s.decimal(SoundFilters.GAIN), 0, 1));
		EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_GAINHF, Math.clamp(s.decimal(SoundFilters.GAIN_HF), 0, 1));
		EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DECAY_TIME, decay);
		EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DECAY_HFRATIO, Math.clamp(s.decimal(SoundFilters.DECAY_HF), 0.1F, 2));
		EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_REFLECTIONS_GAIN,
			Math.clamp(s.decimal(SoundFilters.REFLECTIONS_BASE) + s.decimal(SoundFilters.REFLECTIONS_MULTIPLIER) * a, 0, 3.16F));
		EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_REFLECTIONS_DELAY,
			Math.clamp(s.decimal(SoundFilters.REFLECTIONS_DELAY) * a * (1 + 4 * size), 0, 0.3F));
		EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_LATE_REVERB_GAIN,
			Math.clamp(s.decimal(SoundFilters.LATE_BASE) + s.decimal(SoundFilters.LATE_MULTIPLIER) * a, 0, 10));
		EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_LATE_REVERB_DELAY, Math.clamp(s.decimal(SoundFilters.LATE_DELAY) * a * (1 + 4 * size), 0, 0.1F));
		EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_AIR_ABSORPTION_GAINHF, Math.clamp(s.decimal(SoundFilters.AIR_ABSORPTION), 0.892F, 1));
		// Re-attaching loads the changed effect into the slot; the slot's own gain is how much of it you hear.
		EXTEfx.alAuxiliaryEffectSloti(slot, EXTEfx.AL_EFFECTSLOT_EFFECT, effect);
		EXTEfx.alAuxiliaryEffectSlotf(slot, EXTEfx.AL_EFFECTSLOT_GAIN, a);
	}

	/** How many solid blocks lie on the straight line from your ears to (x, y, z), counted every half block (at most 40). */
	private static int blocksBetween(double x, double y, double z) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return 0;
		}
		double fx = listenerX;
		double fy = listenerY;
		double fz = listenerZ;
		double dx = x - fx;
		double dy = y - fy;
		double dz = z - fz;
		double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
		int steps = (int) Math.min(80, Math.ceil(length * 2));
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		long last = Long.MIN_VALUE;
		int count = 0;
		for (int i = 1; i < steps && count < 40; i++) {
			double t = i / (double) steps;
			at.set(fx + dx * t, fy + dy * t, fz + dz * t);
			long key = at.asLong();
			if (key == last) {
				continue;
			}
			last = key;
			if (level.getBlockState(at).isSolidRender()) {
				count++;
			}
		}
		return count;
	}
}
