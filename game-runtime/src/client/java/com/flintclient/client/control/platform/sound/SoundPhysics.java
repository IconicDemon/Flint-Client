package com.flintclient.client.control.platform.sound;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.SoundMods;
import com.flintclient.client.control.platform.HookGuard;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;

/**
 * Sound Physics: Sound Physics Remastered's sound simulation, inside Flint. As each sound starts (on the sound thread,
 * just before OpenAL plays it), rays traced from it through a copy of the world around you decide:
 * <ul>
 * <li>how much the blocks between it and you muffle it (occlusion: a low-pass on its direct sound),</li>
 * <li>how much it echoes and for how long (four reverb sends, from short to long, weighted by the rays' bounce
 * distances and how reflective the blocks they hit are),</li>
 * <li>where it seems to come from when it's behind a wall (the directions its echoes reach you through open air),</li>
 * <li>and how much the air and water between you dull it.</li>
 * </ul>
 * The numbers and the formulas are the mod's; so are its settings. The game thread keeps the world copy fresh and reads
 * the settings into a {@link SoundPhysicsParams} the sound thread uses. Fault-isolated: a failure turns it off for the
 * session.
 *
 * <p>Adapted from Sound Physics Remastered 1.5.2's {@code SoundPhysics}, {@code RaycastUtils}, {@code LevelAccessUtils},
 * {@code SoundRateManager}, {@code ReverbParams} and {@code RaycastRenderer} (Copyright Sonic Ether, vlad2305m, Max Henkel
 * and Saint, GPL-3.0; see META-INF/licenses/GPL-3.0-SoundPhysicsRemastered.txt).
 */
public final class SoundPhysics {
	private static final int MODULE = Mods.index(SoundMods.SoundPhysics.ID);
	private static final HookGuard GUARD = new HookGuard("sound physics",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final Pattern AMBIENT_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-\\.]+:ambient\\..*$");
	private static final Pattern BLOCK_PATTERN = Pattern.compile(".*block..*");
	private static final Pattern STEP_PATTERN = Pattern.compile(".*step.*");
	private static final Pattern REFERENCE_DISTANCE_BUG_VERSION = Pattern.compile("ALSOFT 1\\.(22|23)\\.");
	private static final float PHI = 1.618034F;

	private SoundPhysics() {
	}

	// ---------- shared state ----------

	/** The settings while the module is on, else null. Written by the game thread. */
	private static volatile @Nullable SoundPhysicsParams params;
	/** The world copy the rays read (null until the first copy, and while direct access is on). */
	private static volatile @Nullable SoundLevel world;
	/** False from a world change until the next tick (the mod skips sounds until the world has ticked once). */
	private static volatile boolean worldInitialized;
	/** Bumped each time Minecraft starts its sound engine (new OpenAL context): the effects are made again. */
	private static volatile int libraryGeneration;
	/** Sound rates: how many of each sound got sound physics this tick. */
	private static final Map<Identifier, Integer> SOUND_COUNTS = new ConcurrentHashMap<>();

	// ---------- the game thread ----------

	private static @Nullable ClientLevel seenLevel;
	private static boolean voiceChecked;

	/** True while the module is on (any thread). */
	public static boolean on() {
		return params != null;
	}

	/** Minecraft started (or restarted) its sound engine: the effects are made again on the next sound. */
	public static void libraryLoaded() {
		libraryGeneration++;
	}

