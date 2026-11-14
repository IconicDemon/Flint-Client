package com.flintclient.client.cosmetics;

import com.flintclient.client.cosmetics.CosmeticAnimation.Clip;
import com.flintclient.client.cosmetics.CosmeticAnimation.Track;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * One worn model's pose, worked out each frame it is drawn: the clip that applies ({@link #IDLE}, or {@link #WALK},
 * {@link #FLY}, {@link #SWIM} when the model has one and the wearer does that; a change blends over {@link #BLEND_SECONDS}),
 * sampled onto the bones, and every bone's transform into its anchor part's space ({@link #bone}, 12 floats a bone, see
 * {@link Mat}). Bedrock's channels in model space: rotations keep their numbers (radians), positions are
 * {@code (x, -y, z)} pixels, scales as they are; mirrored, y and z rotations and x positions turn around. Allocation-free
 * after construction; one per drawn instance (the render thread only).
 */
public final class ModelPose {
	public static final int IDLE = 0;
	public static final int WALK = 1;
	public static final int FLY = 2;
	public static final int SWIM = 3;
	static final String[] CLIP_NAMES = {"idle", "walk", "fly", "swim"};
	static final double BLEND_SECONDS = 0.25;

	public final CosmeticModel model;
	/** Each bone's transform into its anchor's space (blocks), 12 floats a bone. */
	public final float[] bone;
	private final @Nullable Clip[] clips = new Clip[4];
	private final int[][] binding = new int[4][];
	private final float[] rot;
	private final float[] pos;
	private final float[] scl;
	private final float[] rotB;
	private final float[] posB;
	private final float[] scl2;
	private final float[] local = new float[12];
	/** Bones no clip moves: their transform about their parent is worked out once ({@link #rest}). */
	private final boolean[] still;
	private float @org.jspecify.annotations.Nullable [] rest;
	private final float[] scratch = new float[3];
	private int playing = -1;
	private int previous = -1;
	private double switchedAt;
	private double previousStart;
	private double start;

	public ModelPose(CosmeticModel model, @Nullable CosmeticAnimation animation) {
		this.model = model;
		int n = model.bones;
		bone = new float[n * 12];
		rot = new float[n * 3];
		pos = new float[n * 3];
		scl = new float[n * 3];
		rotB = new float[n * 3];
		posB = new float[n * 3];
		scl2 = new float[n * 3];
		if (animation != null) {
			for (int i = 0; i < 4; i++) {
				Clip c = animation.clip(CLIP_NAMES[i]);
				if (c != null) {
					clips[i] = c;
					binding[i] = bind(model, c);
				}
			}
		}
		still = new boolean[n];
		java.util.Arrays.fill(still, true);
		for (int[] b : binding) {
			if (b != null) {
				for (int bone : b) {
					if (bone >= 0) {
						still[bone] = false;
					}
				}
			}
		}
		rest = null;
		pose(IDLE, 0, 0);
		rest = new float[n * 12];
		for (int b = 0; b < n; b++) {
			local(b);
			System.arraycopy(local, 0, rest, b * 12, 12);
		}
		playing = -1;
		pose(IDLE, 0, 0);
	}

	/** Each track's bone in {@code model} (-1: none of that name). */
	static int[] bind(CosmeticModel model, Clip clip) {
		int[] out = new int[clip.tracks().size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = model.bone(clip.tracks().get(i).bone());
		}
		return out;
	}

	/** Whether any clip moves the model (a still model's pose is worked out once). */
	public boolean animated() {
		for (Clip c : clips) {
			if (c != null && !c.tracks().isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Poses the model for {@code state} (what the wearer does) at {@code seconds} (a clock of the instance's own, in seconds),
	 * {@code life}: seconds since the model was first shown (Molang's {@code query.life_time}).
	 */
	public void pose(int state, double seconds, double life) {
		int want = state >= 0 && state < 4 && clips[state] != null ? state : clips[IDLE] != null ? IDLE : -1;
		if (want != playing) {
			previous = playing;
			previousStart = start;
			playing = want;
			start = seconds;
			switchedAt = seconds;
		}
		reset(rot, pos, scl);
		if (playing >= 0) {
			sample(playing, seconds - start, life, rot, pos, scl);
		}
		double blend = (seconds - switchedAt) / BLEND_SECONDS;
		if (previous >= 0 && blend < 1 && blend >= 0) {
			reset(rotB, posB, scl2);
			sample(previous, seconds - previousStart, life, rotB, posB, scl2);
			float w = (float) blend;
			for (int i = 0; i < rot.length; i++) {
				rot[i] = rotB[i] + (rot[i] - rotB[i]) * w;
				pos[i] = posB[i] + (pos[i] - posB[i]) * w;
				scl[i] = scl2[i] + (scl[i] - scl2[i]) * w;
			}
		} else if (previous >= 0 && blend >= 1) {
			previous = -1;
		}
		compute();
	}

	private static void reset(float[] r, float[] p, float[] s) {
		Arrays.fill(r, 0);
		Arrays.fill(p, 0);
		Arrays.fill(s, 1);
	}

	private void sample(int which, double seconds, double life, float[] r, float[] p, float[] s) {
		Clip clip = clips[which];
		int[] bones = binding[which];
		double t = clip.time(seconds);
		for (int i = 0; i < bones.length; i++) {
			int b = bones[i];
			if (b < 0) {
				continue;
			}
			Track track = clip.tracks().get(i);
			if (track.rotation() != null) {
				track.rotation().sample(t, life, r, b * 3);
			}
			if (track.position() != null) {
				track.position().sample(t, life, p, b * 3);
			}
			if (track.scale() != null) {
				track.scale().sample(t, life, s, b * 3);
			}
		}
	}

	/** Every bone's transform from its rest data and the sampled offsets. */
	private void compute() {
		CosmeticModel m = model;
		float side = m.mirror ? -1 : 1;
		for (int b = 0; b < m.bones; b++) {
			float[] src = local;
			int at = 0;
			if (rest != null && still[b]) {
				src = rest;
				at = b * 12;
			} else {
				local(b);
			}
			int parent = m.parent[b];
			if (parent < 0) {
				System.arraycopy(src, at, bone, b * 12, 12);
			} else {
				Mat.mul(bone, parent * 12, src, at, bone, b * 12);
			}
		}
	}

	/** Bone {@code b}'s transform about its parent into {@link #local}, from its rest data and the sampled offsets. */
	private void local(int b) {
		CosmeticModel m = model;
		float side = m.mirror ? -1 : 1;
		{
			int i = b * 3;
			float rx = m.rotation[i] + (float) Math.toRadians(rot[i]);
			float ry = m.rotation[i + 1] + side * (float) Math.toRadians(rot[i + 1]);
			float rz = m.rotation[i + 2] + side * (float) Math.toRadians(rot[i + 2]);
			Mat.rotationZYX(rz, ry, rx, local, 0);
			float sx = scl[i];
			float sy = scl[i + 1];
			float sz = scl[i + 2];
			for (int k = 0; k < 3; k++) {
				local[k] *= sx;
				local[3 + k] *= sy;
				local[6 + k] *= sz;
			}
			float px = m.pivot[i];
			float py = m.pivot[i + 1];
			float pz = m.pivot[i + 2];
			// T(pivot + offset) · R · S · T(-pivot)
			local[9] = px + side * pos[i] / 16F - (local[0] * px + local[3] * py + local[6] * pz);
			local[10] = py - pos[i + 1] / 16F - (local[1] * px + local[4] * py + local[7] * pz);
			local[11] = pz + pos[i + 2] / 16F - (local[2] * px + local[5] * py + local[8] * pz);
		}
	}

	/** Bone {@code b}'s transform of {@code (x, y, z)} into {@code out} (tests, previews). */
	public float[] transform(int b, float x, float y, float z) {
		int o = b * 12;
		scratch[0] = bone[o] * x + bone[o + 3] * y + bone[o + 6] * z + bone[o + 9];
		scratch[1] = bone[o + 1] * x + bone[o + 4] * y + bone[o + 7] * z + bone[o + 10];
		scratch[2] = bone[o + 2] * x + bone[o + 5] * y + bone[o + 8] * z + bone[o + 11];
		return scratch;
	}

	/** The clip being played (-1: none), for the dev tour. */
	public int playing() {
		return playing;
	}
}
