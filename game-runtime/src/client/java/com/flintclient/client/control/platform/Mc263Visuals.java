package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.UtilityMods;
import com.flintclient.client.control.model.mods.VisualMods;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Util;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 26.3's side of Overlay, Particle Changer, Block Outline, Shulker Preview and the screenshot preview. Each
 * path is fault-isolated; the per-frame ones allocate nothing in steady state. Render thread unless said otherwise.
 */
public final class Mc263Visuals {
	private static final int OVERLAY = Mods.index(VisualMods.Overlay.ID);
	private static final int PARTICLES = Mods.index(VisualMods.ParticleChanger.ID);
	private static final int OUTLINE = Mods.index(VisualMods.BlockOutline.ID);
	private static final int SHULKER = Mods.index(UtilityMods.ShulkerPreview.ID);
	private static final int SCREENSHOTS = Mods.index(UtilityMods.Screenshots.ID);
	private static final HookGuard GUARD = new HookGuard("visual modules", (path, error) -> FlintClient.LOGGER.warn(
		"Flint: the {} hook failed; it's off for this session", path, error));
	private static final Identifier SHOT = Identifier.fromNamespaceAndPath(FlintClient.MOD_ID, "screenshot_preview");
	private static final float LOW_FIRE = 0.3F;

	private Mc263Visuals() {
	}

	// ---------- overlay ----------

	/** The fire overlay: 0 as vanilla, 1 lowered, 2 hidden. */
	public static int fire() {
		return GUARD.on() && Mods.on(OVERLAY) ? Mods.state(OVERLAY).option(VisualMods.Overlay.FIRE) : 0;
	}

	public static float lowFireOffset() {
		return LOW_FIRE;
	}

	public static boolean hideUnderwater() {
		return GUARD.on() && Mods.on(OVERLAY) && Mods.state(OVERLAY).bool(VisualMods.Overlay.UNDERWATER);
	}

	/** A full-screen texture overlay (pumpkin blur, powder snow frost): whether to skip it. */
	public static boolean hideTexture(Identifier texture) {
		if (!GUARD.on() || !Mods.on(OVERLAY)) {
			return false;
		}
		ModuleState state = Mods.state(OVERLAY);
		String path = texture.getPath();
		return path.contains("pumpkinblur") && state.bool(VisualMods.Overlay.PUMPKIN)
			|| path.contains("powder_snow_outline") && state.bool(VisualMods.Overlay.POWDER_SNOW);
	}

	public static boolean hidePortal() {
		return GUARD.on() && Mods.on(OVERLAY) && Mods.state(OVERLAY).bool(VisualMods.Overlay.PORTAL);
	}

	public static boolean hideNausea() {
		return GUARD.on() && Mods.on(OVERLAY) && Mods.state(OVERLAY).bool(VisualMods.Overlay.NAUSEA);
	}

	// ---------- particles ----------

	/** How many emitters to make for a critical (true) or enchanted (false) hit Minecraft shows. */
	public static int hitParticles(boolean critical) {
		if (!GUARD.on() || !Mods.on(PARTICLES)) {
			return 1;
		}
		ModuleState state = Mods.state(PARTICLES);
		return state.integer(critical ? VisualMods.ParticleChanger.CRITS : VisualMods.ParticleChanger.SHARPNESS);
	}

	/** Whether every hit with an enchanted weapon shows enchanted hit particles. */
	public static boolean alwaysSharpness() {
		return GUARD.on() && Mods.on(PARTICLES) && Mods.state(PARTICLES).bool(VisualMods.ParticleChanger.ALWAYS_SHARPNESS);
	}

	// ---------- block outline ----------

	public static boolean outlineOn(BlockOutlineRenderState state) {
		return GUARD.on() && Mods.on(OUTLINE) && !state.highContrast();
	}

	public static int outlineColor() {
		ModuleState state = Mods.state(OUTLINE);
		int alpha = Math.round(255 * state.integer(VisualMods.BlockOutline.OPACITY) / 100.0F);
		return alpha << 24 | state.rgb(VisualMods.BlockOutline.COLOR);
	}

