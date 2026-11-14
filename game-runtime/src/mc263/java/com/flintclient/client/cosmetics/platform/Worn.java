package com.flintclient.client.cosmetics.platform;

import com.flintclient.client.cosmetics.CosmeticAnimation;
import com.flintclient.client.cosmetics.CosmeticKind;
import com.flintclient.client.cosmetics.CosmeticModel;
import com.flintclient.client.cosmetics.EmotePose;
import com.flintclient.client.cosmetics.HiddenFaces;
import com.flintclient.client.cosmetics.ModelCosmetics.Loaded;
import com.flintclient.client.cosmetics.ModelPose;
import com.flintclient.client.platform.Texture;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.UUID;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * One player's 3D cosmetics on the render thread: per worn kind an {@link Instance} (the loaded model, its own pose and clock,
 * and one {@link Drawer} per player part it hangs from), and what the player did this frame. Kept per player while they are
 * drawn and reused every frame, so drawing allocates nothing.
 */
public final class Worn {
	final UUID id;
	final @Nullable Instance[] instances = new Instance[CosmeticKind.WORN.length];
	/** {@link ModelPose#IDLE} etc. */
	int motion;
	/** You, seen from your own eyes (a first-person body mod): what is on the head would block the view. */
	boolean ownView;
	/** The wings worn ask to hide the cape. */
	boolean hidesCape;
	/** Your emote playing now (null: none), and how far into it. */
	CosmeticAnimation.@Nullable Clip emote;
	double emoteSeconds;
	/** How far the emote has blended in (0..1). */
	float emoteWeight = 1;
	final EmotePose emotePose = new EmotePose();
	long lastSeen;

	Worn(UUID id) {
		this.id = id;
	}

	/** One worn model of one player. */
	static final class Instance {
		final Loaded<Texture> loaded;
		final @Nullable ModelPose pose;
		final Drawer[] drawers = new Drawer[CosmeticModel.ANCHORS];
		final RenderType renderType;
		final boolean animated;
		final long start = System.nanoTime();
		long posedAt = Long.MIN_VALUE;
		/** A texture-only shield's frame mapping, made the first time it is drawn. */
		Mc263Shields.@Nullable FrameMapping frames;

		Instance(Loaded<Texture> loaded) {
			this.loaded = loaded;
			CosmeticModel model = loaded.model;
			this.pose = model == null ? null : new ModelPose(model, loaded.animation);
			this.animated = pose != null && pose.animated();
			Texture texture = loaded.texture;
			Identifier id = texture == null ? Mc263Cosmetics.MISSING : texture.id();
			// Cut out (no sorting) unless the texture is really see-through somewhere: sorting every face each frame is the cost.
			// A blended texture's faces whose pixels are each fully opaque or not drawn look the same cut out: only the others are
			// blended (and sorted), in a second set of drawers.
			boolean split = loaded.translucent && model != null && model.blendMarked;
			byte[] blend = model == null ? null : model.blend;
			boolean anyBlended = blend != null;
			boolean anyCut = false;
			if (blend != null) {
				for (byte b : blend) {
					anyCut |= b == 0;
				}
			}
			boolean blendAll = loaded.translucent && (!split || anyBlended && !anyCut);
			boolean two = split && anyBlended && anyCut;
			this.renderType = blendAll ? RenderTypes.entityTranslucent(id) : RenderTypes.entityCutout(id);
			this.blendType = two ? RenderTypes.entityTranslucent(id) : null;
			// Blended faces are put in order by Flint (SortedFaces), not sorted from scratch by Minecraft every frame.
			this.sorted = blendAll || two ? SortedFaces.of(id) : null;
			this.blendDrawers = two ? new Drawer[CosmeticModel.ANCHORS] : null;
			for (int a = 0; a < drawers.length; a++) {
				drawers[a] = new Drawer(this, a, two ? 0 : -1, blendAll ? sorted : null);
				if (blendDrawers != null) {
					blendDrawers[a] = new Drawer(this, a, 1, sorted);
				}
			}
		}

		/** A second set of drawers for the faces that need blending (null: one set draws everything), and their render type. */
		final Drawer @Nullable [] blendDrawers;
		final @Nullable RenderType blendType;
		/** The blended faces' queue (null: nothing blended). */
		final @Nullable SortedFaces sorted;

