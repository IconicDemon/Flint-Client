package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.feature.SkinVoxels;
import com.flintclient.client.control.feature.SkinVoxels.Layer;
import com.flintclient.client.control.feature.SkinVoxels.Mesh;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.VisualMods.Skins3d;
import com.flintclient.client.mixin.feature.TextureManagerAccessor;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.io.InputStream;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.object.skull.SkullModel;
import net.minecraft.client.model.object.skull.SkullModelBase;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.PlayerModelType;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft's side of 3D Skins. Right before a player model is drawn (Minecraft sets a model up just before drawing it),
 * its outer-layer parts get that skin's voxel meshes ({@link SkinVoxels}), or none to keep Minecraft's flat boxes; the
 * parts then draw the mesh in place of their boxes ({@link CustomPart}, from {@code ModelPart.render}). The mesh is written
 * straight to the model's buffer, so it draws the same with Sodium (which replaces how boxes are drawn, not parts).
 *
 * <p>Only the skin model itself gets meshes: armour, the cape and the ears are drawn with player models too, whose
 * layer parts are empty. A player beyond the detail distance, an invisible one, and a small picture in a menu keep the
 * flat layers; a hat under a worn head or pumpkin stays flat so it doesn't poke through. Meshes are built once per skin
 * and settings, from pixels Minecraft already has (a downloaded skin) or read once in the background (a built-in one);
 * until then the flat layers show. Render thread; fault-isolated (a failure leaves every skin flat).
 */