	public static float outlineWidth(float vanilla) {
		return vanilla * Mods.state(OUTLINE).integer(VisualMods.BlockOutline.THICKNESS) / 100.0F;
	}

	/** Tints the faces of the outlined block (the pose is already at the block). */
	public static void fill(PoseStack poseStack, SubmitNodeCollector collector, BlockOutlineRenderState outline) {
		ModuleState state = Mods.state(OUTLINE);
		if (!state.bool(VisualMods.BlockOutline.FILL)) {
			return;
		}
		int alpha = Math.round(255 * state.integer(VisualMods.BlockOutline.FILL_OPACITY) / 100.0F);
		int color = alpha << 24 | state.rgb(VisualMods.BlockOutline.FILL_COLOR);
		collector.submitCustomGeometry(poseStack, RenderTypes.debugFilledBox(), (pose, buffer) -> outline.shape().forAllBoxes(
			(x0, y0, z0, x1, y1, z1) -> {
				float e = 0.002F;
				float ax = (float) x0 - e;
				float ay = (float) y0 - e;
				float az = (float) z0 - e;
				float bx = (float) x1 + e;
				float by = (float) y1 + e;
				float bz = (float) z1 + e;
				quad(buffer, pose, color, ax, ay, az, bx, ay, az, bx, ay, bz, ax, ay, bz);
				quad(buffer, pose, color, ax, by, az, ax, by, bz, bx, by, bz, bx, by, az);
				quad(buffer, pose, color, ax, ay, az, ax, by, az, bx, by, az, bx, ay, az);
				quad(buffer, pose, color, ax, ay, bz, bx, ay, bz, bx, by, bz, ax, by, bz);
				quad(buffer, pose, color, ax, ay, az, ax, ay, bz, ax, by, bz, ax, by, az);
				quad(buffer, pose, color, bx, ay, az, bx, by, az, bx, by, bz, bx, ay, bz);
			}));
	}

	private static void quad(com.mojang.blaze3d.vertex.VertexConsumer buffer, PoseStack.Pose pose, int color, float x0, float y0, float z0,
		float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3) {
		buffer.addVertex(pose, x0, y0, z0).setColor(color);
		buffer.addVertex(pose, x1, y1, z1).setColor(color);
		buffer.addVertex(pose, x2, y2, z2).setColor(color);
		buffer.addVertex(pose, x3, y3, z3).setColor(color);
	}

	// ---------- shulker preview ----------

	/** A shulker box's tooltip image (its items in a grid), or empty to leave the tooltip as it is. */
	public static Optional<TooltipComponent> shulkerImage(ItemStack stack, Optional<TooltipComponent> vanilla) {
		if (vanilla.isPresent() || !GUARD.on() || !Mods.on(SHULKER) || !stack.is(ItemTags.SHULKER_BOXES)) {
			return vanilla;
		}
		try {
			ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
			if (contents == null || contents.nonEmptyItemCopyStream().findAny().isEmpty() || !shulkerShown()) {
				return vanilla;
			}
			return Optional.of(new ShulkerTooltip(contents.itemCopies().toList()));
		} catch (RuntimeException e) {
			GUARD.fail(e);
			return vanilla;
		}
	}

	private static boolean shulkerShown() {
		return Mods.state(SHULKER).option(UtilityMods.ShulkerPreview.SHOW) == 0 || Mc263ControlPlatform.keyDown(225)
			|| Mc263ControlPlatform.keyDown(229);
	}

	/** Whether Minecraft's text list of a container's items is left out (the grid shows them). */
	public static boolean hideContainerList() {
		return GUARD.on() && Mods.on(SHULKER) && Mods.state(SHULKER).bool(UtilityMods.ShulkerPreview.HIDE_LIST) && shulkerShown();
	}

	/** The client side of {@link ShulkerTooltip}, or null for any other tooltip image. */
	public static @Nullable ClientTooltipComponent clientTooltip(TooltipComponent component) {
		return component instanceof ShulkerTooltip shulker ? new ShulkerGrid(shulker.items()) : null;
	}

