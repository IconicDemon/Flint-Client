package com.flintclient.client.cosmetics;

import com.flintclient.client.cosmetics.CosmeticAnimation.Clip;
import com.flintclient.client.cosmetics.CosmeticAnimation.Track;
import java.util.Locale;

/**
 * An emote's pose of the player's own parts: a Bedrock animation whose bones are {@code head}, {@code body},
 * {@code right_arm}, {@code left_arm}, {@code right_leg}, {@code left_leg} (Blockbench's {@code rightArm} spellings too), sampled
 * at a time ({@link #sample}) and laid over the pose Minecraft gave the model ({@link #apply}). A part the emote animates
 * takes the emote's rotation (and its position offset, on top of where Minecraft put it); the others keep Minecraft's. As in
 * Bedrock's player geometry, the head and arms hang from the body: turning the body turns them with it (the legs don't).
 * Model space as {@link CosmeticModel}'s: rotations keep Bedrock's numbers, positions are {@code (x, -y, z)} pixels.
 * Allocation-free after construction (render thread). Pure Java.
 */
public final class EmotePose {
	public static final int HEAD = 0;
	public static final int BODY = 1;
	public static final int RIGHT_ARM = 2;
	public static final int LEFT_ARM = 3;
	public static final int RIGHT_LEG = 4;
	public static final int LEFT_LEG = 5;
	public static final int PARTS = 6;
	/** Floats per part in {@link #apply}'s array: x, y, z (pixels), x, y, z rotation (radians). */
	public static final int STRIDE = 6;
	/** An emote blends in, and out, over this long (seconds). */
	public static final double BLEND_SECONDS = 0.15;

	/** The blend weight {@code seconds} into {@code clip}: up over the first {@link #BLEND_SECONDS}, down over the last (one-shot). */
	public static float weight(Clip clip, double seconds) {
		double w = Math.min(1, seconds / BLEND_SECONDS);
		if (clip.loop() == CosmeticAnimation.Loop.ONCE && clip.length() > 0) {
			w = Math.min(w, (clip.length() - seconds) / BLEND_SECONDS);
		}
		return (float) Math.max(0, Math.min(1, w));
	}
	private static final int[] FOLLOW_BODY = {HEAD, RIGHT_ARM, LEFT_ARM};

	/** Each part's rotation (radians, x y z) while it {@link #moves}. */
	public final float[] rotation = new float[PARTS * 3];
	/** Each part's position offset (model pixels). */
	public final float[] offset = new float[PARTS * 3];
	private final float[] sampled = new float[3];
	private final float[] body = new float[12];
	private final float[] child = new float[12];
	private final float[] out = new float[12];
	private int moved;
	private Clip bound;
	private int[] binding = new int[0];

	/** A track's part by its bone name, or -1. */
	static int part(String bone) {
		return switch (bone.toLowerCase(Locale.ROOT).replace("_", "")) {
			case "head" -> HEAD;
			case "body" -> BODY;
			case "rightarm" -> RIGHT_ARM;
			case "leftarm" -> LEFT_ARM;
			case "rightleg" -> RIGHT_LEG;
			case "leftleg" -> LEFT_LEG;
			default -> -1;
		};
	}

	/** Whether {@code clip} animates any of the player's parts (an emote must). */
	public static boolean playable(Clip clip) {
		for (Track t : clip.tracks()) {
			if (part(t.bone()) >= 0 && (t.rotation() != null || t.position() != null)) {
				return true;
			}
		}
		return false;
	}

	/** The pose {@code seconds} into {@code clip} ({@code life}: seconds since it started, Molang's life time). */
	public void sample(Clip clip, double seconds, double life) {
		if (clip != bound) {
			bound = clip;
			binding = new int[clip.tracks().size()];
			for (int i = 0; i < binding.length; i++) {
				binding[i] = part(clip.tracks().get(i).bone());
			}
		}
		moved = 0;
		java.util.Arrays.fill(rotation, 0);
		java.util.Arrays.fill(offset, 0);
		double t = clip.time(seconds);
		for (int i = 0; i < binding.length; i++) {
			int p = binding[i];
			if (p < 0) {
				continue;
			}
			Track track = clip.tracks().get(i);
			if (track.rotation() != null) {
				track.rotation().sample(t, life, sampled, 0);
				rotation[p * 3] = (float) Math.toRadians(sampled[0]);
				rotation[p * 3 + 1] = (float) Math.toRadians(sampled[1]);
				rotation[p * 3 + 2] = (float) Math.toRadians(sampled[2]);
				moved |= 1 << p;
			}
			if (track.position() != null) {
				track.position().sample(t, life, sampled, 0);
				offset[p * 3] = sampled[0];
				offset[p * 3 + 1] = -sampled[1];
				offset[p * 3 + 2] = sampled[2];
				moved |= 1 << p;
			}
		}
	}

	/** Whether the last {@link #sample} animates {@code part}. */
	public boolean moves(int part) {
		return (moved & 1 << part) != 0;
	}

	/** Whether it animates any part. */
	public boolean any() {
		return moved != 0;
	}

	/**
	 * Lays the emote over {@code parts} ({@link #STRIDE} floats a part in {@link #HEAD}… order: the pose Minecraft gave the model),
	 * writing the result back. {@code bodyPivot}: where the body part turns (Minecraft's body pivot, pixels).
	 */
	public void apply(float[] parts) {
		apply(parts, 1);
	}

	/**
	 * {@link #apply(float[])}, {@code weight} of the way from Minecraft's pose to the emote's (0..1): an emote blends in over
	 * its first {@link #BLEND_SECONDS} and out over its last, as the website's preview does.
	 */
	public void apply(float[] parts, float weight) {
		float w = Math.max(0, Math.min(1, weight));
		if (moved == 0 || w == 0) {
			return;
		}
		for (int p = 0; p < PARTS; p++) {
			if (moves(p)) {
				int o = p * STRIDE;
				parts[o] += offset[p * 3] * w;
				parts[o + 1] += offset[p * 3 + 1] * w;
				parts[o + 2] += offset[p * 3 + 2] * w;
				parts[o + 3] += (rotation[p * 3] - parts[o + 3]) * w;
				parts[o + 4] += (rotation[p * 3 + 1] - parts[o + 4]) * w;
				parts[o + 5] += (rotation[p * 3 + 2] - parts[o + 5]) * w;
			}
		}
		if (!moves(BODY)) {
			return;
		}
		// The body's transform about its pivot; the head and arms go with it.
		int b = BODY * STRIDE;
		Mat.rotationZYX(parts[b + 5], parts[b + 4], parts[b + 3], body, 0);
		float bx = parts[b];
		float by = parts[b + 1];
		float bz = parts[b + 2];
		body[9] = bx;
		body[10] = by;
		body[11] = bz;
		for (int p : FOLLOW_BODY) {
			int o = p * STRIDE;
			Mat.rotationZYX(parts[o + 5], parts[o + 4], parts[o + 3], child, 0);
			child[9] = parts[o] - bx;
			child[10] = parts[o + 1] - by;
			child[11] = parts[o + 2] - bz;
			Mat.mul(body, 0, child, 0, out, 0);
			parts[o] = out[9];
			parts[o + 1] = out[10];
			parts[o + 2] = out[11];
			// Back to Minecraft's angles (Rz·Ry·Rx).
			float sy = Math.max(-1, Math.min(1, -out[2]));
			parts[o + 4] = (float) Math.asin(sy);
			parts[o + 3] = (float) Math.atan2(out[5], out[8]);
			parts[o + 5] = (float) Math.atan2(out[1], out[0]);
		}
	}
}