	/** Each client tick: the settings, the world copy, sound rates, long sounds and the debug rays. */
	public static void tick() {
		if (!GUARD.on()) {
			return;
		}
		try {
			if (!voiceChecked) {
				voiceChecked = true;
				SoundMods.voiceChatPresent = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("voicechat");
			}
			if (!Mods.on(MODULE)) {
				params = null;
				world = null;
				seenLevel = null;
				synchronized (RAYS) {
					RAYS.clear();
				}
				return;
			}
			ModuleState state = Mods.state(MODULE);
			SoundPhysicsParams p = params;
			if (p == null || p.revision != state.revision() || p.state != state) {
				p = new SoundPhysicsParams(state);
				params = p;
			}
			Minecraft mc = Minecraft.getInstance();
			ClientLevel level = mc.level;
			LocalPlayer player = mc.player;
			if (level == null || player == null) {
				seenLevel = null;
				world = null;
				worldInitialized = false;
				SOUND_COUNTS.clear();
				return;
			}
			if (level != seenLevel) {
				// A new world: copy it now; sounds wait for its first tick.
				seenLevel = level;
				logDebug(p, "Creating initial level cache");
				world = SoundLevel.copy(level, player.blockPosition(), level.getGameTime(), p.cloneRange);
				worldInitialized = false;
				SOUND_COUNTS.clear();
			} else {
				SOUND_COUNTS.clear();
				worldInitialized = true;
				if (!p.unsafeLevelAccess) {
					refreshWorld(p, level, player.blockPosition());
				}
			}
			if (p.updateMoving) {
				updateMovingSounds(p, mc, level);
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			params = null;
		}
	}

	private static void refreshWorld(SoundPhysicsParams p, ClientLevel level, BlockPos origin) {
		SoundLevel copy = world;
		long now = level.getGameTime();
		if (copy == null || now - copy.tick >= p.cloneTicks || origin.distSqr(copy.origin) >= p.cloneDistance) {
			long start = System.nanoTime();
			world = SoundLevel.copy(level, origin, now, p.cloneRange);
			if (p.performanceLogging) {
				CLONE_PROFILER.add((System.nanoTime() - start) / 1.0E6, p);
			}
		}
	}

	/** Music discs and other long sounds, worked out again every few ticks (the mod's "update moving sounds"). */
	private static void updateMovingSounds(SoundPhysicsParams p, Minecraft mc, ClientLevel level) {
		Map<SoundInstance, ChannelAccess.ChannelHandle> playing = SoundAccess.playing(mc);
		if (playing == null) {
			return;
		}
		long time = level.getGameTime();
		for (Map.Entry<SoundInstance, ChannelAccess.ChannelHandle> entry : playing.entrySet()) {
			SoundInstance sound = entry.getKey();
			if ((time + sound.hashCode()) % p.updateInterval == 0) {
				double x = sound.getX();
				double y = sound.getY();
				double z = sound.getZ();
				SoundSource category = sound.getSource();
				Identifier id = sound.getIdentifier();
				entry.getValue().execute(channel -> processSound(((TaggedChannel) channel).flint$source(), x, y, z, category, id, 1));
			}
		}
	}

	/** Sound Distance Allowance: how much farther than Minecraft's 16 blocks a sound may be sent (singleplayer: the built-in server). */
	public static float rangeAllowance(float range) {
		SoundPhysicsParams p = params;
		return p == null ? range : range * p.distanceAllowance;
	}

	/** The distance a sound fades out over, divided by the attenuation factor (lower factor: sounds carry farther). */
	public static float attenuation(float distance) {
		SoundPhysicsParams p = params;
		return p == null ? distance : distance / p.attenuationFactor;
	}

	/** Entity sounds come from the entity's eyes, except footsteps (on the mod's "is it a step" rule). */
	public static double entitySoundY(double y, float eyeHeight, @Nullable Identifier sound) {
		if (params == null || sound == null) {
			return y;
		}
		return STEP_PATTERN.matcher(sound.getPath()).matches() ? y : y + eyeHeight;
	}

	// ---------- the sound thread ----------

	/** One OpenAL context's effects: Minecraft's, and Simple Voice Chat's own when it plays voices. */
	private static final class Efx {
		boolean ready;
		int maxAuxSends;
		final int[] slots = new int[4];
		final int[] reverbs = new int[4];
		final int[] sendFilters = new int[4];
		int directFilter;
		@Nullable SoundPhysicsParams reverbFor;
	}

	private static int efxGeneration = -1;
	private static final Map<Long, Efx> EFX = new ConcurrentHashMap<>();
	/** The effects of the context the calling thread plays in (set as each sound is worked out). */
	private static final ThreadLocal<Efx> CURRENT = new ThreadLocal<>();
	private static @Nullable Boolean referenceDistanceBug;

	/**
	 * As a sound starts (sound thread): its echo, muffling and direction. Returns whether Sound Physics handled it (so
	 * Sound Filters stays out of its way); false while the module is off.
	 */
	public static boolean play(TaggedChannel channel, double x, double y, double z) {
		SoundPhysicsParams p = params;
		if (p == null || !GUARD.on()) {
			return false;
		}
		try {
			SoundSource category = channel.flint$category();
			processSound(channel.flint$source(), x, y, z, category == null ? SoundSource.MASTER : category, channel.flint$sound(), 1);
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
		return true;
	}

	/** After Minecraft sets a sound's fade-out distance: OpenAL Soft 1.22 and 1.23 need a reference distance for the reverb. */
	public static void afterAttenuation(int source, float maxDistance) {
		if (params != null && hasReferenceDistanceBug()) {
			AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, maxDistance / 2);
		}
	}

	static boolean hasReferenceDistanceBug() {
		Boolean bug = referenceDistanceBug;
		if (bug == null) {
			String version = AL10.alGetString(AL10.AL_VERSION);
			bug = version != null && REFERENCE_DISTANCE_BUG_VERSION.matcher(version).find();
			referenceDistanceBug = bug;
		}
		return bug;
	}

	// ---------- Simple Voice Chat (its audio thread, its own OpenAL context) ----------

	/** While the module is on: whether voices get sound physics ({@code hearSelf} false), or you hear your own (true). */
	static boolean voiceSettings(boolean hearSelf) {
		SoundPhysicsParams p = params;
		return p != null && GUARD.on() && (hearSelf ? p.voiceChat && p.hearSelf : p.voiceChat);
	}

	/** A voice with no place (a group call): no effects, at {@code directGain}. */
	static void voiceDefault(int source, float directGain) {
		SoundPhysicsParams p = params;
		if (p != null && efx(p)) {
			setDefaultEnvironment(p, source, directGain);
		}
	}

	/** A voice at {@code at}: the same simulation as a game sound, under "voicechat:<category>". */
	static void voice(int source, Vec3 at, float directGainScale, @Nullable String category) {
		try {
			Identifier sound = Identifier.fromNamespaceAndPath("voicechat", category == null ? "voicechat" : category);
			processSound(source, at.x, at.y, at.z, SoundSource.MASTER, sound, directGainScale);
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	static boolean isVoiceChat(@Nullable Identifier sound) {
		return sound != null && sound.getNamespace().equals("voicechat");
	}

	/** The mod's {@code processSound}: evaluates the sound's surroundings and sets its filters; returns where to move it, or null. */
	static @Nullable Vec3 processSound(int source, double posX, double posY, double posZ, SoundSource category, @Nullable Identifier sound,
		float directGainScale) {
		SoundPhysicsParams p = params;
		if (p == null || !efx(p)) {
			return null;
		}
		logDebug(p, "Playing sound with source id '{}', position x:{}, y:{}, z:{}, \tcategory: '{}' \tname: '{}'", source, posX, posY, posZ, category,
			sound);
		long start = System.nanoTime();
		Vec3 moved = evaluateEnvironment(p, source, posX, posY, posZ, category, sound, directGainScale);
		if (p.performanceLogging) {
			double ms = (System.nanoTime() - start) / 1.0E6;
			logProfiling(p, "Evaluated environment for sound {} in {} ms", sound, ms);
			SOUND_PROFILER.add(ms, p);
		}
		return moved;
	}

	private static @Nullable Vec3 evaluateEnvironment(SoundPhysicsParams p, int sourceID, double posX, double posY, double posZ,
		SoundSource category, @Nullable Identifier sound, float directGainScale) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null || posX == 0 && posY == 0 && posZ == 0) {
			setDefaultEnvironment(p, sourceID, directGainScale);
			return null;
		}
		Vec3 soundPos = new Vec3(posX, posY, posZ);
		double distance = player.position().distanceTo(soundPos);
		if (distance > p.maxDistance) {
			logDebug(p, "Sound {} is too far away from player ({} blocks)", sound, distance);
			setDefaultEnvironment(p, sourceID, directGainScale);
			return null;
		}
		if (!p.updateMoving && category == SoundSource.RECORDS) {
			setDefaultEnvironment(p, sourceID, directGainScale);
			return null;
		}
		if (!worldInitialized) {
			logDebug(p, "Sound {} skipped because the world is not initialized yet", sound);
			setDefaultEnvironment(p, sourceID, directGainScale);
			return null;
		}
		if (sound != null && rateLimited(p, sound)) {
			logDebug(p, "Sound {} skipped due to sound rate limit", sound);
			setDefaultEnvironment(p, sourceID, directGainScale);
			return null;
		}
		if (!p.evaluateAmbient && sound != null && AMBIENT_PATTERN.matcher(sound.toString()).matches()) {
			logDebug(p, "Sound {} skipped due to ambient sound evaluation option", sound);
			setDefaultEnvironment(p, sourceID, directGainScale);
			return null;
		}
		BlockGetter blocks = p.unsafeLevelAccess ? level : world;
		float absorptionCoeff = (float) (p.blockAbsorption * 3.0);
		Vec3 playerPos = com.flintclient.client.compat.McCompat.mainCamera().position();
		Vec3 normalToPlayer = playerPos.subtract(soundPos).normalize();
		BlockPos soundBlockPos = BlockPos.containing(soundPos);
		boolean sourceIsUnderwater = blocks != null && blocks.getFluidState(soundBlockPos).is(FluidTags.WATER);
		logDebug(p, "Player pos: {}, {}, {} \tSound Pos: {}, {}, {} \tTo player vector: {}, {}, {}", playerPos.x, playerPos.y, playerPos.z, soundPos.x,
			soundPos.y, soundPos.z, normalToPlayer.x, normalToPlayer.y, normalToPlayer.z);
		double occlusionAccumulation = calculateOcclusion(p, blocks, soundPos, playerPos, category, sound);
		float directCutoff = (float) Math.exp(-occlusionAccumulation * absorptionCoeff);
		float directGain = directGainScale * (float) Math.pow(directCutoff, 0.1);
		logOcclusion(p, "Direct cutoff: {}, direct gain: {}", directCutoff, directGain);
		float sendGain0 = 0;
		float sendGain1 = 0;
		float sendGain2 = 0;
		float sendGain3 = 0;
		boolean underwater = player.isUnderWater() || sourceIsUnderwater;
		if (underwater) {
			directCutoff *= 1 - p.underwaterFilter;
		}
		float maxDistance = 256;
		int numRays = p.rayCount;
		int rayBounces = p.rayBounces;
		ReflectedAudio audioDirection = new ReflectedAudio(occlusionAccumulation, sound, p);
		float[] bounceReflectivityRatio = new float[rayBounces];
		float rcpTotalRays = 1F / (numRays * rayBounces);
		float gAngle = PHI * (float) Math.PI * 2;
		Vec3 directSharedAirspaceVector = sharedAirspace(p, blocks, soundPos, playerPos);
		if (directSharedAirspaceVector != null) {
			audioDirection.addDirectAirspace(directSharedAirspaceVector);
		}
		for (int i = 0; i < numRays; i++) {
			float fiN = (float) i / numRays;
			float longitude = gAngle * i * 1;
			float latitude = (float) Math.asin(fiN * 2 - 1);
			Vec3 rayDir = new Vec3(Math.cos(latitude) * Math.cos(longitude), Math.cos(latitude) * Math.sin(longitude), Math.sin(latitude));
			Vec3 rayEnd = new Vec3(soundPos.x + rayDir.x * maxDistance, soundPos.y + rayDir.y * maxDistance, soundPos.z + rayDir.z * maxDistance);
			BlockHitResult rayHit = rayCast(blocks, soundPos, rayEnd, soundBlockPos);
			if (rayHit.getType() != HitResult.Type.BLOCK) {
				continue;
			}
			double rayLength = soundPos.distanceTo(rayHit.getLocation());
			BlockPos lastHitBlock = rayHit.getBlockPos();
			Vec3 lastHitPos = rayHit.getLocation();
			Vec3 lastHitNormal = normal(rayHit.getDirection());
			Vec3 lastRayDir = rayDir;
			float totalRayDistance = (float) rayLength;
			addBounceRay(p, soundPos, rayHit.getLocation(), 0xFF55FF55);
			Vec3 firstSharedAirspaceVector = sharedAirspace(p, blocks, rayHit, playerPos);
			if (firstSharedAirspaceVector != null) {
				audioDirection.addSharedAirspace(firstSharedAirspaceVector, totalRayDistance);
			}
			for (int j = 0; j < rayBounces; j++) {
				Vec3 newRayDir = reflect(lastRayDir, lastHitNormal);
				Vec3 newRayEnd = new Vec3(lastHitPos.x + newRayDir.x * maxDistance, lastHitPos.y + newRayDir.y * maxDistance,
					lastHitPos.z + newRayDir.z * maxDistance);
				BlockHitResult newRayHit = rayCast(blocks, lastHitPos, newRayEnd, lastHitBlock);
				float blockReflectivity = blocks == null ? p.defaultReflectivity : p.reflectivity(blocks.getBlockState(lastHitBlock));
				float energyTowardsPlayer = 0.25F * (blockReflectivity * 0.75F + 0.25F);
				if (newRayHit.getType() == HitResult.Type.MISS) {
					totalRayDistance = (float) (totalRayDistance + lastHitPos.distanceTo(playerPos));
					addBounceRay(p, lastHitPos, newRayEnd, 0xFFFF5555);
				} else {
					Vec3 newRayHitPos = newRayHit.getLocation();
					addBounceRay(p, lastHitPos, newRayHitPos, 0xFF5555FF);
					double newRayLength = lastHitPos.distanceTo(newRayHitPos);
					bounceReflectivityRatio[j] += blockReflectivity;
					totalRayDistance = (float) (totalRayDistance + newRayLength);
					lastHitPos = newRayHitPos;
					lastHitNormal = normal(newRayHit.getDirection());
					lastRayDir = newRayDir;
					lastHitBlock = newRayHit.getBlockPos();
					Vec3 sharedAirspaceVector = sharedAirspace(p, blocks, newRayHit, playerPos);
					if (sharedAirspaceVector != null) {
						audioDirection.addSharedAirspace(sharedAirspaceVector, totalRayDistance);
					}
				}
				if (!(totalRayDistance < p.reverbAttenuationDistance)) {
					float reflectionDelay = (float) Math.max(totalRayDistance, 0.0) * 0.12F * blockReflectivity;
					float cross0 = 1 - Mth.clamp(Math.abs(reflectionDelay - 0), 0, 1);
					float cross1 = 1 - Mth.clamp(Math.abs(reflectionDelay - 1), 0, 1);
					float cross2 = 1 - Mth.clamp(Math.abs(reflectionDelay - 2), 0, 1);
					float cross3 = Mth.clamp(reflectionDelay - 2, 0, 1);
					sendGain0 += cross0 * energyTowardsPlayer * 6.4F * rcpTotalRays;
					sendGain1 += cross1 * energyTowardsPlayer * 12.8F * rcpTotalRays;
					sendGain2 += cross2 * energyTowardsPlayer * 12.8F * rcpTotalRays;
					sendGain3 += cross3 * energyTowardsPlayer * 12.8F * rcpTotalRays;
					if (newRayHit.getType() == HitResult.Type.MISS) {
						break;
					}
				}
			}
		}
		for (int i = 0; i < bounceReflectivityRatio.length; i++) {
			bounceReflectivityRatio[i] /= numRays;
			logEnvironment(p, "Bounce reflectivity {}: {}", i, bounceReflectivityRatio[i]);
		}
		Vec3 newSoundPos = audioDirection.evaluateSoundPosition(soundPos, playerPos);
		if (newSoundPos != null) {
			AL10.alSource3f(sourceID, AL10.AL_POSITION, (float) newSoundPos.x, (float) newSoundPos.y, (float) newSoundPos.z);
			soundPos = newSoundPos;
		}
		float sharedAirspace = audioDirection.sharedAirspaces() * 64 * rcpTotalRays;
		logEnvironment(p, "Shared airspace: {} ({})", sharedAirspace, audioDirection.sharedAirspaces());
		float sharedAirspaceWeight0 = Mth.clamp(sharedAirspace / 20, 0, 1);
		float sharedAirspaceWeight1 = Mth.clamp(sharedAirspace / 15, 0, 1);
		float sharedAirspaceWeight2 = Mth.clamp(sharedAirspace / 10, 0, 1);
		float sharedAirspaceWeight3 = Mth.clamp(sharedAirspace / 10, 0, 1);
		float occlusionCutoff = (float) Math.exp(-occlusionAccumulation * absorptionCoeff * 1.0);
		float sendCutoff0 = occlusionCutoff * (1 - sharedAirspaceWeight0) + sharedAirspaceWeight0;
		float sendCutoff1 = occlusionCutoff * (1 - sharedAirspaceWeight1) + sharedAirspaceWeight1;
		float sendCutoff2 = occlusionCutoff * (1 - sharedAirspaceWeight2) + sharedAirspaceWeight2;
		float sendCutoff3 = occlusionCutoff * (1 - sharedAirspaceWeight3) + sharedAirspaceWeight3;
		float averageSharedAirspace = (sharedAirspaceWeight0 + sharedAirspaceWeight1 + sharedAirspaceWeight2 + sharedAirspaceWeight3) * 0.25F;
		directCutoff = Math.max((float) Math.pow(averageSharedAirspace, 0.5) * 0.2F, directCutoff);
		directGain = directGainScale * (float) Math.pow(directCutoff, 0.1);
		sendGain1 *= bounceReflectivityRatio[1];
		if (bounceReflectivityRatio.length > 2) {
			sendGain2 *= (float) Math.pow(bounceReflectivityRatio[2], 3.0);
		}
		if (bounceReflectivityRatio.length > 3) {
			sendGain3 *= (float) Math.pow(bounceReflectivityRatio[3], 4.0);
		}
		sendGain0 = Mth.clamp(sendGain0, 0, 1);
		sendGain1 = Mth.clamp(sendGain1, 0, 1);
		sendGain2 = Mth.clamp(sendGain2 * 1.05F - 0.05F, 0, 1);
		sendGain3 = Mth.clamp(sendGain3 * 1.05F - 0.05F, 0, 1);
		sendGain0 *= (float) Math.pow(sendCutoff0, 0.1);
		sendGain1 *= (float) Math.pow(sendCutoff1, 0.1);
		sendGain2 *= (float) Math.pow(sendCutoff2, 0.1);
		sendGain3 *= (float) Math.pow(sendCutoff3, 0.1);
		float soundDistance = (float) playerPos.distanceTo(soundPos);
		float maxSoundDistance = AL10.alGetSourcef(sourceID, AL10.AL_MAX_DISTANCE);
		float sendGainMultiplier = 1 - Math.min(soundDistance / (maxSoundDistance * p.reverbDistance), 1);
		sendGain0 = sendGainMultiplier * sendGain0;
		sendGain1 = sendGainMultiplier * sendGain1;
		sendGain2 = sendGainMultiplier * sendGain2;
		sendGain3 = sendGainMultiplier * sendGain3;
		logEnvironment(p, "Final environment settings: {}, {}, {}, {}", sendGain0, sendGain1, sendGain2, sendGain3);
		if (underwater) {
			sendCutoff0 *= 0.4F;
			sendCutoff1 *= 0.4F;
			sendCutoff2 *= 0.4F;
			sendCutoff3 *= 0.4F;
		}
		setEnvironment(p, sourceID, sendGain0, sendGain1, sendGain2, sendGain3, sendCutoff0, sendCutoff1, sendCutoff2, sendCutoff3, directCutoff,
			directGain);
		return newSoundPos;
	}

	private static boolean rateLimited(SoundPhysicsParams p, Identifier sound) {
		int count = SOUND_COUNTS.getOrDefault(sound, 0);
		if (count >= p.maxPerTick(sound)) {
			return true;
		}
		SOUND_COUNTS.put(sound, count + 1);
		return false;
	}

	private static Vec3 normal(Direction direction) {
		return new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ());
	}

