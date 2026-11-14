package com.flintclient.legacy.cosmetics;

import com.flintclient.client.cosmetics.CosmeticAnimation;
import com.flintclient.client.cosmetics.CosmeticKind;
import com.flintclient.client.cosmetics.CosmeticModel;
import com.flintclient.client.cosmetics.EmotePose;
import com.flintclient.client.cosmetics.ModelCosmetics.Loaded;
import com.flintclient.client.cosmetics.ModelPose;
import com.flintclient.client.platform.Texture;
import java.util.UUID;
import net.minecraft.client.render.vertex.BufferBuilder;
import net.minecraft.client.render.vertex.DefaultVertexFormat;
import net.minecraft.client.render.vertex.Tesselator;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;

/**
 * One player's 3D cosmetics on 1.8.9's render thread (26.3's {@code Worn}): per worn kind an {@link Instance} (the loaded
 * model, its own pose and clock), and what the player did this frame. Kept per player while they are drawn and reused, so
 * drawing allocates nothing. A model is drawn bone by bone with 1.8.9's tessellator: OpenGL's model view is the player part's
 * (the layer put it there, as Minecraft's own layers do), and each bone's matrix is applied here.
 */
public final class Worn189 {
	final UUID id;
	final @Nullable Instance[] instances = new Instance[CosmeticKind.WORN.length];
	int motion;
	boolean hidesCape;
	CosmeticAnimation.@Nullable Clip emote;
	double emoteSeconds;
	float emoteWeight = 1;
	final EmotePose emotePose = new EmotePose();
	long lastSeen;
	/** The frame these were last brought up to date ({@link Cosmetics189#frames}). */
	long frame = -1;

	Worn189(UUID id) {
		this.id = id;
	}

	boolean instancesEmpty() {
		for (Instance i : instances) {
			if (i != null) {
				return false;
			}
		}
		return true;
	}

	void clear() {
		java.util.Arrays.fill(instances, null);
		emote = null;
		hidesCape = false;
	}

	static final class Instance {
		final Loaded<Texture> loaded;
		final @Nullable ModelPose pose;
		final boolean animated;
		final long start = System.nanoTime();
		long posedAt = Long.MIN_VALUE;
		float frameV;
		float frameScale = 1;

		Instance(Loaded<Texture> loaded) {
			this.loaded = loaded;
			CosmeticModel model = loaded.model;
			this.pose = model == null ? null : new ModelPose(model, loaded.animation);
			this.animated = pose != null && pose.animated();
		}

		double seconds(long now) {
			return (now - start) / 1e9;
		}

		/**
		 * Draws the bones hanging from {@code anchor} in their pose, faces smaller than {@code minArea} left out. The texture is
		 * bound by the caller. Returns the vertices written.
		 */
		int draw(int anchor, float minArea) {
			ModelPose mp = pose;
			if (mp == null) {
				return 0;
			}
			CosmeticModel m = mp.model;
			float[] bones = mp.bone;
			float[] q = m.quads;
			float[] areas = m.area;
			float v0 = frameV;
			float vs = frameScale;
			BufferBuilder buffer = Tesselator.getInstance().getBuffer();
			int written = 0;
			boolean begun = false;
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
				for (int quad = qs; quad < qe; quad++) {
					if (areas[quad] < minArea) {
						continue;
					}
					if (!begun) {
						buffer.begin(GL11.GL_QUADS, DefaultVertexFormat.POSITION_TEX_NORMAL);
						begun = true;
					}
					int at = quad * CosmeticModel.QUAD;
					float lx = q[at + 20];
					float ly = q[at + 21];
					float lz = q[at + 22];
					float nx = b00 * lx + b10 * ly + b20 * lz;
					float ny = b01 * lx + b11 * ly + b21 * lz;
					float nz = b02 * lx + b12 * ly + b22 * lz;
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
						buffer.vertex(b00 * x + b10 * y + b20 * z + b30, b01 * x + b11 * y + b21 * z + b31, b02 * x + b12 * y + b22 * z + b32)
							.texture(q[v + 3], v0 + q[v + 4] * vs).normal(nx, ny, nz).nextVertex();
					}
					written += 4;
				}
			}
			if (begun) {
				Tesselator.getInstance().end();
			}
			return written;
		}
	}
}