public final class Mc263Skins {
	private static final int MODULE = Mods.index(Skins3d.ID);
	private static final HookGuard GUARD = new HookGuard("3d skins",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final int MAX_SKINS = 48;
	/** Below this many screen pixels to a skin pixel (a small picture in a menu) the side faces can't be seen. */
	private static final float GUI_FLAT_BELOW = 2.0F;
	/** Limbs and body are scaled a little in height too, so their ends don't sit flush with the body's. */
	private static final float HEIGHT_SCALE = 1.035F;

	/** Built meshes per skin, wide and slim models apart (no key made per player per frame), least recently used first. */
	private static final Map<Identifier, Meshes> MESHES = new LinkedHashMap<>(16, 0.75F, true);
	private static final Map<Identifier, Meshes> MESHES_SLIM = new LinkedHashMap<>(16, 0.75F, true);
	/** How many skins' pixels are kept for (re)building meshes: a crowded server's every skin would otherwise stay for good. */
	private static final int MAX_READ = 4 * MAX_SKINS;
	/**
	 * Skins' alpha, read once (empty while a built-in one is read in the background, or if it can't be), least recently used
	 * first and at most {@link #MAX_READ} (16 KB each).
	 */
	private static final Map<Identifier, Optional<int[]>> READ = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Identifier, Optional<int[]>> eldest) {
			return size() > MAX_READ;
		}
	});
	/**
	 * Meshes built in a row before the rest wait (flat layers meanwhile): joining a crowded lobby, or more skins in view than
	 * the cache holds, then costs a couple of builds a frame instead of every one at once.
	 */
	private static final int BUILDS_PER_WINDOW = 2;
	private static final long BUILD_WINDOW_NANOS = 8_000_000L;
	private static long buildWindowStart;
	private static int buildsInWindow;
	private static @Nullable ModuleState builtFor;
	private static int builtRevision = -1;
	/** Player heads' skin render types and their skins (every player head is drawn with one of these). */
	private static final Map<RenderType, Identifier> HEAD_SKINS = Collections.synchronizedMap(new WeakHashMap<>());
	/** A head model per skin with that skin's voxel hat, least recently used first. */
	private static final Map<Identifier, SkullModel> HEADS = new LinkedHashMap<>(16, 0.75F, true);
	private static final Set<SkullModelBase> OURS = Collections.newSetFromMap(new IdentityHashMap<>());
	private static final Vector3f AT = new Vector3f();
	private static final Vector3f POS = new Vector3f();
	private static final Vector3f[] NORMALS = {new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()};
	private static final Vector3fc[] FACE_NORMALS = {new Vector3f(0, -1, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, -1), new Vector3f(0, 0, 1),
		new Vector3f(-1, 0, 0), new Vector3f(1, 0, 0)};
	private static boolean inItem;
	/** While a menu picture of an entity is drawn: screen pixels to a skin pixel there (-1: not in one). */
	private static float guiTexel = -1;
	private static int builtCount;
	private static int drawnQuads;

	private Mc263Skins() {
	}

	/** One skin's meshes, per layer; first-person sleeves at their own size. */
	private record Meshes(Voxels hat, Voxels jacket, Voxels leftSleeve, Voxels rightSleeve, Voxels leftPants, Voxels rightPants, Voxels leftSleeveHand,
		Voxels rightSleeveHand, Voxels skullHat) {
		int quads() {
			return hat.mesh.quads() + jacket.mesh.quads() + leftSleeve.mesh.quads() + rightSleeve.mesh.quads() + leftPants.mesh.quads()
				+ rightPants.mesh.quads();
		}
	}

	/** A layer's mesh as a part drawing. */
	private record Voxels(Mesh mesh) implements PartDrawing {
		@Override
		public void draw(PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay, int color) {
			Mc263Skins.draw(mesh, pose, buffer, light, overlay, color);
		}
	}

	private static void set(ModelPart part, @Nullable Voxels voxels) {
		((CustomPart) (Object) part).flint$drawing(voxels != null && voxels.mesh.quads() > 0 ? voxels : null);
	}

	private static void clear(PlayerModel model) {
		set(model.hat, null);
		set(model.jacket, null);
		set(model.leftSleeve, null);
		set(model.rightSleeve, null);
		set(model.leftPants, null);
		set(model.rightPants, null);
	}

	/** Whether a player model draws a skin (armour, cape and ears models have empty layer parts). */
	private static boolean skinModel(PlayerModel model) {
		return !model.jacket.isEmpty();
	}

	/** From the end of {@code PlayerModel.setupAnim}: this player's meshes on the outer layers, or the flat boxes. */
	public static void apply(PlayerModel model, AvatarRenderState state) {
		if (!GUARD.on()) {
			return;
		}
		try {
			if (!skinModel(model)) {
				return;
			}
			Meshes mesh = Mods.on(MODULE) ? meshFor(state) : null;
			if (mesh == null) {
				clear(model);
				return;
			}
			ModuleState settings = Mods.state(MODULE);
			// A head or pumpkin worn over the hat: the flat hat stays under it.
			boolean headWorn = state.wornHeadType != null || !state.headItem.isEmpty();
			set(model.hat, settings.bool(Skins3d.HAT) && !headWorn ? mesh.hat() : null);
			set(model.jacket, settings.bool(Skins3d.JACKET) ? mesh.jacket() : null);
			set(model.leftSleeve, settings.bool(Skins3d.LEFT_SLEEVE) ? mesh.leftSleeve() : null);
			set(model.rightSleeve, settings.bool(Skins3d.RIGHT_SLEEVE) ? mesh.rightSleeve() : null);
			set(model.leftPants, settings.bool(Skins3d.LEFT_PANTS) ? mesh.leftPants() : null);
			set(model.rightPants, settings.bool(Skins3d.RIGHT_PANTS) ? mesh.rightPants() : null);
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			clear(model);
		}
	}

	/** From the first-person hand: your sleeves at the first-person voxel size. */
	public static void firstPerson(PlayerModel model, Identifier skin) {
		if (!GUARD.on()) {
			return;
		}
		try {
			LocalPlayer player = Minecraft.getInstance().player;
			Meshes mesh = Mods.on(MODULE) && player != null && skinModel(model) ? meshes(skin, player.getSkin().model() == PlayerModelType.SLIM) : null;
			ModuleState settings = Mods.state(MODULE);
			set(model.leftSleeve, mesh != null && settings.bool(Skins3d.LEFT_SLEEVE) ? mesh.leftSleeveHand() : null);
			set(model.rightSleeve, mesh != null && settings.bool(Skins3d.RIGHT_SLEEVE) ? mesh.rightSleeveHand() : null);
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			set(model.leftSleeve, null);
			set(model.rightSleeve, null);
		}
	}

	private static @Nullable Meshes meshFor(AvatarRenderState state) {
		ModuleState settings = Mods.state(MODULE);
		LocalPlayer self = Minecraft.getInstance().player;
		boolean mine = self != null && state.id == self.getId();
		if (!mine && !settings.bool(Skins3d.OTHERS) || state.isInvisible) {
			return null;
		}
		if (guiTexel >= 0) {
			// A picture in a menu: 3D only when its pixels are big enough for the sides to show.
			if (guiTexel < GUI_FLAT_BELOW) {
				return null;
			}
		} else {
			double far = settings.integer(Skins3d.DISTANCE);
			if (state.distanceToCameraSq > far * far) {
				return null;
			}
		}
		return meshes(state.skin.body().texturePath(), state.skin.model() == PlayerModelType.SLIM);
	}

	/**
	 * How far (pixels) this player's 3D jacket stands out of their back, so a cape can hang clear of it; 0 while the
	 * jacket is flat for them.
	 */
	public static float jacketDepth(AvatarRenderState state) {
		if (!GUARD.on() || !Mods.on(MODULE)) {
			return 0;
		}
		ModuleState settings = Mods.state(MODULE);
		if (!settings.bool(Skins3d.JACKET)) {
			return 0;
		}
		LocalPlayer self = Minecraft.getInstance().player;
		boolean mine = self != null && state.id == self.getId();
		if (!mine && !settings.bool(Skins3d.OTHERS) || state.isInvisible) {
			return 0;
		}
		double far = settings.integer(Skins3d.DISTANCE);
		if (guiTexel >= 0 ? guiTexel < GUI_FLAT_BELOW : state.distanceToCameraSq > far * far) {
			return 0;
		}
		return 2 * (Math.max(SkinVoxels.MIN_SCALE, settings.decimal(Skins3d.VOXEL)) - 1);
	}

	/** Whether a menu picture of an entity (the inventory, Flint's player model) is being drawn. */
	public static boolean inGuiPicture() {
		return guiTexel >= 0;
	}

	/** From Minecraft's menu entity pictures: their scale (screen pixels to a block) while one is drawn, or -1 after. */
	public static void guiPicture(float pixelsPerBlock) {
		guiTexel = pixelsPerBlock < 0 ? -1 : pixelsPerBlock / 16.0F;
	}

	/** From Minecraft's player-head skin render type: which skin it draws. */
	public static void headSkin(RenderType type, Identifier skin) {
		HEAD_SKINS.put(type, skin);
	}

	/** While a player head item is submitted (a slot, a hand): it gets its 3D hat whatever the detail distance. */
	public static void headItem(boolean item) {
		inItem = item;
	}

	/**
	 * Where Minecraft submits a skull: for a player head within the detail distance (or an item), a head model with its
	 * skin's voxel hat; null keeps Minecraft's (another skull, 3D Skins or 3D heads off, a skin not read yet).
	 */
	public static @Nullable SkullModelBase head(SkullModelBase model, RenderType type, PoseStack pose) {
		if (!GUARD.on() || OURS.contains(model) || !(model instanceof SkullModel) || !Mods.on(MODULE)) {
			return null;
		}
		try {
			ModuleState settings = Mods.state(MODULE);
			Identifier skin = HEAD_SKINS.get(type);
			// 3D Skin Layers: placed heads within the detail distance; head items (held, worn, in slots) when Skull items is on.
			if (skin == null || !settings.bool(Skins3d.HEADS) || (inItem ? !settings.bool(Skins3d.SKULL_ITEMS)
				: !near(pose, settings.integer(Skins3d.DISTANCE)))) {
				return null;
			}
			Meshes mesh = meshes(skin, false);
			if (mesh == null || mesh.skullHat().mesh.quads() == 0) {
				return null;
			}
			SkullModel head = HEADS.get(skin);
			if (head == null) {
				head = new SkullModel(SkullModel.createHumanoidHeadLayer().bakeRoot());
				set(head.root().getChild("head").getChild("hat"), mesh.skullHat());
				HEADS.put(skin, head);
				OURS.add(head);
				while (HEADS.size() > MAX_SKINS) {
					OURS.remove(HEADS.remove(HEADS.keySet().iterator().next()));
				}
			}
			return head;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return null;
		}
	}

	/** Whether a skull at {@code pose} (camera-relative in the world) is within {@code blocks} of the camera. */
	private static boolean near(PoseStack pose, int blocks) {
		pose.last().pose().getTranslation(AT);
		return AT.lengthSquared() <= (float) blocks * blocks;
	}

	private static @Nullable Meshes meshes(Identifier texture, boolean slim) {
		ModuleState settings = Mods.state(MODULE);
		if (settings != builtFor || settings.revision() != builtRevision) {
			builtFor = settings;
			builtRevision = settings.revision();
			MESHES.clear();
			MESHES_SLIM.clear();
			HEADS.clear();
			OURS.clear();
		}
		Map<Identifier, Meshes> meshes = slim ? MESHES_SLIM : MESHES;
		Meshes mesh = meshes.get(texture);
		if (mesh != null) {
			return mesh;
		}
		long now = System.nanoTime();
		if (now - buildWindowStart > BUILD_WINDOW_NANOS) {
			buildWindowStart = now;
			buildsInWindow = 0;
		}
		if (buildsInWindow >= BUILDS_PER_WINDOW) {
			return null;
		}
		int[] alpha = pixels(texture);
		if (alpha == null) {
			return null;
		}
		buildsInWindow++;
		SkinVoxels.Alpha at = (x, y) -> x >= 0 && y >= 0 && x < 64 && y < 64 ? alpha[y * 64 + x] : 0;
		float limbs = settings.decimal(Skins3d.VOXEL);
		float torso = settings.decimal(Skins3d.TORSO);
		float head = settings.decimal(Skins3d.HEAD);
		float hand = settings.decimal(Skins3d.FIRST_PERSON);
		float skull = settings.decimal(Skins3d.SKULL);
		Layer left = slim ? SkinVoxels.LEFT_SLEEVE_SLIM : SkinVoxels.LEFT_SLEEVE;
		Layer right = slim ? SkinVoxels.RIGHT_SLEEVE_SLIM : SkinVoxels.RIGHT_SLEEVE;
		mesh = new Meshes(new Voxels(SkinVoxels.build(SkinVoxels.HAT, at, head, head, head, 64, 64)),
			new Voxels(SkinVoxels.build(SkinVoxels.JACKET, at, torso, HEIGHT_SCALE, limbs, 64, 64)), limb(left, at, limbs), limb(right, at, limbs),
			limb(SkinVoxels.LEFT_PANTS, at, limbs), limb(SkinVoxels.RIGHT_PANTS, at, limbs), limb(left, at, hand), limb(right, at, hand),
			new Voxels(SkinVoxels.build(SkinVoxels.HAT, at, skull, skull, skull, 64, 64)));
		meshes.put(texture, mesh);
		while (meshes.size() > MAX_SKINS) {
			meshes.remove(meshes.keySet().iterator().next());
		}
		builtCount++;
		FlintClient.LOGGER.debug("Flint: 3D Skins built {} {} ({} quads)", texture, slim ? "slim" : "wide", mesh.quads());
		return mesh;
	}

	private static Voxels limb(Layer layer, SkinVoxels.Alpha alpha, float scale) {
		return new Voxels(SkinVoxels.build(layer, alpha, scale, HEIGHT_SCALE, scale, 64, 64));
	}

	/** How many skins have been built into meshes this session (for the dev tour). */
	public static int built() {
		return builtCount;
	}

	/** How many voxel quads were drawn since the last call (for the dev tour). */
	public static int drawnQuads() {
		int n = drawnQuads;
		drawnQuads = 0;
		return n;
	}

	/** The quads of the last skin built for {@code texture} ("wide" or "slim"), or -1 (for the dev tour). */
	public static int quads(String texture, String model) {
		Meshes mesh = (model.equals("slim") ? MESHES_SLIM : MESHES).get(Identifier.parse(texture));
		return mesh == null ? -1 : mesh.quads();
	}

	/**
	 * From a part with a mesh, in place of its boxes: the mesh into {@code buffer} under {@code pose}, lit and tinted as
	 * the boxes would be.
	 */
	public static void draw(Mesh mesh, PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay, int color) {
		Matrix4f matrix = pose.pose();
		for (int f = 0; f < 6; f++) {
			pose.transformNormal(FACE_NORMALS[f], NORMALS[f]);
		}
		float[] p = mesh.positions();
		float[] t = mesh.textures();
		int quads = mesh.quads();
		for (int q = 0; q < quads; q++) {
			Vector3f n = NORMALS[mesh.face(q)];
			float nx = n.x();
			float ny = n.y();
			float nz = n.z();
			int pi = q * 12;
			int ti = q * 8;
			for (int k = 0; k < 4; k++) {
				matrix.transformPosition(p[pi], p[pi + 1], p[pi + 2], POS);
				buffer.addVertex(POS.x(), POS.y(), POS.z(), color, t[ti], t[ti + 1], overlay, light, nx, ny, nz);
				pi += 3;
				ti += 2;
			}
		}
		drawnQuads += quads;
	}

	/**
	 * The skin's alpha (64x64, row by row), or null when not known yet: a downloaded skin's pixels are in its texture; a
	 * built-in one is read once in the background. A skin that isn't 64x64 (an old 64x32 one) has no outer layer.
	 */
	private static int @Nullable [] pixels(Identifier texture) {
		Optional<int[]> read = READ.get(texture);
		if (read != null) {
			return read.orElse(null);
		}
		AbstractTexture loaded = ((TextureManagerAccessor) Minecraft.getInstance().getTextureManager()).flint$byPath().get(texture);
		if (loaded instanceof DynamicTexture dynamic) {
			NativeImage image = dynamic.getPixels();
			int[] alpha = image == null ? null : alpha(image);
			READ.put(texture, Optional.ofNullable(alpha));
			return alpha;
		}
		if (loaded == null) {
			return null;
		}
		// A built-in skin: read its file off the render thread, once.
		READ.put(texture, Optional.empty());
		CompletableFuture.runAsync(() -> {
			Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(texture);
			if (resource.isEmpty()) {
				return;
			}
			try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
				int[] alpha = alpha(image);
				if (alpha != null) {
					READ.put(texture, Optional.of(alpha));
				}
			} catch (Exception e) {
				FlintClient.LOGGER.debug("Flint: couldn't read skin {} for 3D Skins", texture, e);
			}
		}, Util.backgroundExecutor());
		return null;
	}

	private static int @Nullable [] alpha(NativeImage image) {
		if (image.getWidth() != 64 || image.getHeight() != 64) {
			return null;
		}
		int[] alpha = new int[64 * 64];
		for (int y = 0; y < 64; y++) {
			for (int x = 0; x < 64; x++) {
				alpha[y * 64 + x] = image.getPixel(x, y) >>> 24;
			}
		}
		return alpha;
	}
}