	private static Vec3 reflect(Vec3 dir, Vec3 normal) {
		double dot = dir.dot(normal) * 2;
		return new Vec3(dir.x - dot * normal.x, dir.y - dot * normal.y, dir.z - dot * normal.z);
	}

	private static double calculateOcclusion(SoundPhysicsParams p, @Nullable BlockGetter blocks, Vec3 soundPos, Vec3 playerPos,
		SoundSource category, @Nullable Identifier sound) {
		if (p.strictOcclusion) {
			return Math.min(runOcclusion(p, blocks, soundPos, playerPos), p.maxOcclusion);
		}
		boolean isBlock = category == SoundSource.BLOCKS || sound != null && BLOCK_PATTERN.matcher(sound.toString()).matches();
		double variationFactor = p.occlusionVariation;
		if (isBlock) {
			variationFactor = Math.max(variationFactor, 0.49);
		}
		double occlusionAccMin = runOcclusion(p, blocks, soundPos, playerPos);
		if (variationFactor > 0) {
			for (int x = -1; x <= 1; x += 2) {
				for (int y = -1; y <= 1; y += 2) {
					for (int z = -1; z <= 1; z += 2) {
						Vec3 offset = new Vec3(x, y, z).scale(variationFactor);
						occlusionAccMin = Math.min(occlusionAccMin, runOcclusion(p, blocks, soundPos.add(offset), playerPos.add(offset)));
					}
				}
			}
		}
		return Math.min(occlusionAccMin, p.maxOcclusion);
	}

