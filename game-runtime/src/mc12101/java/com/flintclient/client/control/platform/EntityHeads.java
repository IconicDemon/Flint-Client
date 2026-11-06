package com.flintclient.client.control.platform;

import com.flintclient.client.mixin.bridge.AgeableListModelInvoker;
import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.AgeableListModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Sheep;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 1.21.1's side of {@link EntityIcons}: a renderer gives a texture straight from the entity (there are no render
 * states), a model keeps its head as a field (humanoids), behind headParts (four-legged animals) or under its root
 * (hierarchical models), and a box's faces are package-private, so they are read by their types (a run-time name lookup
 * would miss: the game's fields have intermediary names).
 */
final class EntityHeads {
	/** Floats per polygon from {@link #polygons}: its normal (3), then four corners of x, y, z, u, v. */
	static final int POLYGON_FLOATS = 23;

	private static @Nullable Field cubePolygons;
	private static @Nullable Field polygonVertices;
	private static @Nullable Field polygonNormal;
	private static @Nullable Field vertexPos;
	private static @Nullable Field vertexU;
	private static @Nullable Field vertexV;
	private static boolean reflected;

	private EntityHeads() {
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	static @Nullable ResourceLocation texture(LivingEntity entity, float partialTick) {
		EntityRenderer renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
		return renderer instanceof LivingEntityRenderer living ? living.getTextureLocation(entity) : null;
	}

	/** The model {@code entity}'s renderer draws it with, asked up front (a mob not drawn yet this session); null for none. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	static @Nullable Object model(LivingEntity entity) {
		EntityRenderer renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
		return renderer instanceof LivingEntityRenderer living ? living.getModel() : null;
	}

	/** A part's resting pose (as the model defines it): x, y, z, xRot, yRot, zRot, and a scale of 1 (1.21.1 has none). */
	static void pose(ModelPart part, float[] out) {
		net.minecraft.client.model.geom.PartPose p = part.getInitialPose();
		out[0] = p.x;
		out[1] = p.y;
		out[2] = p.z;
		out[3] = p.xRot;
		out[4] = p.yRot;
		out[5] = p.zRot;
		out[6] = 1;
		out[7] = 1;
		out[8] = 1;
	}

	/** The model's root part (where a slime's eyes are found), or null: hierarchical models only. */
	static @Nullable ModelPart root(Object model) {
		return model instanceof HierarchicalModel<?> hierarchical ? hierarchical.root() : null;
	}

	static int headParts(Object model, ModelPart[] out) {
		if (model instanceof HumanoidModel<?> humanoid) {
			out[0] = humanoid.head;
			out[1] = humanoid.hat;
			return 2;
		}
		if (model instanceof AgeableListModel<?> ageable) {
			int n = 0;
			for (ModelPart part : ((AgeableListModelInvoker) ageable).flint$headParts()) {
				if (n < out.length) {
					out[n++] = part;
				}
			}
			if (n == 0) {
				// An animal whose head hangs off its body (an axolotl): the body's part named head.
				for (ModelPart part : ((AgeableListModelInvoker) ageable).flint$bodyParts()) {
					ModelPart head = EntityIcons.named(part, "head", 0);
					if (head != null) {
						out[0] = head;
						return 1;
					}
				}
			}
			return n;
		}
		if (model instanceof HierarchicalModel<?> hierarchical) {
			ModelPart head = EntityIcons.findHead(hierarchical.root());
			if (head != null) {
				out[0] = head;
				return 1;
			}
		}
		return 0;
	}

	static void bounds(Object cube, float[] out) {
		ModelPart.Cube c = (ModelPart.Cube) cube;
		out[0] = c.minX;
		out[1] = c.minY;
		out[2] = c.minZ;
		out[3] = c.maxX;
		out[4] = c.maxY;
		out[5] = c.maxZ;
	}

	private static void reflect() {
		if (reflected) {
			return;
		}
		reflected = true;
		try {
			for (Field f : ModelPart.Cube.class.getDeclaredFields()) {
				if (f.getType().isArray() && !f.getType().getComponentType().isPrimitive()) {
					f.setAccessible(true);
					cubePolygons = f;
				}
			}
			if (cubePolygons == null) {
				return;
			}
			Class<?> polygon = cubePolygons.getType().getComponentType();
			for (Field f : polygon.getDeclaredFields()) {
				if (f.getType().isArray()) {
					f.setAccessible(true);
					polygonVertices = f;
				} else if (f.getType() == Vector3f.class) {
					f.setAccessible(true);
					polygonNormal = f;
				}
			}
			if (polygonVertices == null) {
				return;
			}
			Class<?> vertex = polygonVertices.getType().getComponentType();
			for (Field f : vertex.getDeclaredFields()) {
				if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
					continue;
				}
				if (f.getType() == Vector3f.class) {
					f.setAccessible(true);
					vertexPos = f;
				} else if (f.getType() == float.class) {
					f.setAccessible(true);
					if (vertexU == null) {
						vertexU = f;
					} else if (vertexV == null) {
						vertexV = f;
					}
				}
			}
		} catch (RuntimeException e) {
			cubePolygons = null;
		}
	}

	static int polygons(Object cube, float[] out) {
		reflect();
		if (cubePolygons == null || polygonVertices == null || polygonNormal == null || vertexPos == null || vertexU == null || vertexV == null) {
			return 0;
		}
		try {
			Object[] polygons = (Object[]) cubePolygons.get(cube);
			int n = 0;
			for (Object p : polygons) {
				if (p == null || (n + 1) * POLYGON_FLOATS > out.length) {
					continue;
				}
				Object[] vertices = (Object[]) polygonVertices.get(p);
				if (vertices.length < 4) {
					continue;
				}
				Vector3f normal = (Vector3f) polygonNormal.get(p);
				int at = n * POLYGON_FLOATS;
				out[at] = normal.x();
				out[at + 1] = normal.y();
				out[at + 2] = normal.z();
				for (int v = 0; v < 4; v++) {
					Object vertex = vertices[v];
					Vector3f pos = (Vector3f) vertexPos.get(vertex);
					int vo = at + 3 + v * 5;
					out[vo] = pos.x();
					out[vo + 1] = pos.y();
					out[vo + 2] = pos.z();
					out[vo + 3] = vertexU.getFloat(vertex);
					out[vo + 4] = vertexV.getFloat(vertex);
				}
				n++;
			}
			return n;
		} catch (IllegalAccessException e) {
			return 0;
		}
	}

	static int woolColor(LivingEntity entity) {
		return entity instanceof Sheep sheep && !sheep.isSheared() && !sheep.isBaby() ? sheep.getColor().getTextureDiffuseColor() & 0xFFFFFF : -1;
	}

	/** 1.21.1 calls the wool "fur". */
	static ResourceLocation woolTexture() {
		return ResourceLocation.withDefaultNamespace("textures/entity/sheep/sheep_fur.png");
	}
}