	/** A shulker box's slots, empty ones included, so the grid keeps their places. */
	public record ShulkerTooltip(List<ItemStack> items) implements TooltipComponent {
	}

	/** Nine items a row, 18 units a slot, with Minecraft's own item rendering and counts. */
	private static final class ShulkerGrid implements ClientTooltipComponent {
		private final List<ItemStack> items;

		ShulkerGrid(List<ItemStack> items) {
			this.items = items;
		}

		private int rows() {
			return Math.max(1, (items.size() + 8) / 9);
		}

		@Override
		public int getHeight(Font font) {
			return rows() * 18 + 4;
		}

		@Override
		public int getWidth(Font font) {
			return 9 * 18;
		}

		@Override
		public void extractImage(Font font, int x, int y, int w, int h, GuiGraphicsExtractor graphics) {
			for (int i = 0; i < items.size(); i++) {
				int sx = x + i % 9 * 18;
				int sy = y + 2 + i / 9 * 18;
				graphics.fill(sx, sy, sx + 17, sy + 17, 0x40000000);
				ItemStack stack = items.get(i);
				if (!stack.isEmpty()) {
					graphics.item(stack, sx, sy);
					graphics.itemDecorations(font, stack, sx, sy);
				}
			}
		}
	}

	// ---------- screenshot preview ----------

	private static volatile @Nullable File pendingShot;
	private static long pendingSince;
	private static boolean loaded;
	private static int shotW;
	private static int shotH;
	private static long shownAt = Long.MIN_VALUE;

	/** From Minecraft's screenshot code (any thread): the file a screenshot is being written to. */
	public static File screenshotFile(File file) {
		if (GUARD.on() && !file.getName().startsWith("panorama")) {
			pendingShot = file;
		}
		return file;
	}

	/** From the client tick: once the screenshot file is written, loads it for the preview. */
	public static void tick() {
		File file = pendingShot;
		if (file == null || !GUARD.on()) {
			return;
		}
		if (!Mods.on(SCREENSHOTS)) {
			pendingShot = null;
			return;
		}
		long now = Util.getMillis();
		if (pendingSince == 0) {
			pendingSince = now;
		}
		// Written on Minecraft's IO thread; wait until it exists and has stopped growing, at most 10 s.
		if (!file.isFile() || now - pendingSince < 400) {
			if (now - pendingSince > 10_000) {
				pendingShot = null;
				pendingSince = 0;
			}
			return;
		}
		pendingShot = null;
		pendingSince = 0;
		ModuleState state = Mods.state(SCREENSHOTS);
		if (state.bool(UtilityMods.Screenshots.PREVIEW)) {
			try (InputStream in = Files.newInputStream(file.toPath())) {
				NativeImage image = NativeImage.read(in);
				shotW = image.getWidth();
				shotH = image.getHeight();
				Minecraft.getInstance().getTextureManager().register(SHOT, new DynamicTexture(() -> "Flint screenshot preview", image));
				loaded = true;
				shownAt = now;
			} catch (Exception e) {
				FlintClient.LOGGER.warn("Flint couldn't load the screenshot preview from {}", file.getName(), e);
			}
		}
		int after = state.option(UtilityMods.Screenshots.AFTER);
		if (state.bool(UtilityMods.Screenshots.JPEG)) {
			// Converted off the render thread; the action then uses the .jpg.
			java.util.concurrent.CompletableFuture.supplyAsync(() -> toJpeg(file), Util.ioPool())
				.thenAcceptAsync(saved -> afterShot(saved, after), Minecraft.getInstance());
		} else {
			afterShot(file, after);
		}
	}

	private static volatile @Nullable File lastSaved;

	/** The last screenshot file Flint saw saved (after any JPEG conversion), for the dev tour. */
	public static @Nullable File lastSaved() {
		return lastSaved;
	}