	private static double runOcclusion(SoundPhysicsParams p, @Nullable BlockGetter blocks, Vec3 soundPos, Vec3 playerPos) {
		if (blocks == null) {
			return 0;
		}
		double occlusionAccumulation = 0;
		Vec3 rayOrigin = soundPos;
		BlockPos lastBlockPos = BlockPos.containing(soundPos);
		for (int i = 0; i < p.maxOcclusionRays; i++) {
			BlockHitResult rayHit = rayCast(blocks, rayOrigin, playerPos, lastBlockPos);
			lastBlockPos = rayHit.getBlockPos();
			int color = 0xFF000000 | Mth.hsvToRgb(1 / 3F * (1 - Math.min(1, (float) occlusionAccumulation / 12)), 1, 1);
			if (rayHit.getType() == HitResult.Type.MISS) {
				addOcclusionRay(p, rayOrigin, playerPos.add(0, -0.1, 0), color);
				break;
			}
			addOcclusionRay(p, rayOrigin, rayHit.getLocation(), color);
			BlockPos blockHitPos = rayHit.getBlockPos();
			rayOrigin = rayHit.getLocation();
			BlockState blockHit = blocks.getBlockState(blockHitPos);
			float blockOcclusion = p.occlusion(blockHit);
			Vec3 dirVec = rayOrigin.subtract(blockHitPos.getX() + 0.5, blockHitPos.getY() + 0.5, blockHitPos.getZ() + 0.5);
			Direction sideHit = Direction.getApproximateNearest(dirVec.x, dirVec.y, dirVec.z);
			if (!blockHit.isFaceSturdy(blocks, blockHitPos, sideHit)) {
				blockOcclusion *= p.nonFullBlockOcclusion;
			}
			if (p.occlusionLogging) {
				logOcclusion(p, "{} \t{},{},{}", blockHit.getBlock().getDescriptionId(), rayOrigin.x, rayOrigin.y, rayOrigin.z);
			}
			occlusionAccumulation += blockOcclusion;
			if (occlusionAccumulation > p.maxOcclusion) {
				logOcclusion(p, "Max occlusion reached after {} steps", i + 1);
				break;
			}
		}
		return occlusionAccumulation;
	}

