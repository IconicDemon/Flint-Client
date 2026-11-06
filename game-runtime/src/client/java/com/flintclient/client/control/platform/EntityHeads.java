package com.flintclient.client.control.platform;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.sheep.Sheep;
import org.jspecify.annotations.Nullable;

/**
 * What {@link EntityIcons} asks of Minecraft's models and renderers, in this line's terms (render states and record polygons,
 * as 1.21.2 and later have them). Minecraft 1.21.1 has its own copy of this file (its overlay): no render states, and its
 * polygons are package-private.
 */
final class EntityHeads {
	/** Floats per polygon from {@link #polygons}: its normal (3), then four corners of x, y, z, u, v. */
	static final int POLYGON_FLOATS = 23;

	private EntityHeads() {
	}

	/** The texture {@code entity} is drawn with, asked of its renderer (it fills a render state: not every frame). */
	@SuppressWarnings({"unchecked", "rawtypes"})
	static @Nullable Identifier texture(LivingEntity entity, float partialTick) {
		EntityRenderer renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
		if (!(renderer instanceof LivingEntityRenderer living)) {
			return null;
		}
		Object state = living.createRenderState(entity, partialTick);
		return state instanceof LivingEntityRenderState s ? living.getTextureLocation(s) : null;
	}

	/** The model {@code entity}'s renderer draws it with, asked up front (a mob not drawn yet this session); null for none. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	static @Nullable Object model(LivingEntity entity) {
		EntityRenderer renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
		return renderer instanceof LivingEntityRenderer living ? living.getModel() : null;
	}

	/** A part's resting pose (as the model defines it, not mid-animation): x, y, z, xRot, yRot, zRot, xScale, yScale, zScale. */
	static void pose(ModelPart part, float[] out) {
		net.minecraft.client.model.geom.PartPose p = part.getInitialPose();
		out[0] = p.x();
		out[1] = p.y();
		out[2] = p.z();
		out[3] = p.xRot();
		out[4] = p.yRot();
		out[5] = p.zRot();
		out[6] = p.xScale();
		out[7] = p.yScale();
		out[8] = p.zScale();
	}

	/** The model's root part (where a horse's head_parts or a slime's eyes are found), or null. */
	static @Nullable ModelPart root(Object model) {
		return model instanceof Model<?> m ? m.root() : null;
	}

	/** The parts of {@code model} its head is in (into {@code out}); how many. */
	static int headParts(Object model, ModelPart[] out) {
		if (model instanceof Model<?> m) {
			ModelPart head = EntityIcons.findHead(m.root());
			if (head != null) {
				out[0] = head;
				return 1;
			}
		}
		return 0;
	}

	/** A box's corners (min x, y, z, max x, y, z, model units) into {@code out}. */
	static void bounds(Object cube, float[] out) {
		ModelPart.Cube c = (ModelPart.Cube) cube;
		out[0] = c.minX;
		out[1] = c.minY;
		out[2] = c.minZ;
		out[3] = c.maxX;
		out[4] = c.maxY;
		out[5] = c.maxZ;
	}

	/** A box's faces into {@code out} ({@link #POLYGON_FLOATS} each, texture coordinates 0..1); how many. */
	static int polygons(Object cube, float[] out) {
		ModelPart.Polygon[] polygons = ((ModelPart.Cube) cube).polygons;
		int n = 0;
		for (ModelPart.Polygon p : polygons) {
			if (p == null || p.vertices().length < 4 || (n + 1) * POLYGON_FLOATS > out.length) {
				continue;
			}
			int at = n * POLYGON_FLOATS;
			out[at] = p.normal().x();
			out[at + 1] = p.normal().y();
			out[at + 2] = p.normal().z();
			for (int v = 0; v < 4; v++) {
				ModelPart.Vertex vertex = p.vertices()[v];
				int vo = at + 3 + v * 5;
				out[vo] = vertex.x();
				out[vo + 1] = vertex.y();
				out[vo + 2] = vertex.z();
				out[vo + 3] = vertex.u();
				out[vo + 4] = vertex.v();
			}
			n++;
		}
		return n;
	}

	/** A sheep's wool colour (0xRRGGBB) while it has wool, else -1. */
	static int woolColor(LivingEntity entity) {
		return entity instanceof Sheep sheep && !sheep.isSheared() && !sheep.isBaby() ? sheep.getColor().getTextureDiffuseColor() & 0xFFFFFF : -1;
	}

	/** The sheep's wool texture. */
	static Identifier woolTexture() {
		return Identifier.withDefaultNamespace("textures/entity/sheep/sheep_wool.png");
	}
}