		/** Submits anchor {@code a}'s faces at {@code pose} (both sets when the model is split). */
		void submit(SubmitNodeCollector collector, PoseStack pose, int a, int light, int overlay, float minArea) {
			submit(collector, pose, drawers[a], renderType, light, overlay, minArea);
			Drawer[] more = blendDrawers;
			RenderType type = blendType;
			if (more != null && type != null) {
				submit(collector, pose, more[a], type, light, overlay, minArea);
			}
		}

		private static void submit(SubmitNodeCollector collector, PoseStack pose, Drawer d, RenderType type, int light, int overlay, float minArea) {
			d.light = light;
			d.overlay = overlay;
			d.minArea = minArea;
			SortedFaces s = d.sorted;
			if (s != null && Mc263Cosmetics.sortedFaces) {
				d.record(pose.last());
				s.add(d, Mc263Cosmetics.frameCount());
				collector.submitCustomGeometry(pose, s.type, d);
			} else {
				d.queued = false;
				collector.submitCustomGeometry(pose, type, d);
			}
		}

		/** Seconds since this instance was first drawn. */
		double seconds(long now) {
			return (now - start) / 1e9;
		}
	}

	/**
	 * Draws one instance's bones that hang from one part, in the pose its {@link Instance#pose} has, through Minecraft's batched
	 * entity buffers (as Minecraft's own models are drawn), nothing allocated after the first frame. Two steps: which faces
	 * draw (far away, the smallest are left out; the faces of a solid box turned away from the eye can't show,
	 * {@link HiddenFaces}), then their vertices, written straight into the buffer in one run when it is Minecraft's plain entity
	 * buffer ({@link EntityVertices}), else through {@code addVertex}. The same bytes either way.
	 */
	static final class Drawer implements SubmitNodeCollector.CustomGeometryRenderer {
		/** Per bone: the pose's matrix times the bone's (12 floats), then the normals' (9). */
		private static final int MAT = 21;
		final Instance instance;
		final int anchor;
		/** Which faces: -1 all, 0 those cut out, 1 those blended ({@link CosmeticModel#blend}). */
		final int layer;
		int light;
		int overlay;
		int color = -1;
		/** The texture frame shown: v = v0 + v · scale. */
		float frameV;
		float frameScale = 1;
		/** Faces smaller than this (square pixels) are left out: far away they are under a pixel on screen. */
		float minArea;
		/** This frame's faces to draw (quad indices, by bone), each bone's first in it, and the bones' matrices. */
		private int[] faces = new int[0];
		private int[] boneFirst = new int[0];
		private float[] mats = new float[0];
		private final float[] inverse = new float[HiddenFaces.SIZE];

		Drawer(Instance instance, int anchor, int layer, @Nullable SortedFaces sorted) {
			this.layer = layer;
			this.instance = instance;
			this.anchor = anchor;
			this.sorted = sorted;
		}

		/** Blended faces: put in order with the other drawers of their texture ({@link SortedFaces}); null: drawn as they come. */
		final @Nullable SortedFaces sorted;
		/** Submitted to {@link #sorted} this pass (its pose recorded), not drawn yet. */
		boolean queued;
		private final Matrix4f recordedPose = new Matrix4f();
		private final Matrix3f recordedNormal = new Matrix3f();
		/** Per face listed this frame: its bone, its eye distance, and the faces' back-to-front order (kept frame to frame). */
		private int[] faceBone = new int[0];
		private float[] depth = new float[0];
		private int[] order = new int[0];
		private int[] lastList = new int[0];
		private int lastCount = -1;
		private long[] keys = new long[0];

		/** At submit: the pose to draw at, for {@link SortedFaces#drain}. */
		void record(PoseStack.Pose pose) {
			recordedPose.set(pose.pose());
			recordedNormal.set(pose.normal());
			queued = true;
		}

		/** The squared eye distance of this drawer's origin (its part's pivot). */
		float originDepth() {
			Matrix4f p = recordedPose;
			return p.m30() * p.m30() + p.m31() * p.m31() + p.m32() * p.m32();
		}

		/** Drawn by {@link SortedFaces#drain}, at the recorded pose, faces back to front. */
		void renderRecorded(VertexConsumer buffer) {
			queued = false;
			render(recordedPose, recordedNormal, buffer, true);
		}

		@Override
		public void render(PoseStack.Pose pose, VertexConsumer buffer) {
			SortedFaces s = sorted;
			if (s != null && Mc263Cosmetics.sortedFaces) {
				// The first of the batch draws everything queued, in order; the rest were drawn with it.
				if (queued) {
					s.drain(buffer);
				}
				return;
			}
			render(pose.pose(), pose.normal(), buffer, false);
		}