	private static @Nullable Vec3 sharedAirspace(SoundPhysicsParams p, @Nullable BlockGetter blocks, BlockHitResult hit, Vec3 listener) {
		Direction side = hit.getDirection();
		Vec3 at = hit.getLocation();
		Vec3 start = new Vec3(at.x + side.getStepX() * 0.001, at.y + side.getStepY() * 0.001, at.z + side.getStepZ() * 0.001);
		return sharedAirspace(p, blocks, start, listener);
	}

	private static @Nullable Vec3 sharedAirspace(SoundPhysicsParams p, @Nullable BlockGetter blocks, Vec3 sound, Vec3 listener) {
		BlockHitResult finalRayHit = rayCast(blocks, sound, listener, null);
		if (finalRayHit.getType() == HitResult.Type.MISS) {
			addBounceRay(p, sound, listener.add(0, -0.1, 0), 0xFFFFFFFF);
			return sound.subtract(listener);
		}
		return null;
	}

	/** The first block or fluid surface on the line from {@code from} to {@code to}, skipping {@code ignore} (the mod's ray cast). */
	static BlockHitResult rayCast(@Nullable BlockGetter blocks, Vec3 from, Vec3 to, @Nullable BlockPos ignore) {
		if (blocks == null) {
			return BlockHitResult.miss(to, Direction.getApproximateNearest(from.x - to.x, from.y - to.y, from.z - to.z), BlockPos.containing(to));
		}
		return BlockGetter.traverseBlocks(from, to, blocks, (getter, pos) -> {
			if (pos.equals(ignore)) {
				return null;
			}
			BlockState blockState = getter.getBlockState(pos);
			FluidState fluidState = getter.getFluidState(pos);
			VoxelShape shape = ClipContext.Block.COLLIDER.get(blockState, getter, pos, CollisionContext.empty());
			BlockHitResult blockHitResult = getter.clipWithInteractionOverride(from, to, pos, shape, blockState);
			BlockHitResult fluidHitResult = fluidState.isEmpty() ? null : fluidState.getShape(getter, pos).clip(from, to, pos);
			if (fluidHitResult == null) {
				return blockHitResult;
			}
			if (blockHitResult == null) {
				return fluidHitResult;
			}
			return from.distanceToSqr(blockHitResult.getLocation()) <= from.distanceToSqr(fluidHitResult.getLocation()) ? blockHitResult : fluidHitResult;
		}, getter -> BlockHitResult.miss(to, Direction.getApproximateNearest(from.x - to.x, from.y - to.y, from.z - to.z), BlockPos.containing(to)));
	}

