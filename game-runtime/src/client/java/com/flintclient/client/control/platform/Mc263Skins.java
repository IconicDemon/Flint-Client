package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.feature.SkinVoxels;
import com.flintclient.client.control.feature.SkinVoxels.Layer;
import com.flintclient.client.control.feature.SkinVoxels.Voxel;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.VisualMods.Skins3d;
import com.flintclient.client.mixin.feature.ModelPartAccessor;
import com.flintclient.client.mixin.feature.TextureManagerAccessor;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.PlayerModelType;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 26.3's side of 3D Skins: the player model's outer-layer parts get, right before each player is drawn, the
 * voxels built from that player's skin ({@link SkinVoxels}), or Minecraft's own flat boxes back. Minecraft draws a model
 * straight after setting it up, so each player is drawn with their own voxels. Voxels are built once per skin and
 * settings, from pixels Minecraft already has (a downloaded skin) or read once in the background (a built-in one); until
 * then the flat layers show. Render thread; fault-isolated (a failure leaves every skin flat).
 */
public final class Mc263Skins {
	private static final int MODULE = Mods.index(Skins3d.ID);
	private static final HookGuard GUARD = new HookGuard("3d skins",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final int MAX_SKINS = 48;
	private static final float UNIT = 1;

	/** The parts' own flat boxes, kept the first time each part is seen. */
	private static final Map<ModelPart, List<ModelPart.Cube>> ORIGINAL = new IdentityHashMap<>();
	/** Built voxels per skin (and model type), least recently used first. */
	private static final Map<String, Mesh> MESHES = new LinkedHashMap<>(16, 0.75F, true);
	/** Pixels of built-in skins, read in the background (null while reading or if it failed). */
	private static final Map<Identifier, Optional<boolean[]>> READ = new ConcurrentHashMap<>();
	private static ModuleState builtFor;
	private static int builtRevision = -1;

	private Mc263Skins() {
	}

	/** One skin's voxels as Minecraft boxes, per layer; first-person sleeves at their own size. */
	private record Mesh(List<ModelPart.Cube> hat, List<ModelPart.Cube> jacket, List<ModelPart.Cube> leftSleeve, List<ModelPart.Cube> rightSleeve,
		List<ModelPart.Cube> leftPants, List<ModelPart.Cube> rightPants, List<ModelPart.Cube> leftSleeveHand, List<ModelPart.Cube> rightSleeveHand) {
	}

	private static void restore(ModelPart part) {
		List<ModelPart.Cube> original = ORIGINAL.get(part);
		if (original != null) {
			((ModelPartAccessor) (Object) part).flint$setCubes(original);
		}
	}

	private static void set(ModelPart part, @Nullable List<ModelPart.Cube> voxels, boolean on) {
		ModelPartAccessor access = (ModelPartAccessor) (Object) part;
		ORIGINAL.computeIfAbsent(part, p -> access.flint$cubes());
		access.flint$setCubes(on && voxels != null ? voxels : ORIGINAL.get(part));
	}

	/** From the end of {@code PlayerModel.setupAnim}: this player's voxels on the outer layers, or the flat boxes. */
	public static void apply(PlayerModel model, AvatarRenderState state) {
		if (!GUARD.on()) {
			return;
		}
		try {
			Mesh mesh = Mods.on(MODULE) ? meshFor(state) : null;
			ModuleState settings = Mods.state(MODULE);
			set(model.hat, mesh == null ? null : mesh.hat(), settings.bool(Skins3d.HAT));
			set(model.jacket, mesh == null ? null : mesh.jacket(), settings.bool(Skins3d.JACKET));
			set(model.leftSleeve, mesh == null ? null : mesh.leftSleeve(), settings.bool(Skins3d.LEFT_SLEEVE));
			set(model.rightSleeve, mesh == null ? null : mesh.rightSleeve(), settings.bool(Skins3d.RIGHT_SLEEVE));
			set(model.leftPants, mesh == null ? null : mesh.leftPants(), settings.bool(Skins3d.LEFT_PANTS));
			set(model.rightPants, mesh == null ? null : mesh.rightPants(), settings.bool(Skins3d.RIGHT_PANTS));
		} catch (RuntimeException e) {
			GUARD.fail(e);
			for (ModelPart part : List.of(model.hat, model.jacket, model.leftSleeve, model.rightSleeve, model.leftPants, model.rightPants)) {
				restore(part);
			}
		}
	}

	/** From the first-person hand: your sleeves at the first-person voxel size. */
	public static void firstPerson(PlayerModel model, Identifier skin) {
		if (!GUARD.on()) {
			return;
		}
		try {
			LocalPlayer player = Minecraft.getInstance().player;
			Mesh mesh = Mods.on(MODULE) && player != null ? mesh(skin, player.getSkin().model() == PlayerModelType.SLIM) : null;
			ModuleState settings = Mods.state(MODULE);
			set(model.leftSleeve, mesh == null ? null : mesh.leftSleeveHand(), settings.bool(Skins3d.LEFT_SLEEVE));
			set(model.rightSleeve, mesh == null ? null : mesh.rightSleeveHand(), settings.bool(Skins3d.RIGHT_SLEEVE));
		} catch (RuntimeException e) {
			GUARD.fail(e);
			restore(model.leftSleeve);
			restore(model.rightSleeve);
		}
	}

	private static @Nullable Mesh meshFor(AvatarRenderState state) {
		ModuleState settings = Mods.state(MODULE);
		LocalPlayer self = Minecraft.getInstance().player;
		boolean mine = self != null && state.id == self.getId();
		if (!mine && !settings.bool(Skins3d.OTHERS) || state.isInvisible) {
			return null;
		}
		double far = settings.integer(Skins3d.DISTANCE);
		if (state.distanceToCameraSq > far * far) {
			return null;
		}
		return mesh(state.skin.body().texturePath(), state.skin.model() == PlayerModelType.SLIM);
	}

	private static @Nullable Mesh mesh(Identifier texture, boolean slim) {
		ModuleState settings = Mods.state(MODULE);
		if (settings != builtFor || settings.revision() != builtRevision) {
			builtFor = settings;
			builtRevision = settings.revision();
			MESHES.clear();
		}
		String key = texture + (slim ? "#slim" : "#wide");
		Mesh mesh = MESHES.get(key);
		if (mesh != null) {
			return mesh;
		}
		boolean[] opaque = pixels(texture);
		if (opaque == null) {
			return null;
		}
		SkinVoxels.Opacity at = (x, y) -> x >= 0 && y >= 0 && x < 64 && y < 64 && opaque[y * 64 + x];
		float limbs = settings.integer(Skins3d.VOXEL) / 100F;
		float torso = settings.integer(Skins3d.TORSO) / 100F;
		float head = settings.integer(Skins3d.HEAD) / 100F;
		float hand = settings.integer(Skins3d.FIRST_PERSON) / 100F;
		Layer left = slim ? SkinVoxels.LEFT_SLEEVE_SLIM : SkinVoxels.LEFT_SLEEVE;
		Layer right = slim ? SkinVoxels.RIGHT_SLEEVE_SLIM : SkinVoxels.RIGHT_SLEEVE;
		mesh = new Mesh(cubes(SkinVoxels.build(SkinVoxels.HAT, at, head, UNIT)), cubes(SkinVoxels.build(SkinVoxels.JACKET, at, torso, UNIT)),
			cubes(SkinVoxels.build(left, at, limbs, UNIT)), cubes(SkinVoxels.build(right, at, limbs, UNIT)),
			cubes(SkinVoxels.build(SkinVoxels.LEFT_PANTS, at, limbs, UNIT)), cubes(SkinVoxels.build(SkinVoxels.RIGHT_PANTS, at, limbs, UNIT)),
			cubes(SkinVoxels.build(left, at, hand, UNIT)), cubes(SkinVoxels.build(right, at, hand, UNIT)));
		MESHES.put(key, mesh);
		while (MESHES.size() > MAX_SKINS) {
			MESHES.remove(MESHES.keySet().iterator().next());
		}
		builtCount++;
		return mesh;
	}

	private static int builtCount;

	/** How many skins have been built into voxels this session (for the dev tour). */
	public static int built() {
		return builtCount;
	}

	/**
	 * The skin's outer-layer opacity (64x64, row by row), or null when not known yet: a downloaded skin's pixels are in its
	 * texture; a built-in one is read once in the background. A skin that isn't 64x64 (an old 64x32 one) has no outer layer.
	 */
	private static boolean @Nullable [] pixels(Identifier texture) {
		Optional<boolean[]> read = READ.get(texture);
		if (read != null) {
			return read.orElse(null);
		}
		AbstractTexture loaded = ((TextureManagerAccessor) Minecraft.getInstance().getTextureManager()).flint$byPath().get(texture);
		if (loaded instanceof DynamicTexture dynamic) {
			NativeImage image = dynamic.getPixels();
			boolean[] opaque = image == null ? null : opacity(image);
			READ.put(texture, Optional.ofNullable(opaque));
			return opaque;
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
				boolean[] opaque = opacity(image);
				if (opaque != null) {
					READ.put(texture, Optional.of(opaque));
				}
			} catch (Exception e) {
				FlintClient.LOGGER.debug("Flint: couldn't read skin {} for 3D Skins", texture, e);
			}
		}, Util.backgroundExecutor());
		return null;
	}

	private static boolean @Nullable [] opacity(NativeImage image) {
		if (image.getWidth() != 64 || image.getHeight() != 64) {
			return null;
		}
		boolean[] opaque = new boolean[64 * 64];
		for (int y = 0; y < 64; y++) {
			for (int x = 0; x < 64; x++) {
				opaque[y * 64 + x] = (image.getPixel(x, y) >>> 24) >= SkinVoxels.ALPHA_THRESHOLD;
			}
		}
		return opaque;
	}

	/** Minecraft boxes for voxels: each box's faces all show its one texel; hidden faces aren't made. */
	private static List<ModelPart.Cube> cubes(List<Voxel> voxels) {
		List<ModelPart.Cube> out = new ArrayList<>(voxels.size());
		for (Voxel v : voxels) {
			EnumSet<Direction> faces = EnumSet.noneOf(Direction.class);
			for (SkinVoxels.Face face : SkinVoxels.Face.values()) {
				if (v.has(face)) {
					faces.add(Direction.valueOf(face.name()));
				}
			}
			if (faces.isEmpty()) {
				continue;
			}
			ModelPart.Cube cube = new ModelPart.Cube(0, 0, v.minX(), v.minY(), v.minZ(), v.maxX() - v.minX(), v.maxY() - v.minY(), v.maxZ() - v.minZ(),
				0, 0, 0, false, 64, 64, faces);
			float u = v.u() / 64F;
			float t = v.v() / 64F;
			for (int i = 0; i < cube.polygons.length; i++) {
				ModelPart.Polygon polygon = cube.polygons[i];
				ModelPart.Vertex[] remapped = new ModelPart.Vertex[polygon.vertices().length];
				for (int k = 0; k < remapped.length; k++) {
					remapped[k] = polygon.vertices()[k].remap(u, t);
				}
				cube.polygons[i] = new ModelPart.Polygon(remapped, polygon.normal());
			}
			out.add(cube);
		}
		return List.copyOf(out);
	}
}