		private void render(Matrix4f p, Matrix3f n, VertexConsumer buffer, boolean backToFront) {
			ModelPose mp = instance.pose;
			if (mp == null) {
				return;
			}
			long began = System.nanoTime();
			long allocated = Mc263Cosmetics.allocBegin();
			CosmeticModel m = mp.model;
			if (faces.length < m.quadCount || boneFirst.length < m.bones + 1) {
				faces = new int[m.quadCount];
				boneFirst = new int[m.bones + 1];
				mats = new float[m.bones * MAT];
				if (sorted != null) {
					faceBone = new int[m.quadCount];
					depth = new float[m.quadCount];
					order = new int[m.quadCount];
					lastList = new int[m.quadCount];
					keys = new long[m.quadCount];
				}
			}
			int[] list = faces;
			int[] first = boneFirst;
			float[] mat = mats;
			float[] inv = inverse;
			float[] bones = mp.bone;
			float p00 = p.m00();
			float p01 = p.m01();
			float p02 = p.m02();
			float p10 = p.m10();
			float p11 = p.m11();
			float p12 = p.m12();
			float p20 = p.m20();
			float p21 = p.m21();
			float p22 = p.m22();
			float p30 = p.m30();
			float p31 = p.m31();
			float p32 = p.m32();
			float n00 = n.m00();
			float n01 = n.m01();
			float n02 = n.m02();
			float n10 = n.m10();
			float n11 = n.m11();
			float n12 = n.m12();
			float n20 = n.m20();
			float n21 = n.m21();
			float n22 = n.m22();
			float[] areas = m.area;
			float minArea = this.minArea;
			byte[] solid = m.solid;
			int layer = this.layer;
			byte[] blend = layer < 0 ? null : m.blend;
			// The eye is the origin only in the world's perspective view (a doll in a screen is drawn flat, a shader pack's
			// shadow pass from the sun): elsewhere every face draws.
			boolean cull = solid != null && Mc263Cosmetics.hiddenFaces && RenderSystem.getProjectionType() == ProjectionType.PERSPECTIVE
				&& !Mc263Cosmetics.shadowPass();
			int count = 0;
			for (int b = 0; b < m.bones; b++) {
				first[b] = count;
				int qs = m.quadStart[b];
				int qe = m.quadStart[b + 1];
				if (qs == qe || m.anchor[b] != anchor) {
					continue;
				}
				int o = b * 12;
				float b00 = bones[o];
				float b01 = bones[o + 1];
				float b02 = bones[o + 2];
				float b10 = bones[o + 3];
				float b11 = bones[o + 4];
				float b12 = bones[o + 5];
				float b20 = bones[o + 6];
				float b21 = bones[o + 7];
				float b22 = bones[o + 8];
				float b30 = bones[o + 9];
				float b31 = bones[o + 10];
				float b32 = bones[o + 11];
				// The pose's matrix times the bone's (column-major; JOML's mCR is column C, row R).
				int at = b * MAT;
				mat[at] = p00 * b00 + p10 * b01 + p20 * b02;
				mat[at + 1] = p01 * b00 + p11 * b01 + p21 * b02;
				mat[at + 2] = p02 * b00 + p12 * b01 + p22 * b02;
				mat[at + 3] = p00 * b10 + p10 * b11 + p20 * b12;
				mat[at + 4] = p01 * b10 + p11 * b11 + p21 * b12;
				mat[at + 5] = p02 * b10 + p12 * b11 + p22 * b12;
				mat[at + 6] = p00 * b20 + p10 * b21 + p20 * b22;
				mat[at + 7] = p01 * b20 + p11 * b21 + p21 * b22;
				mat[at + 8] = p02 * b20 + p12 * b21 + p22 * b22;
				mat[at + 9] = p00 * b30 + p10 * b31 + p20 * b32 + p30;
				mat[at + 10] = p01 * b30 + p11 * b31 + p21 * b32 + p31;
				mat[at + 11] = p02 * b30 + p12 * b31 + p22 * b32 + p32;
				mat[at + 12] = n00 * b00 + n10 * b01 + n20 * b02;
				mat[at + 13] = n01 * b00 + n11 * b01 + n21 * b02;
				mat[at + 14] = n02 * b00 + n12 * b01 + n22 * b02;
				mat[at + 15] = n00 * b10 + n10 * b11 + n20 * b12;
				mat[at + 16] = n01 * b10 + n11 * b11 + n21 * b12;
				mat[at + 17] = n02 * b10 + n12 * b11 + n22 * b12;
				mat[at + 18] = n00 * b20 + n10 * b21 + n20 * b22;
				mat[at + 19] = n01 * b20 + n11 * b21 + n21 * b22;
				mat[at + 20] = n02 * b20 + n12 * b21 + n22 * b22;
				boolean hide = cull && HiddenFaces.invert(mat, at, inv, 0);
				for (int quad = qs; quad < qe; quad++) {
					if (blend != null && blend[quad] != layer) {
						// The other set's face (a solid box is never blended: its six are skipped one by one).
						continue;
					}
					if (hide && solid[quad] != 0) {
						int keep = HiddenFaces.faces(m, quad, inv, 0);
						for (int i = 0; i < 6; i++) {
							if ((keep & 1 << i) != 0 && areas[quad + i] >= minArea) {
								if (backToFront) {
									faceBone[count] = b;
								}
								list[count++] = quad + i;
							}
						}
						quad += 5;
					} else if (areas[quad] >= minArea) {
						if (backToFront) {
							faceBone[count] = b;
						}
						list[count++] = quad;
					}
				}
			}
			first[m.bones] = count;
			if (count > 0) {
				if (backToFront) {
					writeBackToFront(m, buffer, count);
				} else {
					write(m, buffer, count);
				}
			}
			Mc263Cosmetics.allocEnd(allocated);
			Mc263Cosmetics.drawNanos += System.nanoTime() - began;
			Mc263Cosmetics.vertices += count * 4L;
		}