	// ---------- OpenAL effects (sound thread) ----------

	/** Makes the reverb slots, effects and filters for this sound engine (once per engine start); false without the effects extension. */
	private static boolean efx(SoundPhysicsParams p) {
		int generation = libraryGeneration;
		if (generation != efxGeneration) {
			// A new sound engine: every context's effects went with the old one.
			efxGeneration = generation;
			EFX.clear();
		}
		Efx fx = EFX.computeIfAbsent(currentContext(), context -> makeEfx());
		CURRENT.set(fx);
		if (fx.ready && fx.reverbFor != p) {
			fx.reverbFor = p;
			syncReverbParams(p);
		}
		return fx.ready;
	}

	/**
	 * The OpenAL context this thread plays in: OpenAL Soft answers with the thread's own context when it has one (Simple
	 * Voice Chat's audio thread), else the process-wide one (Minecraft's).
	 */
	private static long currentContext() {
		return ALC10.alcGetCurrentContext();
	}

	private static Efx makeEfx() {
		Efx fx = new Efx();
		long device = ALC10.alcGetContextsDevice(currentContext());
		if (device == 0 || !ALC.createCapabilities(device).ALC_EXT_EFX) {
			FlintClient.LOGGER.error("[Sound Physics - General] EFX Extension not found on current device. Aborting.");
			return fx;
		}
		fx.maxAuxSends = ALC10.alcGetInteger(device, EXTEfx.ALC_MAX_AUXILIARY_SENDS);
		FlintClient.LOGGER.info("[Sound Physics - General] EFX ready, max auxiliary sends: {}", fx.maxAuxSends);
		for (int i = 0; i < 4; i++) {
			fx.slots[i] = EXTEfx.alGenAuxiliaryEffectSlots();
			EXTEfx.alAuxiliaryEffectSloti(fx.slots[i], EXTEfx.AL_EFFECTSLOT_AUXILIARY_SEND_AUTO, AL10.AL_TRUE);
		}
		for (int i = 0; i < 4; i++) {
			fx.reverbs[i] = EXTEfx.alGenEffects();
			EXTEfx.alEffecti(fx.reverbs[i], EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_EAXREVERB);
		}
		fx.directFilter = EXTEfx.alGenFilters();
		EXTEfx.alFilteri(fx.directFilter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
		for (int i = 0; i < 4; i++) {
			fx.sendFilters[i] = EXTEfx.alGenFilters();
			EXTEfx.alFilteri(fx.sendFilters[i], EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
		}
		logALError("Failed creating the effects");
		fx.ready = true;
		return fx;
	}

	/** The mod's four reverbs (short to long), scaled by reverb gain, brightness and the attenuation factor. */
	private static void syncReverbParams(SoundPhysicsParams p) {
		float global = 0.7F * p.reverbGain;
		float brightness = p.reverbBrightness;
		float attenuation = p.attenuationFactor;
		setReverb(0, 0.15F, 0, 1, 0.2F * global * 0.85F, 0.99F, 0.6F * brightness, 2.5F, 0.001F, 1.26F, 0.011F, 0.994F, 0.16F * attenuation);
		setReverb(1, 0.55F, 0, 1, 0.3F * global * 0.85F, 0.99F, 0.7F * brightness, 0.2F, 0.015F, 1.26F, 0.011F, 0.994F, 0.15F * attenuation);
		setReverb(2, 1.68F, 0.1F, 1, 0.5F * global * 0.85F, 0.99F, 0.7F * brightness, 0, 0.021F, 1.26F, 0.021F, 0.994F, 0.13F * attenuation);
		setReverb(3, 4.142F, 0.5F, 1, 0.4F * global * 0.85F, 0.89F, 0.7F * brightness, 0, 0.025F, 1.26F, 0.021F, 0.994F, 0.11F * attenuation);
		FlintClient.LOGGER.info("[Sound Physics - General] Reverb parameters set (gain {}, brightness {}, attenuation factor {})", p.reverbGain,
			brightness, attenuation);
	}

	@SuppressWarnings("unused")
	private static void setReverb(int i, float decayTime, float density, float diffusion, float gain, float gainHF, float decayHFRatio,
		float reflectionsGain, float reflectionsDelay, float lateReverbGain, float lateReverbDelay, float airAbsorptionGainHF,
		float roomRolloffFactor) {
		// As the mod sets them: the reflections delay is computed but never handed to OpenAL.
		Efx fx = CURRENT.get();
		int effect = fx.reverbs[i];
		EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DENSITY, density);
		EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DIFFUSION, diffusion);
		EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_GAIN, gain);
		EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_GAINHF, gainHF);
		EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DECAY_TIME, decayTime);
		EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DECAY_HFRATIO, decayHFRatio);
		EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_REFLECTIONS_GAIN, reflectionsGain);
		EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_LATE_REVERB_GAIN, lateReverbGain);
		EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_LATE_REVERB_DELAY, lateReverbDelay);
		EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_AIR_ABSORPTION_GAINHF, airAbsorptionGainHF);
		EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_ROOM_ROLLOFF_FACTOR, roomRolloffFactor);
		EXTEfx.alAuxiliaryEffectSloti(fx.slots[i], EXTEfx.AL_EFFECTSLOT_EFFECT, effect);
		logALError("Error while assigning reverb " + i);
	}

	static void setDefaultEnvironment(SoundPhysicsParams p, int sourceID, float directGain) {
		setEnvironment(p, sourceID, 0, 0, 0, 0, 1, 1, 1, 1, 1, directGain);
	}

	/** Hands a sound its four reverb sends (gain and high-frequency cutoff each), its direct filter and its air absorption. */
	static void setEnvironment(SoundPhysicsParams p, int sourceID, float sendGain0, float sendGain1, float sendGain2, float sendGain3,
		float sendCutoff0, float sendCutoff1, float sendCutoff2, float sendCutoff3, float directCutoff, float directGain) {
		Efx fx = CURRENT.get();
		if (fx == null || !fx.ready) {
			return;
		}
		// The longest reverb goes on send 0, so a device with fewer sends keeps the long tail (the mod's order).
		float[] gains = {sendGain0, sendGain1, sendGain2, sendGain3};
		float[] cutoffs = {sendCutoff0, sendCutoff1, sendCutoff2, sendCutoff3};
		for (int i = 0; i < 4; i++) {
			int send = 3 - i;
			if (fx.maxAuxSends > send) {
				EXTEfx.alFilterf(fx.sendFilters[i], EXTEfx.AL_LOWPASS_GAIN, gains[i]);
				EXTEfx.alFilterf(fx.sendFilters[i], EXTEfx.AL_LOWPASS_GAINHF, cutoffs[i]);
				AL11.alSource3i(sourceID, EXTEfx.AL_AUXILIARY_SEND_FILTER, fx.slots[i], send, fx.sendFilters[i]);
			}
		}
		EXTEfx.alFilterf(fx.directFilter, EXTEfx.AL_LOWPASS_GAIN, directGain);
		EXTEfx.alFilterf(fx.directFilter, EXTEfx.AL_LOWPASS_GAINHF, directCutoff);
		AL10.alSourcei(sourceID, EXTEfx.AL_DIRECT_FILTER, fx.directFilter);
		AL10.alSourcef(sourceID, EXTEfx.AL_AIR_ABSORPTION_FACTOR, p.airAbsorption);
		logALError("Set environment");
		// Flint's addition to the environment log: exactly what OpenAL got (the mod logs the direct cutoff before the
		// underwater filter and never the send cutoffs).
		logEnvironment(p, "Applied to source {}: direct gain {}, direct cutoff {}, send gains {}/{}/{}/{}, send cutoffs {}/{}/{}/{}, air absorption {}",
			sourceID, directGain, directCutoff, sendGain0, sendGain1, sendGain2, sendGain3, sendCutoff0, sendCutoff1, sendCutoff2, sendCutoff3,
			p.airAbsorption);
	}

	private static void logALError(String what) {
		int error = AL10.alGetError();
		if (error != AL10.AL_NO_ERROR) {
			String name = switch (error) {
				case AL10.AL_INVALID_NAME -> "AL_INVALID_NAME";
				case AL10.AL_INVALID_ENUM -> "AL_INVALID_ENUM";
				case AL10.AL_INVALID_VALUE -> "AL_INVALID_VALUE";
				case AL10.AL_INVALID_OPERATION -> "AL_INVALID_OPERATION";
				case AL10.AL_OUT_OF_MEMORY -> "AL_OUT_OF_MEMORY";
				default -> Integer.toString(error);
			};
			FlintClient.LOGGER.error("[Sound Physics - General] {}: OpenAL error {}", what, name);
		}
	}

	// ---------- logs and the debug rays ----------

	private static void logDebug(SoundPhysicsParams p, String message, Object... args) {
		if (p.debugLogging) {
			FlintClient.LOGGER.info("[Sound Physics - Debug] " + message, args);
		}
	}

	private static void logOcclusion(SoundPhysicsParams p, String message, Object... args) {
		if (p.occlusionLogging) {
			FlintClient.LOGGER.info("[Sound Physics - Occlusion] " + message, args);
		}
	}

	private static void logEnvironment(SoundPhysicsParams p, String message, Object... args) {
		if (p.environmentLogging) {
			FlintClient.LOGGER.info("[Sound Physics - Environment] " + message, args);
		}
	}

	private static void logProfiling(SoundPhysicsParams p, String message, Object... args) {
		if (p.performanceLogging) {
			FlintClient.LOGGER.info("[Sound Physics - Profiling] " + message, args);
		}
	}

	private static final Profiler SOUND_PROFILER = new Profiler("Sound Physics");
	private static final Profiler CLONE_PROFILER = new Profiler("Level Caching");

	/** The mod's task profiler: the last 100 durations, summed up every 100 tasks. */
	private static final class Profiler {
		private final String name;
		private final ArrayDeque<Double> durations = new ArrayDeque<>();
		private int tally;

		Profiler(String name) {
			this.name = name;
		}

		synchronized void add(double ms, SoundPhysicsParams p) {
			if (durations.size() == 100) {
				durations.poll();
			}
			durations.offer(ms);
			if (++tally >= 100) {
				tally = 0;
				double total = 0;
				double min = Double.MAX_VALUE;
				double max = 0;
				for (double d : durations) {
					total += d;
					min = Math.min(min, d);
					max = Math.max(max, d);
				}
				logProfiling(p, "Profile for task '{}', total: {} ms, average: {} ms, min: {} ms, max: {} ms", name, total, total / durations.size(), min,
					max);
			}
		}
	}

	private record Ray(Vec3 start, Vec3 end, int color, long tickCreated, boolean throughWalls) {
	}

	private static final List<Ray> RAYS = new ArrayList<>();

	private static void addBounceRay(SoundPhysicsParams p, Vec3 start, Vec3 end, int color) {
		if (p.renderBounces) {
			addRay(start, end, color, false);
		}
	}

	private static void addOcclusionRay(SoundPhysicsParams p, Vec3 start, Vec3 end, int color) {
		if (p.renderOcclusion) {
			addRay(start, end, color, true);
		}
	}

	private static void addRay(Vec3 start, Vec3 end, int color, boolean throughWalls) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null || player.position().distanceTo(start) > 32 && player.position().distanceTo(end) > 32) {
			return;
		}
		synchronized (RAYS) {
			RAYS.add(new Ray(start, end, color, level.getGameTime(), throughWalls));
		}
	}

	/** The rays of the last two seconds, fading out (game thread, each tick, in the world gizmo collection). */
	public static void tickGizmos() {
		SoundPhysicsParams p = params;
		ClientLevel level = Minecraft.getInstance().level;
		if (p == null || level == null || !GUARD.on()) {
			return;
		}
		synchronized (RAYS) {
			if (!p.renderBounces && !p.renderOcclusion) {
				RAYS.clear();
				return;
			}
			long now = level.getGameTime();
			Iterator<Ray> it = RAYS.iterator();
			while (it.hasNext()) {
				Ray ray = it.next();
				long age = now - ray.tickCreated;
				if (age > 40 || age < 0) {
					it.remove();
					continue;
				}
				int alpha = (int) (255 * (1 - age / 40F));
				int argb = alpha << 24 | ray.color & 0xFFFFFF;
				var line = net.minecraft.gizmos.Gizmos.line(ray.start, ray.end, argb, 1);
				if (ray.throughWalls) {
					line.setAlwaysOnTop();
				}
			}
		}
	}
}
