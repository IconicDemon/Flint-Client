package com.flintclient.client.cosmetics.platform;

import com.flintclient.client.mixin.cosmetics.RenderTypeCosmeticsInvoker;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/**
 * The 3D cosmetics' blended faces of one texture, put in order here instead of by Minecraft: Minecraft's
 * {@code entity_translucent} sorts every quad of the batch by its distance from the eye every frame, from scratch (the biggest
 * single cost of a see-through hat). This is the same render type without that sort: the drawers submitted this pass are
 * drawn, all of them, when the first is asked to, the farthest wearer first, each one's faces farthest first (kept in order
 * from frame to frame, so a frame costs a pass over them). The same back-to-front order Minecraft makes, barring two wearers'
 * blended faces interleaving in depth (two players inside each other). Render thread.
 */
final class SortedFaces {
	private static final Map<Identifier, SortedFaces> BY_TEXTURE = new HashMap<>();

	final RenderType type;
	private Worn.Drawer[] queue = new Worn.Drawer[8];
	private int size;
	private long frame = -1;

	private SortedFaces(Identifier texture) {
		// RenderTypes.entityTranslucent(texture) as Minecraft builds it, but for sortOnUpload.
		RenderSetup setup = RenderSetup.builder(RenderPipelines.ENTITY_TRANSLUCENT)
			.setOitPipelines(RenderPipelines.OIT_ENTITY)
			.withTexture("Sampler0", texture)
			.useLightmap()
			.useOverlay()
			.affectsCrumbling()
			.setOutline(RenderSetup.OutlineProperty.AFFECTS_OUTLINE)
			.createRenderSetup();
		this.type = RenderTypeCosmeticsInvoker.flint$create("flint_cosmetics_translucent", setup);
	}

	/** The queue of {@code texture}'s blended faces (made once per texture). */
	static SortedFaces of(Identifier texture) {
		return BY_TEXTURE.computeIfAbsent(texture, SortedFaces::new);
	}

	/** {@code d} was submitted (its pose recorded on it): drawn with the others at the batch's first draw. */
	void add(Worn.Drawer d, long now) {
		if (frame != now) {
			// Whatever a past frame left (never drawn) is dropped.
			size = 0;
			frame = now;
		}
		if (size == queue.length) {
			queue = java.util.Arrays.copyOf(queue, size * 2);
		}
		queue[size++] = d;
	}

	/** Draws everything queued into {@code buffer}, farthest wearer first; nothing when it was drawn already. */
	void drain(VertexConsumer buffer) {
		int n = size;
		if (n == 0) {
			return;
		}
		Worn.Drawer[] q = queue;
		// Insertion sort by the eye distance of each drawer's origin, farthest first (a handful; nearly in order frame to frame).
		for (int i = 1; i < n; i++) {
			Worn.Drawer d = q[i];
			float key = d.originDepth();
			int j = i - 1;
			while (j >= 0 && q[j].originDepth() < key) {
				q[j + 1] = q[j];
				j--;
			}
			q[j + 1] = d;
		}
		size = 0;
		for (int i = 0; i < n; i++) {
			q[i].renderRecorded(buffer);
			q[i] = null;
		}
	}
}
