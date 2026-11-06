package com.flintclient.legacy.mods;

import com.flintclient.client.control.model.mods.VisualMods;
import com.flintclient.legacy.module.Module;
import com.flintclient.legacy.module.Setting;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.render.vertex.BufferBuilder;
import net.minecraft.client.render.vertex.DefaultVertexFormat;
import net.minecraft.client.render.vertex.Tesselator;
import net.minecraft.entity.living.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.HitResult;
import org.lwjgl.opengl.GL11;

/**
 * The outline of the block you're looking at, in your colour and thickness, with a fill over its faces if you like. The same
 * box Minecraft outlines (the block's own shape), drawn where Minecraft draws its outline. The 1.8.9 backend of Block Outline:
 * the outline's colour, opacity and thickness (100% is Minecraft's line), and the fill, are the shared settings.
 */
public final class BlockOverlay extends Module {
	/** Minecraft 1.8.9's own outline width, in pixels: 100% thickness. */
	private static final float LINE = 2;
	public final Setting.Color outlineColor = color(VisualMods.BlockOutline.COLOR, VisualMods.BlockOutline.OPACITY, true);
	public final Setting.Int thickness = integer(VisualMods.BlockOutline.THICKNESS);
	public final Setting.Bool fill = bool(VisualMods.BlockOutline.FILL);
	public final Setting.Color fillColor = color(VisualMods.BlockOutline.FILL_COLOR, VisualMods.BlockOutline.FILL_OPACITY, true);

	public BlockOverlay() {
		super("block_outline", "blockoverlay");
	}

	/** Draws the overlay for {@code hit} (from WorldRenderer's outline call); false to let Minecraft draw its own. */
	public boolean render(PlayerEntity camera, HitResult hit, float tickDelta) {
		if (!enabled()) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		if (hit == null || hit.type != HitResult.Type.BLOCK || mc.world == null) {
			return true;
		}
		BlockPos pos = hit.getPos();
		Block block = mc.world.getBlockState(pos).getBlock();
		if (block.getMaterial() == Material.AIR || !mc.world.getWorldBorder().contains(pos)) {
			return true;
		}
		block.updateShape(mc.world, pos);
		double dx = camera.prevX + (camera.x - camera.prevX) * tickDelta;
		double dy = camera.prevY + (camera.y - camera.prevY) * tickDelta;
		double dz = camera.prevZ + (camera.z - camera.prevZ) * tickDelta;
		Box box = block.getOutlineShape(mc.world, pos).grown(0.002, 0.002, 0.002).moved(-dx, -dy, -dz);
		GlStateManager.enableBlend();
		GlStateManager.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
		GlStateManager.disableTexture();
		GlStateManager.depthMask(false);
		GlStateManager.disableCull();
		if (fill.on()) {
			faces(box, fillColor.argb());
		}
		GL11.glLineWidth(Math.max(1, LINE * thickness.value() / 100F));
		edges(box, outlineColor.argb());
		GL11.glLineWidth(1);
		GlStateManager.enableDepthTest();
		GlStateManager.enableCull();
		GlStateManager.depthMask(true);
		GlStateManager.enableTexture();
		GlStateManager.disableBlend();
		GlStateManager.color4f(1, 1, 1, 1);
		return true;
	}

	private static void faces(Box b, int argb) {
		int r = (argb >> 16) & 255;
		int g = (argb >> 8) & 255;
		int bl = argb & 255;
		int a = argb >>> 24;
		Tesselator t = Tesselator.getInstance();
		BufferBuilder buf = t.getBuffer();
		buf.begin(GL11.GL_QUADS, DefaultVertexFormat.POSITION_COLOR);
		double[][] quads = {
			{b.minX, b.minY, b.minZ, b.maxX, b.minY, b.minZ, b.maxX, b.minY, b.maxZ, b.minX, b.minY, b.maxZ},
			{b.minX, b.maxY, b.minZ, b.minX, b.maxY, b.maxZ, b.maxX, b.maxY, b.maxZ, b.maxX, b.maxY, b.minZ},
			{b.minX, b.minY, b.minZ, b.minX, b.maxY, b.minZ, b.maxX, b.maxY, b.minZ, b.maxX, b.minY, b.minZ},
			{b.minX, b.minY, b.maxZ, b.maxX, b.minY, b.maxZ, b.maxX, b.maxY, b.maxZ, b.minX, b.maxY, b.maxZ},
			{b.minX, b.minY, b.minZ, b.minX, b.minY, b.maxZ, b.minX, b.maxY, b.maxZ, b.minX, b.maxY, b.minZ},
			{b.maxX, b.minY, b.minZ, b.maxX, b.maxY, b.minZ, b.maxX, b.maxY, b.maxZ, b.maxX, b.minY, b.maxZ}};
		for (double[] q : quads) {
			for (int i = 0; i < 12; i += 3) {
				buf.vertex(q[i], q[i + 1], q[i + 2]).color(r, g, bl, a).nextVertex();
			}
		}
		t.end();
	}

	private static void edges(Box b, int argb) {
		int r = (argb >> 16) & 255;
		int g = (argb >> 8) & 255;
		int bl = argb & 255;
		int a = argb >>> 24;
		Tesselator t = Tesselator.getInstance();
		BufferBuilder buf = t.getBuffer();
		buf.begin(GL11.GL_LINES, DefaultVertexFormat.POSITION_COLOR);
		double[] xs = {b.minX, b.maxX};
		double[] ys = {b.minY, b.maxY};
		double[] zs = {b.minZ, b.maxZ};
		for (double y : ys) {
			for (double z : zs) {
				line(buf, b.minX, y, z, b.maxX, y, z, r, g, bl, a);
			}
			for (double x : xs) {
				line(buf, x, y, b.minZ, x, y, b.maxZ, r, g, bl, a);
			}
		}
		for (double x : xs) {
			for (double z : zs) {
				line(buf, x, b.minY, z, x, b.maxY, z, r, g, bl, a);
			}
		}
		t.end();
	}

	private static void line(BufferBuilder buf, double x0, double y0, double z0, double x1, double y1, double z1, int r, int g, int b, int a) {
		buf.vertex(x0, y0, z0).color(r, g, b, a).nextVertex();
		buf.vertex(x1, y1, z1).color(r, g, b, a).nextVertex();
	}
}
