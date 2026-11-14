package com.flintclient.client.cosmetics.platform;

import com.flintclient.client.cosmetics.CosmeticKind;
import com.flintclient.client.cosmetics.CosmeticModel;
import com.flintclient.client.cosmetics.ModelPose;
import com.flintclient.client.platform.Texture;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.ShieldModel;
import com.flintclient.client.compat.state.SubmitNodeCollector;
import net.minecraft.client.renderer.RenderType;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.jspecify.annotations.Nullable;

/**
 * Flint shields (1.21.1: Minecraft draws a held shield in BlockEntityWithoutLevelRenderer): while a player's held item is drawn
 * ({@code ItemInHandLayerCosmeticsMixin}, and your own in first person),
 * the shield they hold is drawn as the one they wear: its geometry in the vanilla shield's place (a shield geometry is laid
 * out around Minecraft's: the plate at Bedrock x -6..6, y 1..23, z -2..-1, the handle at x -1..1, y 9..15, z -1..5), or a
 * texture-only shield on Minecraft's shield model (the texture in its 64×64 layout, any whole multiple, frames as a vertical
 * strip). Banner patterns and the glint aren't drawn on a Flint shield.
 */
public final class Mc263Shields {
	private static final int SHIELD = indexOf();
	/** Shield geometry is 12 pixels above Minecraft's model's centre (its plate spans y 1..23, Minecraft's -11..11). */
	private static final float CENTRE = 12F / 16F;
	private static Worn.@Nullable Instance current;
	private static boolean self;

	private Mc263Shields() {
	}

	private static int indexOf() {
		for (int k = 0; k < CosmeticKind.WORN.length; k++) {
			if (CosmeticKind.WORN[k] == CosmeticKind.SHIELD) {
				return k;
			}
		}
		return -1;
	}

	/** The player whose held item is drawn next ({@code state}): their Flint shield, if they wear one. */
	public static void begin(@Nullable Object state) {
		self = false;
		Worn w = state instanceof CosmeticsState cs ? cs.flint$worn() : null;
		current = w == null ? null : w.instances[SHIELD];
	}

	/** Your own held item (first person). */
	public static void beginSelf() {
		self = true;
		Worn w = Mc263Cosmetics.self();
		current = w == null ? null : w.instances[SHIELD];
	}

	public static void end() {
		current = null;
	}

	/** Whether a shield's geometry is worn on an arm (it hangs from an arm anchor) rather than held in the hand's place. */
	static boolean onArm(Worn.Instance inst) {
		CosmeticModel m = inst.pose == null ? null : inst.pose.model;
		return m != null && (m.anchorsUsed & (1 << CosmeticModel.LEFT_ARM | 1 << CosmeticModel.RIGHT_ARM)) != 0;
	}

	/** Draws the Flint shield in place of Minecraft's; false when there is none to draw (Minecraft's is drawn). */
	public static boolean submit(ShieldModel model, PoseStack pose, SubmitNodeCollector collector, int light, int overlay, int outline) {
		Worn.Instance inst = current;
		if (inst == null || !Mc263Cosmetics.GUARD.on() || inst.pose == null && inst.loaded.texture == null) {
			return false;
		}
		if (onArm(inst)) {
			// Worn on the arm (the layer draws it there): the shield in the hand isn't drawn, except your own in first person,
			// where the arm isn't drawn with it.
			return !self;
		}
		try {
			CosmeticsLayer.frame(inst, inst.loaded.item, System.currentTimeMillis());
			if (inst.pose == null) {
				Texture texture = inst.loaded.texture;
				if (texture == null) {
					return false;
				}
				Worn.Drawer d = inst.drawers[0];
				// Minecraft's shield model with the texture (an animated one's coordinates moved to the frame shown).
				FrameMapping frames = frames(inst, d);
				frames.model = model;
				frames.light = light;
				frames.overlay = overlay;
				collector.submitCustomGeometry(pose, RenderType.entityCutout(texture.id()), frames);
				return true;
			}
			CosmeticsLayer.pose(inst, ModelPose.IDLE, System.nanoTime(), true);
			CosmeticModel m = inst.pose.model;
			pose.pushPose();
			pose.translate(0, -CENTRE, 0);
			for (int a = 0; a < CosmeticModel.ANCHORS; a++) {
				if ((m.anchorsUsed & 1 << a) != 0) {
					Worn.Drawer d = inst.drawers[a];
					d.light = light;
					d.overlay = overlay;
					collector.submitCustomGeometry(pose, inst.renderType, d);
				}
			}
			pose.popPose();
			return true;
		} catch (RuntimeException | LinkageError e) {
			Mc263Cosmetics.failed(e);
			return false;
		}
	}

	/** The texture coordinates of a texture-only shield's current frame (Minecraft's 0..1 of one frame, moved down the strip). */
	private static FrameMapping frames(Worn.Instance inst, Worn.Drawer d) {
		if (inst.frames == null) {
			inst.frames = new FrameMapping(d);
		}
		return inst.frames;
	}

	/**
	 * Minecraft's shield model drawn with the texture coordinates of one frame, as the drawer's frame says (1.21.x's model
	 * submission has no texture mapping of its own): the model writes through this, which moves each v down the strip.
	 */
	static final class FrameMapping implements SubmitNodeCollector.CustomGeometryRenderer, VertexConsumer {
		private final Worn.Drawer drawer;
		private final PoseStack stack = new PoseStack();
		@Nullable ShieldModel model;
		int light;
		int overlay;
		private @Nullable VertexConsumer out;

		FrameMapping(Worn.Drawer drawer) {
			this.drawer = drawer;
		}

		@Override
		public void render(PoseStack.Pose pose, VertexConsumer buffer) {
			ShieldModel m = model;
			if (m == null) {
				return;
			}
			out = buffer;
			try {
				stack.last().pose().set(pose.pose());
				stack.last().normal().set(pose.normal());
				m.renderToBuffer(stack, this, light, overlay, -1);
			} finally {
				out = null;
			}
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			out.addVertex(x, y, z);
			return this;
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			out.setColor(r, g, b, a);
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			out.setUv(u, drawer.frameV + v * drawer.frameScale);
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			out.setUv1(u, v);
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			out.setUv2(u, v);
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			out.setNormal(x, y, z);
			return this;
		}

	}
}
