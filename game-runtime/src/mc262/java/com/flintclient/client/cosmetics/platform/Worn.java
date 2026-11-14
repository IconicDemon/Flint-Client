package com.flintclient.client.cosmetics.platform;

import com.flintclient.client.cosmetics.CosmeticAnimation;
import com.flintclient.client.cosmetics.CosmeticKind;
import com.flintclient.client.cosmetics.CosmeticModel;
import com.flintclient.client.cosmetics.EmotePose;
import com.flintclient.client.cosmetics.ModelCosmetics.Loaded;
import com.flintclient.client.cosmetics.ModelPose;
import com.flintclient.client.platform.Texture;
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
			this.renderType = loaded.translucent ? RenderTypes.entityTranslucent(id) : RenderTypes.entityCutout(id);
			for (int a = 0; a < drawers.length; a++) {
				drawers[a] = new Drawer(this, a);
			}
		}

		/** Seconds since this instance was first drawn. */
		double seconds(long now) {
			return (now - start) / 1e9;
		}
	}

	/**
	 * Draws one instance's bones that hang from one part, in the pose its {@link Instance#pose} has, through Minecraft's batched
	 * entity buffers (as Minecraft's own models are drawn): every vertex transformed here, nothing allocated.
	 */
	static final class Drawer implements SubmitNodeCollector.CustomGeometryRenderer {
		final Instance instance;
		final int anchor;
		int light;
		int overlay;
		int color = -1;
		/** The texture frame shown: v = v0 + v · scale. */
		float frameV;
		float frameScale = 1;
		/** Faces smaller than this (square pixels) are left out: far away they are under a pixel on screen. */
		float minArea;

		Drawer(Instance instance, int anchor) {
			this.instance = instance;
			this.anchor = anchor;
		}

		@Override
		public void render(PoseStack.Pose pose, VertexConsumer buffer) {
			ModelPose mp = instance.pose;
			if (mp == null) {
				return;
			}
			long began = System.nanoTime();
			int written = 0;
			CosmeticModel m = mp.model;
			float[] bones = mp.bone;
			Matrix4f p = pose.pose();
			Matrix3f n = pose.normal();
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
			float[] q = m.quads;
			float[] areas = m.area;
			float minArea = this.minArea;
			int light = this.light;
			int overlay = this.overlay;
			int color = this.color;
			float v0 = frameV;
			float vs = frameScale;
			for (int b = 0; b < m.bones; b++) {
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
				float c00 = p00 * b00 + p10 * b01 + p20 * b02;
				float c01 = p01 * b00 + p11 * b01 + p21 * b02;
				float c02 = p02 * b00 + p12 * b01 + p22 * b02;
				float c10 = p00 * b10 + p10 * b11 + p20 * b12;
				float c11 = p01 * b10 + p11 * b11 + p21 * b12;
				float c12 = p02 * b10 + p12 * b11 + p22 * b12;
				float c20 = p00 * b20 + p10 * b21 + p20 * b22;
				float c21 = p01 * b20 + p11 * b21 + p21 * b22;
				float c22 = p02 * b20 + p12 * b21 + p22 * b22;
				float c30 = p00 * b30 + p10 * b31 + p20 * b32 + p30;
				float c31 = p01 * b30 + p11 * b31 + p21 * b32 + p31;
				float c32 = p02 * b30 + p12 * b31 + p22 * b32 + p32;
				float k00 = n00 * b00 + n10 * b01 + n20 * b02;
				float k01 = n01 * b00 + n11 * b01 + n21 * b02;
				float k02 = n02 * b00 + n12 * b01 + n22 * b02;
				float k10 = n00 * b10 + n10 * b11 + n20 * b12;
				float k11 = n01 * b10 + n11 * b11 + n21 * b12;
				float k12 = n02 * b10 + n12 * b11 + n22 * b12;
				float k20 = n00 * b20 + n10 * b21 + n20 * b22;
				float k21 = n01 * b20 + n11 * b21 + n21 * b22;
				float k22 = n02 * b20 + n12 * b21 + n22 * b22;
				for (int quad = qs; quad < qe; quad++) {
					if (areas[quad] < minArea) {
						continue;
					}
					int at = quad * CosmeticModel.QUAD;
					float lx = q[at + 20];
					float ly = q[at + 21];
					float lz = q[at + 22];
					float nx = k00 * lx + k10 * ly + k20 * lz;
					float ny = k01 * lx + k11 * ly + k21 * lz;
					float nz = k02 * lx + k12 * ly + k22 * lz;
					float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
					if (len > 1e-6F) {
						nx /= len;
						ny /= len;
						nz /= len;
					}
					for (int i = 0; i < 4; i++) {
						int v = at + i * 5;
						float x = q[v];
						float y = q[v + 1];
						float z = q[v + 2];
						buffer.addVertex(c00 * x + c10 * y + c20 * z + c30, c01 * x + c11 * y + c21 * z + c31, c02 * x + c12 * y + c22 * z + c32,
							color, q[v + 3], v0 + q[v + 4] * vs, overlay, light, nx, ny, nz);
					}
					written += 4;
				}
			}
			Mc263Cosmetics.drawNanos += System.nanoTime() - began;
			Mc263Cosmetics.vertices += written;
		}
	}
}