		/**
		 * The {@code count} faces chosen, farthest from the eye first (by the centre of each, as Minecraft sorts): the order of
		 * the last frame is kept and put right with an insertion sort (a frame moves few faces), made again when the faces
		 * listed changed.
		 */
		private void writeBackToFront(CosmeticModel m, VertexConsumer buffer, int count) {
			int[] list = faces;
			int[] bone = faceBone;
			float[] dist = depth;
			float[] mat = mats;
			float[] q = m.quads;
			for (int f = 0; f < count; f++) {
				int base = list[f] * CosmeticModel.QUAD;
				int o = bone[f] * MAT;
				float x = (q[base] + q[base + 5] + q[base + 10] + q[base + 15]) * 0.25F;
				float y = (q[base + 1] + q[base + 6] + q[base + 11] + q[base + 16]) * 0.25F;
				float z = (q[base + 2] + q[base + 7] + q[base + 12] + q[base + 17]) * 0.25F;
				float ex = mat[o] * x + mat[o + 3] * y + mat[o + 6] * z + mat[o + 9];
				float ey = mat[o + 1] * x + mat[o + 4] * y + mat[o + 7] * z + mat[o + 10];
				float ez = mat[o + 2] * x + mat[o + 5] * y + mat[o + 8] * z + mat[o + 11];
				dist[f] = ex * ex + ey * ey + ez * ez;
			}
			int[] ord = order;
			if (count == lastCount && java.util.Arrays.equals(list, 0, count, lastList, 0, count)) {
				for (int i = 1; i < count; i++) {
					int at = ord[i];
					float key = dist[at];
					int j = i - 1;
					while (j >= 0 && dist[ord[j]] < key) {
						ord[j + 1] = ord[j];
						j--;
					}
					ord[j + 1] = at;
				}
			} else {
				// Farthest first: ascending by (max - distance bits); distances are positive, so their bits order as they do.
				long[] k = keys;
				for (int f = 0; f < count; f++) {
					k[f] = (long) (Integer.MAX_VALUE - Float.floatToRawIntBits(dist[f])) << 32 | f;
				}
				java.util.Arrays.sort(k, 0, count);
				for (int f = 0; f < count; f++) {
					ord[f] = (int) k[f];
				}
				System.arraycopy(list, 0, lastList, 0, count);
				lastCount = count;
			}
			long at = EntityVertices.reserve(buffer, count * 4);
			int light = this.light;
			int overlay = this.overlay;
			int color = this.color;
			int abgr = EntityVertices.abgr(color);
			float v0 = frameV;
			float vs = frameScale;
			for (int i = 0; i < count; i++) {
				int f = ord[i];
				int base = list[f] * CosmeticModel.QUAD;
				int o = bone[f] * MAT;
				float lx = q[base + 20];
				float ly = q[base + 21];
				float lz = q[base + 22];
				float nx = mat[o + 12] * lx + mat[o + 15] * ly + mat[o + 18] * lz;
				float ny = mat[o + 13] * lx + mat[o + 16] * ly + mat[o + 19] * lz;
				float nz = mat[o + 14] * lx + mat[o + 17] * ly + mat[o + 20] * lz;
				float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
				if (len > 1e-6F) {
					nx /= len;
					ny /= len;
					nz /= len;
				}
				int packed = EntityVertices.normal(nx, ny, nz);
				for (int c = 0; c < 4; c++) {
					int v = base + c * 5;
					float x = q[v];
					float y = q[v + 1];
					float z = q[v + 2];
					float px = mat[o] * x + mat[o + 3] * y + mat[o + 6] * z + mat[o + 9];
					float py = mat[o + 1] * x + mat[o + 4] * y + mat[o + 7] * z + mat[o + 10];
					float pz = mat[o + 2] * x + mat[o + 5] * y + mat[o + 8] * z + mat[o + 11];
					if (at != 0) {
						EntityVertices.put(at, px, py, pz, abgr, q[v + 3], v0 + q[v + 4] * vs, overlay, light, packed);
						at += EntityVertices.SIZE;
					} else {
						buffer.addVertex(px, py, pz, color, q[v + 3], v0 + q[v + 4] * vs, overlay, light, nx, ny, nz);
					}
				}
			}
		}

