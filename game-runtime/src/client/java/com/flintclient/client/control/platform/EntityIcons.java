package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.mixin.feature.ModelPartAccessor;
import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The maps' mob icons, made as Xaero's minimap makes them: the mob's own head, its model part drawn face on.
 *
 * <p>How an icon is made (once per texture, then kept in one atlas): the mob's model and texture are taken from its
 * renderer; its head part is found as Xaero finds it (the part named head, or a horse's head_parts, or the whole model for
 * the mobs Xaero shows whole: slimes, magma cubes, fish, guardians, bees…; a squid shows whole too, at half size). That
 * part and every part under it are drawn in their resting pose, looking straight at the face, two pixels per model pixel
 * with flat light, nearest face winning pixel by pixel and see-through pixels left out (a slime's outer cube lets its face
 * through). Xaero's per-mob framing is kept (a horse tilted to show its long head, squids and guardians at half size, a
 * phantom from above…). A one-pixel black outline goes round the result, nothing else: no box behind it.
 *
 * <p>The icon is drawn at one GUI pixel per model pixel times the icon scale, the same for every mob and every zoom (so
 * a cow's head is 8 across, a chicken's 4, as on Xaero's map). A sheep wears its wool in its colour; a baby uses its
 * adult's head. A mob with no model part, or a texture that isn't a file, keeps its dot.
 *
 * <p>Render thread only. Building is budgeted (two icons a frame), the atlas holds 100 icons and starts over when full, and
 * a frame's drawing allocates nothing.
 */
public final class EntityIcons {
	private static final Identifier ATLAS_ID = Identifier.fromNamespaceAndPath("flintclient", "maps/entity_heads");
	private static final int ATLAS_W = 512;
	private static final int ATLAS_H = 512;
	private static final int CELL = 50;
	private static final int SLOTS = ATLAS_W / CELL * (ATLAS_H / CELL);
	/** Icon pixels per model pixel (Xaero's: 32 per block in a 64 icon). */
	private static final float RES = 2;
	private static final int BUILDS_PER_FRAME = 2;
	private static final int LOOKUPS_PER_FRAME = 4;
	/** Xaero's mobs shown whole rather than by their head. */
	private static final Set<String> WHOLE = Set.of("cod", "salmon", "tropical_fish", "bee", "guardian", "elder_guardian", "endermite",
		"magma_cube", "slime", "sulfur_cube", "pufferfish", "silverfish", "wither", "tadpole");

	/** The model each texture was last drawn with. */
	private static final Map<Identifier, Object> MODELS = new HashMap<>();
	/** Per entity id: the texture it was last drawn with, and when (game time). */
	private static final Int2ObjectMap<Seen> SEEN = new Int2ObjectOpenHashMap<>();
	/** Per texture (a sheep's: per texture and wool colour): its icon in the atlas. */
	private static final Map<Object, Head> HEADS = new HashMap<>();
	/** Baby textures to their adult's ("cow_temperate_baby.png" to "cow_temperate.png"), or to themselves when there is none. */
	private static final Map<Identifier, Identifier> ADULTS = new HashMap<>();
	private static final Map<Identifier, Int2ObjectMap<WoolKey>> WOOL_KEYS = new HashMap<>();

	private static @Nullable NativeImage atlasImage;
	private static @Nullable DynamicTexture atlas;
	private static int nextSlot;
	private static int builds;
	private static int lookups;
	private static long lastUse = Long.MIN_VALUE;
	private static long lastPurge;
	private static long now;

	// Scratch for building (render thread).
	private static final float[] POSE = new float[9];
	private static final float[] POLY = new float[6 * EntityHeads.POLYGON_FLOATS];
	private static final Vector3f V = new Vector3f();

	private EntityIcons() {
	}

	/** Where one icon sits in the atlas, and where the head's middle is in it. */
	private static final class Head {
		int slot = -1;
		int w;
		int h;
		float anchorX;
		float anchorY;
		/** 0: not built yet, 1: built, 2: can't be made (dot). */
		int state;
	}

	private static final class Seen {
		@Nullable Identifier texture;
		long time;
		long asked = Long.MIN_VALUE;
	}

	private record WoolKey(Identifier texture, int rgb) {
	}

	/** A face to draw: its four corners on the icon (x, y, depth) and texture coordinates, from which image, tinted. */
	private record Face(float[] xyz, float[] uv, NativeImage image, int tint) {
	}

	/** Whether the maps want heads (asked for in the last two seconds): the renderers note nothing otherwise. */
	public static boolean active() {
		return System.currentTimeMillis() - lastUse < 2000;
	}

	/** From a living thing's renderer as it draws: its texture and model this frame. */
	public static void seen(LivingEntity entity, @Nullable Identifier texture, @Nullable Object model) {
		if (texture == null || model == null) {
			return;
		}
		// A baby's model isn't kept: its head is its adult's (and 1.21.1 draws a baby with its adult's texture).
		if (!entity.isBaby() && MODELS.get(texture) != model) {
			// A new model for the texture (resources reloaded, or a renderer that picks its model per variant): rebuild.
			Object before = MODELS.put(texture, model);
			if (before != null) {
				forget(texture);
			}
		}
		Seen s = SEEN.get(entity.getId());
		if (s == null) {
			s = new Seen();
			SEEN.put(entity.getId(), s);
		}
		s.texture = texture;
		s.time = gameTime();
	}

	private static void forget(Identifier texture) {
		HEADS.remove(texture);
		Int2ObjectMap<WoolKey> wool = WOOL_KEYS.get(texture);
		if (wool != null) {
			for (WoolKey k : wool.values()) {
				HEADS.remove(k);
			}
		}
	}

	private static long gameTime() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? 0 : mc.level.getGameTime();
	}

	/** Starts a map's frame of heads: the build budget, and the clean-up of entities long gone. */
	public static void beginFrame() {
		lastUse = System.currentTimeMillis();
		builds = 0;
		lookups = 0;
		long t = gameTime();
		now = t;
		if (t - lastPurge > 200 || t < lastPurge) {
			lastPurge = t;
			SEEN.values().removeIf(s -> t - s.time > 600 || s.time > t);
		}
	}

	/**
	 * Draws {@code entity}'s icon with the head's middle at ({@code cx}, {@code cy}) in the pose's units (pixels on the maps),
	 * {@code pixel} units per model pixel (a GUI pixel times the icon scale), {@code alpha} opaque. False when it has none to
	 * draw (yet): draw its dot instead.
	 */
	public static boolean draw(GuiGraphicsExtractor g, Entity entity, float partialTick, float cx, float cy, float pixel, float alpha) {
		if (!(entity instanceof LivingEntity living)) {
			return false;
		}
		Head head = head(living, partialTick);
		if (head == null || head.state != 1 || atlas == null) {
			return false;
		}
		float k = pixel / RES;
		int w = Math.max(1, Math.round(head.w * k));
		int h = Math.max(1, Math.round(head.h * k));
		int x = Math.round(cx - head.anchorX * k);
		int y = Math.round(cy - head.anchorY * k);
		int u = head.slot % (ATLAS_W / CELL) * CELL;
		int v = head.slot / (ATLAS_W / CELL) * CELL;
		int a = Math.clamp(Math.round(alpha * 255), 0, 255);
		g.blit(RenderPipelines.GUI_TEXTURED, ATLAS_ID, x, y, u, v, w, h, head.w, head.h, ATLAS_W, ATLAS_H, a << 24 | 0xFFFFFF);
		return true;
	}

	private static @Nullable Head head(LivingEntity entity, float partialTick) {
		Seen s = SEEN.get(entity.getId());
		Identifier texture = s == null ? null : s.texture;
		// Not drawn lately (behind you, or never): asked of its renderer, a few a frame, at most every two seconds each.
		if ((s == null || now - s.time > 40) && lookups < LOOKUPS_PER_FRAME && (s == null || now - s.asked > 40 || now < s.asked)) {
			lookups++;
			Identifier asked = null;
			try {
				asked = EntityHeads.texture(entity, partialTick);
			} catch (RuntimeException | LinkageError e) {
				// A renderer that can't be asked outside drawing: the dot.
			}
			if (s == null) {
				s = new Seen();
				s.time = Long.MIN_VALUE / 2;
				SEEN.put(entity.getId(), s);
			}
			s.asked = now;
			if (asked != null) {
				s.texture = asked;
				texture = asked;
			}
		}
		if (texture == null) {
			return null;
		}
		texture = adult(texture);
		Object model = MODELS.get(texture);
		if (model == null) {
			// Not drawn yet this session (it was out of sight): the model its renderer draws it with, asked up front, so the
			// icon shows at once instead of a dot. A baby's isn't kept (its head is its adult's, which may differ).
			try {
				model = EntityHeads.model(entity);
			} catch (RuntimeException | LinkageError e) {
				model = null;
			}
			if (model == null) {
				return null;
			}
			if (!entity.isBaby()) {
				MODELS.put(texture, model);
			}
		}
		int wool = EntityHeads.woolColor(entity);
		Object key = wool < 0 ? texture : woolKey(texture, wool);
		Head head = HEADS.get(key);
		if (head == null) {
			head = new Head();
			HEADS.put(key, head);
		}
		if (head.state == 0 && builds < BUILDS_PER_FRAME) {
			builds++;
			build(head, texture, model, wool, BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath());
		}
		return head;
	}

	private static WoolKey woolKey(Identifier texture, int rgb) {
		// By hand (no lambda or boxed colour per call): the same few keys every frame.
		Int2ObjectMap<WoolKey> keys = WOOL_KEYS.get(texture);
		if (keys == null) {
			keys = new Int2ObjectOpenHashMap<>();
			WOOL_KEYS.put(texture, keys);
		}
		WoolKey key = keys.get(rgb);
		if (key == null) {
			key = new WoolKey(texture, rgb);
			keys.put(rgb, key);
		}
		return key;
	}

	/** A baby's texture to its adult's of the same variant when the game has one (babies have their own models on 26.x). */
	private static Identifier adult(Identifier texture) {
		String path = texture.getPath();
		if (!path.contains("_baby")) {
			return texture;
		}
		Identifier known = ADULTS.get(texture);
		if (known != null) {
			return known;
		}
		Identifier adult = Identifier.fromNamespaceAndPath(texture.getNamespace(), path.replace("_baby", ""));
		Identifier result = Minecraft.getInstance().getResourceManager().getResource(adult).isPresent() ? adult : texture;
		ADULTS.put(texture, result);
		return result;
	}

	// ---------- building an icon ----------

	/**
	 * Xaero's framing per mob: its extra turn about x and y (degrees) and its scale (squids and guardians half size, a horse
	 * tilted to show its long head, a phantom from above…).
	 */
	private static void framing(String type, float[] out) {
		out[0] = 0;
		out[1] = 0;
		out[2] = 1;
		switch (type) {
			case "cod", "salmon", "nautilus" -> {
				out[1] = 90;
				out[2] = 0.5F;
			}
			case "tropical_fish" -> out[1] = 90;
			case "horse", "donkey", "mule", "skeleton_horse", "zombie_horse" -> {
				out[0] = 65;
				out[2] = 0.7F;
			}
			case "hoglin", "zoglin" -> {
				out[0] = 45;
				out[2] = 0.5F;
			}
			case "phantom" -> {
				out[0] = 90;
				out[2] = 0.3F;
			}
			case "dolphin", "goat", "panda", "warden" -> out[2] = 0.7F;
			case "bat", "guardian", "elder_guardian", "squid", "glow_squid", "llama", "trader_llama", "camel", "sniffer", "ghast", "happy_ghast",
				"ravager", "strider", "ender_dragon", "sulfur_cube" -> out[2] = 0.5F;
			case "wither" -> out[2] = 0.35F;
			default -> {
			}
		}
	}

	private static void build(Head head, Identifier texture, Object model, int wool, String type) {
		head.state = 2;
		NativeImage woolImage = null;
		try (NativeImage image = read(texture)) {
			if (image == null) {
				return;
			}
			List<ModelPart> parts = mainParts(model, WHOLE.contains(type));
			if (parts.isEmpty()) {
				return;
			}
			float[] frame = new float[3];
			framing(type, frame);
			// The view: Xaero's extra turn and scale, then the head centred (the middle of its biggest box, as Xaero frames it).
			Matrix4f view = new Matrix4f().scale(frame[2] * RES).rotateX((float) Math.toRadians(frame[0])).rotateY((float) Math.toRadians(frame[1]));
			List<Face> faces = new ArrayList<>();
			for (ModelPart part : parts) {
				EntityHeads.pose(part, POSE);
				Matrix4f m = transform(new Matrix4f(view), POSE);
				collect(part, m, image, 0xFFFFFF, faces, 0);
			}
			if (type.equals("slime")) {
				// Its see-through outer cube is a layer of its own in game (same sheet: 8 × 8 × 8 at 0, 0): its face goes over.
				float[] front = {4, 16, -4, 16 / 64F, 8 / 32F, -4, 16, -4, 8 / 64F, 8 / 32F, -4, 24, -4, 8 / 64F, 16 / 32F, 4, 24, -4, 16 / 64F,
					16 / 32F};
				addFace(0, 0, -1, front, view, image, 0xFFFFFF, faces);
			}
			if (wool >= 0 && !parts.isEmpty()) {
				woolImage = read(EntityHeads.woolTexture());
				if (woolImage != null) {
					woolHead(parts.getFirst(), view, woolImage, wool, faces);
				}
			}
			// Framed on the middle of what shows.
			float[] middle = {Float.NaN, Float.NaN};
			int[] pixels = raster(faces, middle);
			if (pixels != null) {
				place(head, pixels);
				head.anchorX = middle[0];
				head.anchorY = middle[1];
			}
		} catch (RuntimeException | LinkageError | java.io.IOException e) {
			FlintClient.LOGGER.debug("Flint: no map icon for {}", texture, e);
			head.state = 2;
		} finally {
			if (woolImage != null) {
				woolImage.close();
			}
		}
	}

	/**
	 * The parts an icon shows, Xaero's way: the whole model for the mobs it shows whole; else a horse's head_parts, the
	 * part named head, or a body's head; else the whole model (a squid).
	 */
	private static List<ModelPart> mainParts(Object model, boolean whole) {
		List<ModelPart> out = new ArrayList<>();
		ModelPart root = EntityHeads.root(model);
		if (root != null) {
			if (!whole) {
				for (String name : new String[] {"head_parts", "head"}) {
					ModelPart found = named(root, name, 0);
					if (found != null) {
						out.add(found);
						return out;
					}
				}
			}
			out.addAll(((ModelPartAccessor) (Object) root).flint$children().values());
			return out;
		}
		// 1.21.1's quadruped and humanoid models have no root: their head parts (a humanoid's head, then its hat).
		ModelPart[] parts = new ModelPart[4];
		int n = EntityHeads.headParts(model, parts);
		for (int i = 0; i < n; i++) {
			if (parts[i] != null) {
				out.add(parts[i]);
			}
		}
		return out;
	}

	/** {@code m} times a part's pose (Minecraft's order: move, turn z-y-x, scale), in model pixels. */
	private static Matrix4f transform(Matrix4f m, float[] pose) {
		m.translate(pose[0], pose[1], pose[2]);
		if (pose[3] != 0 || pose[4] != 0 || pose[5] != 0) {
			m.rotateZYX(pose[5], pose[4], pose[3]);
		}
		if (pose[6] != 1 || pose[7] != 1 || pose[8] != 1) {
			m.scale(pose[6], pose[7], pose[8]);
		}
		return m;
	}

	/** The faces of a part and the parts under it (shown ones only) that look at the viewer, into {@code out}. */
	private static void collect(ModelPart part, Matrix4f m, NativeImage image, int tint, List<Face> out, int depth) {
		ModelPartAccessor access = (ModelPartAccessor) (Object) part;
		for (Object cube : access.flint$cubes()) {
			int n = EntityHeads.polygons(cube, POLY);
			for (int p = 0; p < n; p++) {
				Face f = face(POLY, p * EntityHeads.POLYGON_FLOATS, m, image, tint);
				if (f != null) {
					out.add(f);
				}
			}
		}
		if (depth > 6) {
			return;
		}
		for (ModelPart child : access.flint$children().values()) {
			if (!child.visible || child.skipDraw) {
				continue;
			}
			EntityHeads.pose(child, POSE);
			collect(child, transform(new Matrix4f(m), POSE), image, tint, out, depth + 1);
		}
	}

	/** One polygon (normal, then four corners of x, y, z, u, v) turned into the view; null when it faces away. */
	private static @Nullable Face face(float[] poly, int at, Matrix4f m, NativeImage image, int tint) {
		// Facing the viewer: the normal, turned, points toward -z (a model's front is -z, as Minecraft draws mobs facing you).
		m.transformDirection(V.set(poly[at], poly[at + 1], poly[at + 2]));
		if (V.z > -1e-3F) {
			return null;
		}
		float[] xyz = new float[12];
		float[] uv = new float[8];
		for (int v = 0; v < 4; v++) {
			int vo = at + 3 + v * 5;
			m.transformPosition(V.set(poly[vo], poly[vo + 1], poly[vo + 2]));
			xyz[3 * v] = V.x;
			xyz[3 * v + 1] = V.y;
			xyz[3 * v + 2] = V.z;
			uv[2 * v] = poly[vo + 3];
			uv[2 * v + 1] = poly[vo + 4];
		}
		return new Face(xyz, uv, image, tint);
	}

	/** A sheep's wool round its head: the wool sheet's head box (6 × 6 × 6 at 0, 0), a little bigger, in the wool's colour. */
	private static void woolHead(ModelPart head, Matrix4f view, NativeImage wool, int rgb, List<Face> out) {
		float[] box = new float[6];
		List<?> cubes = ((ModelPartAccessor) (Object) head).flint$cubes();
		if (cubes.isEmpty()) {
			return;
		}
		EntityHeads.bounds(cubes.getFirst(), box);
		EntityHeads.pose(head, POSE);
		Matrix4f m = transform(new Matrix4f(view), POSE);
		// The fur's head box sits two pixels back from the face (the face shows in front of it), grown by 0.6 all round.
		float g = 0.6F;
		float x0 = box[0] - g;
		float y0 = box[1] - g;
		float z0 = box[2] + 2 - g;
		float x1 = box[3] + g;
		float y1 = box[4] + g;
		// The front (-z): u 6..12, v 6..12 on 64 × 32; the top (-y): u 6..12, v 0..6 (its back row first).
		float[] front = {x1, y0, z0, 12 / 64F, 6 / 32F, x0, y0, z0, 6 / 64F, 6 / 32F, x0, y1, z0, 6 / 64F, 12 / 32F, x1, y1, z0, 12 / 64F, 12 / 32F};
		addFace(0, 0, -1, front, m, wool, rgb, out);
		float z1 = box[5] + g;
		float[] top = {x1, y0, z1, 12 / 64F, 0, x0, y0, z1, 6 / 64F, 0, x0, y0, z0, 6 / 64F, 6 / 32F, x1, y0, z0, 12 / 64F, 6 / 32F};
		addFace(0, -1, 0, top, m, wool, rgb, out);
	}

	private static void addFace(float nx, float ny, float nz, float[] corners, Matrix4f m, NativeImage image, int tint, List<Face> out) {
		float[] poly = new float[EntityHeads.POLYGON_FLOATS];
		poly[0] = nx;
		poly[1] = ny;
		poly[2] = nz;
		System.arraycopy(corners, 0, poly, 3, 20);
		Face f = face(poly, 0, m, image, tint);
		if (f != null) {
			out.add(f);
		}
	}

	/**
	 * The faces drawn into a picture ({@code [w, h, pixels...]}): cropped to what shows, the nearest opaque face winning,
	 * see-through texels blended over what's behind, and a one-pixel black outline. {@code middle} becomes the head's middle
	 * in the picture.
	 */
	private static int @Nullable [] raster(List<Face> faces, float[] middle) {
		if (faces.isEmpty()) {
			return null;
		}
		float minX = Float.MAX_VALUE;
		float minY = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE;
		float maxY = -Float.MAX_VALUE;
		for (Face f : faces) {
			for (int v = 0; v < 4; v++) {
				minX = Math.min(minX, f.xyz[3 * v]);
				maxX = Math.max(maxX, f.xyz[3 * v]);
				minY = Math.min(minY, f.xyz[3 * v + 1]);
				maxY = Math.max(maxY, f.xyz[3 * v + 1]);
			}
		}
		float shrink = 1;
		if (maxX - minX + 2 > CELL || maxY - minY + 2 > CELL) {
			// Too big for a cell: drawn smaller, about the head's middle.
			shrink = (CELL - 2) / Math.max(maxX - minX, maxY - minY);
		}
		int x0 = (int) Math.floor(minX * shrink);
		int y0 = (int) Math.floor(minY * shrink);
		int w = Math.max(1, (int) Math.ceil(maxX * shrink) - x0) + 2;
		int h = Math.max(1, (int) Math.ceil(maxY * shrink) - y0) + 2;
		w = Math.min(w, CELL);
		h = Math.min(h, CELL);
		int[] color = new int[w * h];
		float[] depth = new float[w * h];
		Arrays.fill(depth, Float.MAX_VALUE);
		int[] glass = new int[w * h];
		float[] glassDepth = new float[w * h];
		Arrays.fill(glassDepth, Float.MAX_VALUE);
		for (Face f : faces) {
			for (int tri = 0; tri < 2; tri++) {
				int a = 0;
				int b = tri == 0 ? 1 : 2;
				int c = tri == 0 ? 2 : 3;
				float ax = f.xyz[3 * a] * shrink - x0 + 1;
				float ay = f.xyz[3 * a + 1] * shrink - y0 + 1;
				float bx = f.xyz[3 * b] * shrink - x0 + 1;
				float by = f.xyz[3 * b + 1] * shrink - y0 + 1;
				float cx = f.xyz[3 * c] * shrink - x0 + 1;
				float cy = f.xyz[3 * c + 1] * shrink - y0 + 1;
				float area = (bx - ax) * (cy - ay) - (cx - ax) * (by - ay);
				if (Math.abs(area) < 1e-6F) {
					continue;
				}
				int px0 = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cx))));
				int px1 = Math.min(w - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx))));
				int py0 = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, cy))));
				int py1 = Math.min(h - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cy))));
				for (int py = py0; py <= py1; py++) {
					for (int px = px0; px <= px1; px++) {
						float x = px + 0.5F;
						float y = py + 0.5F;
						float wa = ((bx - x) * (cy - y) - (cx - x) * (by - y)) / area;
						float wb = ((cx - x) * (ay - y) - (ax - x) * (cy - y)) / area;
						float wc = 1 - wa - wb;
						if (wa < -1e-4F || wb < -1e-4F || wc < -1e-4F) {
							continue;
						}
						float z = wa * f.xyz[3 * a + 2] + wb * f.xyz[3 * b + 2] + wc * f.xyz[3 * c + 2];
						int at = py * w + px;
						if (z >= depth[at]) {
							continue;
						}
						float u = wa * f.uv[2 * a] + wb * f.uv[2 * b] + wc * f.uv[2 * c];
						float v = wa * f.uv[2 * a + 1] + wb * f.uv[2 * b + 1] + wc * f.uv[2 * c + 1];
						int argb = texel(f.image, u, v);
						int alpha = argb >>> 24;
						if (alpha < 13) {
							continue;
						}
						if (f.tint != 0xFFFFFF) {
							argb = tint(argb, f.tint);
						}
						if (alpha >= 250) {
							color[at] = 0xFF000000 | argb & 0xFFFFFF;
							depth[at] = z;
						} else if (z < glassDepth[at]) {
							glass[at] = argb;
							glassDepth[at] = z;
						}
					}
				}
			}
		}
		for (int i = 0; i < color.length; i++) {
			if (glass[i] != 0 && glassDepth[i] < depth[i]) {
				color[i] = blend(color[i], glass[i]);
			}
		}
		if (Float.isNaN(middle[0])) {
			middle[0] = w / 2.0F;
			middle[1] = h / 2.0F;
		} else {
			middle[0] = middle[0] * shrink - x0 + 1;
			middle[1] = middle[1] * shrink - y0 + 1;
		}
		int[] out = new int[2 + w * h];
		out[0] = w;
		out[1] = h;
		System.arraycopy(color, 0, out, 2, color.length);
		outline(out, w, h);
		return out;
	}

	private static int texel(NativeImage image, float u, float v) {
		int iw = image.getWidth();
		int ih = image.getHeight();
		int x = Math.clamp((int) Math.floor(u * iw), 0, iw - 1);
		int y = Math.clamp((int) Math.floor(v * ih), 0, ih - 1);
		return image.getPixel(x, y);
	}

	/** A see-through texel over what's under it; over nothing, a little more solid than it is (it's all there is). */
	private static int blend(int under, int top) {
		int a = top >>> 24;
		if (under >>> 24 == 0) {
			return Math.min(255, a * 3 / 2) << 24 | top & 0xFFFFFF;
		}
		float t = a / 255.0F;
		int r = Math.round((under >> 16 & 255) * (1 - t) + (top >> 16 & 255) * t);
		int g = Math.round((under >> 8 & 255) * (1 - t) + (top >> 8 & 255) * t);
		int b = Math.round((under & 255) * (1 - t) + (top & 255) * t);
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	private static @Nullable NativeImage read(@Nullable Identifier texture) throws java.io.IOException {
		if (texture == null) {
			return null;
		}
		Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(texture);
		if (resource.isEmpty()) {
			return null;
		}
		try (InputStream in = resource.get().open()) {
			return NativeImage.read(in);
		}
	}

	/** The head part to look in from a model's root: one named "head" anywhere, else a body, else the whole model. */
	public static @Nullable ModelPart findHead(@Nullable ModelPart root) {
		if (root == null) {
			return null;
		}
		for (String name : new String[] {"head", "real_head", "body", "cube"}) {
			ModelPart found = named(root, name, 0);
			if (found != null) {
				return found;
			}
		}
		return root;
	}

	/** The part named {@code name} under {@code part} (any depth), or null. */
	static @Nullable ModelPart named(ModelPart part, String name, int depth) {
		Map<String, ModelPart> children = ((ModelPartAccessor) (Object) part).flint$children();
		ModelPart direct = children.get(name);
		if (direct != null) {
			return direct;
		}
		if (depth > 5) {
			return null;
		}
		for (ModelPart child : children.values()) {
			ModelPart found = named(child, name, depth + 1);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	private static int tint(int argb, int rgb) {
		int r = (argb >> 16 & 255) * (rgb >> 16 & 255) / 255;
		int g = (argb >> 8 & 255) * (rgb >> 8 & 255) / 255;
		int b = (argb & 255) * (rgb & 255) / 255;
		return argb & 0xFF000000 | r << 16 | g << 8 | b;
	}

	/** Xaero's outline: every clear pixel next to (or diagonal to) the icon goes black. */
	private static void outline(int[] out, int w, int h) {
		boolean[] solid = new boolean[w * h];
		for (int i = 0; i < w * h; i++) {
			solid[i] = out[2 + i] >>> 24 != 0;
		}
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if (solid[y * w + x]) {
					continue;
				}
				boolean edge = false;
				for (int dy = -1; dy <= 1 && !edge; dy++) {
					for (int dx = -1; dx <= 1; dx++) {
						int nx = x + dx;
						int ny = y + dy;
						if (nx >= 0 && ny >= 0 && nx < w && ny < h && solid[ny * w + nx]) {
							edge = true;
							break;
						}
					}
				}
				if (edge) {
					out[2 + y * w + x] = 0xFF000000;
				}
			}
		}
	}

	/** Puts a picture in the next free cell of the atlas (all cells cleared and rebuilt when it's full). */
	private static void place(Head head, int[] pixels) {
		Minecraft mc = Minecraft.getInstance();
		if (atlas == null) {
			atlasImage = new NativeImage(ATLAS_W, ATLAS_H, true);
			atlas = new DynamicTexture(ATLAS_ID::toString, atlasImage);
			mc.getTextureManager().register(ATLAS_ID, atlas);
		}
		if (nextSlot >= SLOTS) {
			// Full: every icon starts over (they rebuild a couple a frame as they're drawn).
			nextSlot = 0;
			for (Head other : HEADS.values()) {
				if (other.state == 1) {
					other.state = 0;
					other.slot = -1;
				}
			}
		}
		int slot = nextSlot++;
		int u = slot % (ATLAS_W / CELL) * CELL;
		int v = slot / (ATLAS_W / CELL) * CELL;
		int w = pixels[0];
		int h = pixels[1];
		NativeImage img = atlasImage;
		for (int y = 0; y < CELL; y++) {
			for (int x = 0; x < CELL; x++) {
				img.setPixel(u + x, v + y, x < w && y < h ? pixels[2 + y * w + x] : 0);
			}
		}
		atlas.upload();
		head.slot = slot;
		head.w = w;
		head.h = h;
		head.state = 1;
	}
}
