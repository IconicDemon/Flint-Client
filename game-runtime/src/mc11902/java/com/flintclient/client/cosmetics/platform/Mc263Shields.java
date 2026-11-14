package com.flintclient.client.cosmetics.platform;

import com.flintclient.client.compat.state.SubmitNodeCollector;
import com.flintclient.client.cosmetics.CosmeticKind;
import com.flintclient.client.cosmetics.CosmeticModel;
import com.flintclient.client.cosmetics.ModelPose;
import com.flintclient.client.platform.Texture;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.ShieldModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * Flint shields on Minecraft 1.19.2 and 1.19, as on 26.3: while a player's held item is drawn
 * ({@code ItemInHandLayerCosmeticsMixin}, and your own in first person), the shield they hold is drawn as the one they wear:
 * its geometry in the vanilla shield's place (a shield geometry is laid out around Minecraft's: the plate at Bedrock x -6..6,
 * y 1..23, z -2..-1, the handle at x -1..1, y 9..15, z -1..5), or a texture-only shield on Minecraft's shield model (the
 * texture in its 64×64 layout, any whole multiple, frames as a vertical strip). Banner patterns and the glint aren't drawn on
 * a Flint shield. 1.19 draws the shield in {@code BlockEntityWithoutLevelRenderer.renderByItem}
 * ({@code ShieldSpecialRendererCosmeticsMixin}), flipped as that does.
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

	/** The player whose held item is drawn next ({@code entity}): their Flint shield, if they wear one. */
	public static void begin(LivingEntity entity) {
		self = false;
		Worn w = entity instanceof CosmeticsState cs ? cs.flint$worn() : null;
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

	/** Whether the player holds a shield in either hand. */
	static boolean holding(Player player) {
		return player.getMainHandItem().getItem() instanceof net.minecraft.world.item.ShieldItem
			|| player.getOffhandItem().getItem() instanceof net.minecraft.world.item.ShieldItem;
	}

	/**
	 * Draws the Flint shield in place of Minecraft's (at {@code pose} as renderByItem gets it, before its flip); false when
	 * there is none to draw (Minecraft's is drawn).
	 */
	public static boolean render(ShieldModel model, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
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
			pose.pushPose();
			// As renderByItem lays Minecraft's shield model out.
			pose.scale(1.0F, -1.0F, -1.0F);
			try {
				if (inst.pose == null) {
					Texture texture = inst.loaded.texture;
					if (texture == null) {
						return false;
					}
					Worn.Drawer d = inst.drawers[0];
					VertexConsumer buffer = buffers.getBuffer(RenderType.entityCutout(texture.id()));
					if (inst.loaded.item.frames() > 1) {
						if (inst.frames == null) {
							inst.frames = new FrameMapping(d);
						}
						buffer = inst.frames.over(buffer);
					}
					model.renderToBuffer(pose, buffer, light, overlay, 1.0F, 1.0F, 1.0F, 1.0F);
					return true;
				}
				CosmeticsLayer.pose(inst, ModelPose.IDLE, System.nanoTime(), true);
				CosmeticModel m = inst.pose.model;
				pose.translate(0, -CENTRE, 0);
				SubmitNodeCollector collector = new SubmitNodeCollector(buffers);
				for (int a = 0; a < CosmeticModel.ANCHORS; a++) {
					if ((m.anchorsUsed & 1 << a) != 0) {
						Worn.Drawer d = inst.drawers[a];
						d.light = light;
						d.overlay = overlay;
						d.minArea = 0;
						collector.submitCustomGeometry(pose, inst.renderType, d);
					}
				}
				return true;
			} finally {
				pose.popPose();
			}
		} catch (RuntimeException | LinkageError e) {
			Mc263Cosmetics.failed(e);
			return false;
		}
	}

	/**
	 * A texture-only shield's current frame: Minecraft's shield model writes the texture coordinates of the whole texture
	 * (0..1), moved down the strip to the frame the drawer says as they go into the buffer.
	 */
	static final class FrameMapping {
		private final Worn.Drawer drawer;
		private VertexConsumer target;
		private final VertexConsumer wrapper = new VertexConsumer() {
			@Override
			public VertexConsumer vertex(double x, double y, double z) {
				target.vertex(x, y, z);
				return this;
			}

			@Override
			public VertexConsumer color(int r, int g, int b, int a) {
				target.color(r, g, b, a);
				return this;
			}

			@Override
			public VertexConsumer uv(float u, float v) {
				target.uv(u, drawer.frameV + v * drawer.frameScale);
				return this;
			}

			@Override
			public VertexConsumer overlayCoords(int u, int v) {
				target.overlayCoords(u, v);
				return this;
			}

			@Override
			public VertexConsumer uv2(int u, int v) {
				target.uv2(u, v);
				return this;
			}

			@Override
			public VertexConsumer normal(float x, float y, float z) {
				target.normal(x, y, z);
				return this;
			}

			@Override
			public void endVertex() {
				target.endVertex();
			}

			@Override
			public void vertex(float x, float y, float z, float r, float g, float b, float a, float u, float v, int overlay, int light,
				float nx, float ny, float nz) {
				target.vertex(x, y, z, r, g, b, a, u, drawer.frameV + v * drawer.frameScale, overlay, light, nx, ny, nz);
			}

			@Override
			public void defaultColor(int r, int g, int b, int a) {
				target.defaultColor(r, g, b, a);
			}

			@Override
			public void unsetDefaultColor() {
				target.unsetDefaultColor();
			}
		};

		FrameMapping(Worn.Drawer drawer) {
			this.drawer = drawer;
		}

		/** {@code buffer} with the frame's texture coordinates (render thread: one at a time). */
		VertexConsumer over(VertexConsumer buffer) {
			target = buffer;
			return wrapper;
		}
	}
}