		/** The {@code count} faces chosen, each bone's with its matrices. */
		private void write(CosmeticModel m, VertexConsumer buffer, int count) {
			long at = EntityVertices.reserve(buffer, count * 4);
			int[] list = faces;
			int[] first = boneFirst;
			float[] mat = mats;
			float[] q = m.quads;
			int light = this.light;
			int overlay = this.overlay;
			int color = this.color;
			int abgr = EntityVertices.abgr(color);
			float v0 = frameV;
			float vs = frameScale;
			for (int b = 0; b < m.bones; b++) {
				int fs = first[b];
				int fe = first[b + 1];
				if (fs == fe) {
					continue;
				}
				int o = b * MAT;
				float c00 = mat[o];
				float c01 = mat[o + 1];
				float c02 = mat[o + 2];
				float c10 = mat[o + 3];
				float c11 = mat[o + 4];
				float c12 = mat[o + 5];
				float c20 = mat[o + 6];
				float c21 = mat[o + 7];
				float c22 = mat[o + 8];
				float c30 = mat[o + 9];
				float c31 = mat[o + 10];
				float c32 = mat[o + 11];
				float k00 = mat[o + 12];
				float k01 = mat[o + 13];
				float k02 = mat[o + 14];
				float k10 = mat[o + 15];
				float k11 = mat[o + 16];
				float k12 = mat[o + 17];
				float k20 = mat[o + 18];
				float k21 = mat[o + 19];
				float k22 = mat[o + 20];
				for (int f = fs; f < fe; f++) {
					int base = list[f] * CosmeticModel.QUAD;
					float lx = q[base + 20];
					float ly = q[base + 21];
					float lz = q[base + 22];
					float nx = k00 * lx + k10 * ly + k20 * lz;
					float ny = k01 * lx + k11 * ly + k21 * lz;
					float nz = k02 * lx + k12 * ly + k22 * lz;
					float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
					if (len > 1e-6F) {
						nx /= len;
						ny /= len;
						nz /= len;
					}
					if (at != 0) {
						int packed = EntityVertices.normal(nx, ny, nz);
						for (int i = 0; i < 4; i++) {
							int v = base + i * 5;
							float x = q[v];
							float y = q[v + 1];
							float z = q[v + 2];
							EntityVertices.put(at, c00 * x + c10 * y + c20 * z + c30, c01 * x + c11 * y + c21 * z + c31,
								c02 * x + c12 * y + c22 * z + c32, abgr, q[v + 3], v0 + q[v + 4] * vs, overlay, light, packed);
							at += EntityVertices.SIZE;
						}
					} else {
						for (int i = 0; i < 4; i++) {
							int v = base + i * 5;
							float x = q[v];
							float y = q[v + 1];
							float z = q[v + 2];
							buffer.addVertex(c00 * x + c10 * y + c20 * z + c30, c01 * x + c11 * y + c21 * z + c31, c02 * x + c12 * y + c22 * z + c32,
								color, q[v + 3], v0 + q[v + 4] * vs, overlay, light, nx, ny, nz);
						}
					}
				}
			}
		}
	}
}