	/** The After screenshot action, on the render thread: copy the path, open the file, or open the folder. */
	private static void afterShot(File file, int action) {
		lastSaved = file;
		try {
			Minecraft mc = Minecraft.getInstance();
			switch (action) {
				case 1 -> mc.keyboardHandler.setClipboard(file.getAbsolutePath());
				case 2 -> com.mojang.blaze3d.Blaze3D.openPath(file.toPath());
				case 3 -> com.mojang.blaze3d.Blaze3D.openPath(file.toPath().getParent());
				default -> {
				}
			}
		} catch (RuntimeException e) {
			FlintClient.LOGGER.warn("Flint couldn't run the after-screenshot action", e);
		}
	}

	/**
	 * A copy of the PNG as a JPEG next to it (quality 92, with Minecraft's own image library, no AWT); the PNG is removed once
	 * the JPEG is written. Returns the file to use (the PNG when anything fails).
	 */
	static File toJpeg(File png) {
		File jpg = new File(png.getParentFile(), png.getName().replaceFirst("\\.png$", "") + ".jpg");
		try (InputStream in = Files.newInputStream(png.toPath()); NativeImage image = NativeImage.read(in);
			org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
			int components = image.format().components();
			java.nio.ByteBuffer path = stack.UTF8(jpg.getAbsolutePath());
			boolean ok = org.lwjgl.stb.STBImageWrite.nstbi_write_jpg(org.lwjgl.system.MemoryUtil.memAddress(path), image.getWidth(), image.getHeight(),
				components, image.getPointer(), 92) != 0;
			if (!ok || !jpg.isFile() || jpg.length() == 0) {
				return png;
			}
			Files.deleteIfExists(png.toPath());
			return jpg;
		} catch (Exception e) {
			FlintClient.LOGGER.warn("Flint couldn't save {} as JPEG; the PNG is kept", png.getName(), e);
			return png;
		}
	}

	/** From the HUD: the preview in its corner while it lasts, fading out over its last half second. */
	public static void render(GuiGraphicsExtractor g) {
		if (!loaded || !GUARD.on()) {
			return;
		}
		try {
			long now = Util.getMillis();
			ModuleState state = Mods.state(SCREENSHOTS);
			long duration = state.integer(UtilityMods.Screenshots.DURATION) * 1000L;
			if (!Mods.on(SCREENSHOTS) || now - shownAt > duration) {
				loaded = false;
				Minecraft.getInstance().getTextureManager().release(SHOT);
				return;
			}
			float fade = Math.min(1, (duration - (now - shownAt)) / 500.0F);
			int w = Math.round(g.guiWidth() * state.integer(UtilityMods.Screenshots.SIZE) / 100.0F);
			int h = Math.max(1, Math.round((float) w * shotH / Math.max(1, shotW)));
			int corner = state.option(UtilityMods.Screenshots.CORNER);
			boolean left = corner == 2 || corner == 3;
			boolean top = corner == 1 || corner == 3;
			int dx = state.integer(UtilityMods.Screenshots.X_OFFSET);
			int dy = state.integer(UtilityMods.Screenshots.Y_OFFSET);
			int x = left ? 8 + dx : g.guiWidth() - w - 8 - dx;
			int y = top ? 8 + dy : g.guiHeight() - h - 30 - dy;
			// Slides in from its side over the animation duration (none with reduced motion).
			int animation = state.integer(UtilityMods.Screenshots.ANIMATION);
			if (animation > 0 && !com.flintclient.client.ui.kit.Kit.reduced()) {
				float t = Math.min(1, (now - shownAt) / (float) animation);
				float ease = 1 - (1 - t) * (1 - t) * (1 - t);
				int travel = Math.round((w + 16 + dx) * (1 - ease));
				x += left ? -travel : travel;
			}
			int alpha = Math.round(255 * fade);
			g.fill(x - 2, y - 2, x + w + 2, y + h + 2, alpha << 24 | state.rgb(UtilityMods.Screenshots.BORDER));
			g.blit(RenderPipelines.GUI_TEXTURED, SHOT, x, y, 0, 0, w, h, shotW, shotH, shotW, shotH, alpha << 24 | 0xFFFFFF);
		} catch (RuntimeException e) {
			GUARD.fail(e);
		}
	}
}
