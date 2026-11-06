/*
 * Adapted from PlayerHealthIndicators by Gaider10, MIT License: see META-INF/licenses/MIT-PlayerHealthIndicators.txt.
 */
package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.PlayerMods.HealthIndicators;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;

/**
 * Health Indicators, as PlayerHealthIndicators: other players' health (and absorption) as Minecraft's own hearts above their
 * heads, facing you, up to 64 blocks away; ten to a row with Heart stacking (the rows closer together the more there are),
 * else one row; raised by the heart offset. Its keys toggle stacking and nudge the offset, and say so on the action bar.
 * The hearts are submitted with the player's other geometry, after it is drawn. Fault-isolated.
 */
public final class Mc263HealthIndicators {
	private static final HookGuard GUARD = new HookGuard("health indicators",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final int MODULE = Mods.index(HealthIndicators.ID);
	private static final Identifier GUI_ATLAS = Identifier.withDefaultNamespace("textures/atlas/gui.png");
	private static final Identifier GUI_ATLAS_ID = Identifier.withDefaultNamespace("gui");
	private static final Identifier EMPTY = Identifier.withDefaultNamespace("hud/heart/container");
	private static final Identifier RED_FULL = Identifier.withDefaultNamespace("hud/heart/full");
	private static final Identifier RED_HALF = Identifier.withDefaultNamespace("hud/heart/half");
	private static final Identifier YELLOW_FULL = Identifier.withDefaultNamespace("hud/heart/absorbing_full");
	private static final Identifier YELLOW_HALF = Identifier.withDefaultNamespace("hud/heart/absorbing_half");
	private static final boolean[] HELD = new boolean[3];

	private Mc263HealthIndicators() {
	}

	/** A living thing's render state's room for the hearts ({@code LivingEntityRenderStateMixin}). */
	public interface Health {
		boolean flint$healthShown();

		void flint$health(boolean shown, float health, float maxHealth, float absorption);

		float flint$healthValue();

		float flint$maxHealth();

		float flint$absorption();
	}

	private static boolean on() {
		return GUARD.on() && Mods.on(MODULE);
	}

	/** As each entity's state is made: another player you can see has its health read for the hearts. */
	public static void extract(LivingEntity entity, LivingEntityRenderState state) {
		Health health = (Health) state;
		if (!on()) {
			health.flint$health(false, 0, 0, 0);
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			if (entity instanceof AbstractClientPlayer player && !player.isLocalPlayer() && mc.player != null && !player.isInvisibleTo(mc.player)) {
				health.flint$health(true, player.getHealth(), player.getMaxHealth(), player.getAbsorptionAmount());
			} else {
				health.flint$health(false, 0, 0, 0);
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** After a living thing's geometry: its hearts, when it is a player whose health was read and within 64 blocks. */
	public static void submit(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		if (!on() || !(state instanceof Health health) || !health.flint$healthShown() || state.distanceToCameraSq > 4096.0) {
			return;
		}
		try {
			ModuleState s = Mods.state(MODULE);
			poseStack.pushPose();
			poseStack.translate(0.0F, state.boundingBoxHeight + 0.5F, 0.0F);
			if (state.nameTag != null) {
				poseStack.translate(0.0, 0.25875F, 0.0);
			}
			poseStack.mulPose(new Matrix4f().rotation(camera.orientation));
			poseStack.scale(-1.0F, 1.0F, 1.0F);
			poseStack.scale(0.025F, 0.025F, 0.025F);
			poseStack.translate(0.0F, s.integer(HealthIndicators.OFFSET), 0.0F);
			TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(GUI_ATLAS_ID);
			TextureAtlasSprite empty = atlas.getSprite(EMPTY);
			TextureAtlasSprite redFull = atlas.getSprite(RED_FULL);
			TextureAtlasSprite redHalf = atlas.getSprite(RED_HALF);
			TextureAtlasSprite yellowFull = atlas.getSprite(YELLOW_FULL);
			TextureAtlasSprite yellowHalf = atlas.getSprite(YELLOW_HALF);
			int healthRed = Mth.ceil(health.flint$healthValue());
			int maxHealth = Mth.ceil(health.flint$maxHealth());
			int healthYellow = Mth.ceil(health.flint$absorption());
			int heartsRed = Mth.ceil(healthRed / 2.0F);
			boolean lastRedHalf = (healthRed & 1) == 1;
			int heartsNormal = Mth.ceil(maxHealth / 2.0F);
			int heartsYellow = Mth.ceil(healthYellow / 2.0F);
			boolean lastYellowHalf = (healthYellow & 1) == 1;
			int heartsTotal = heartsNormal + heartsYellow;
			if (heartsTotal > 0) {
				int heartsPerRow = s.bool(HealthIndicators.STACKING) ? 10 : heartsTotal;
				int rowsTotal = (heartsTotal + heartsPerRow - 1) / heartsPerRow;
				int rowOffset = Math.max(10 - (rowsTotal - 2), 3);
				int pixelsTotal = Math.min(heartsTotal, heartsPerRow) * 8 + 1;
				float maxX = pixelsTotal / 2.0F;
				collector.order(0).submitCustomGeometry(poseStack, RenderTypes.entityCutout(GUI_ATLAS), (entry, consumer) -> {
					Matrix4f model = entry.pose();
					for (int heart = 0; heart < heartsTotal; heart++) {
						int row = heart / heartsPerRow;
						int col = heart % heartsPerRow;
						float x = maxX - col * 8.0F;
						float y = row * rowOffset;
						float z = row * 0.01F;
						drawHeart(model, consumer, x, y, z, empty);
						TextureAtlasSprite sprite;
						if (heart < heartsRed) {
							sprite = lastRedHalf && heart == heartsRed - 1 ? redHalf : redFull;
						} else if (heart < heartsNormal) {
							sprite = null;
						} else {
							sprite = lastYellowHalf && heart == heartsTotal - 1 ? yellowHalf : yellowFull;
						}
						if (sprite != null) {
							drawHeart(model, consumer, x, y, z, sprite);
						}
					}
				});
			}
			poseStack.popPose();
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	private static void drawHeart(Matrix4f model, VertexConsumer vertices, float x, float y, float z, TextureAtlasSprite sprite) {
		float minU = sprite.getU0();
		float maxU = sprite.getU1();
		float minV = sprite.getV0();
		float maxV = sprite.getV1();
		float size = 9.0F;
		vertex(model, vertices, x, y - size, z, minU, maxV);
		vertex(model, vertices, x - size, y - size, z, maxU, maxV);
		vertex(model, vertices, x - size, y, z, maxU, minV);
		vertex(model, vertices, x, y, z, minU, minV);
	}

	private static void vertex(Matrix4f model, VertexConsumer vertices, float x, float y, float z, float u, float v) {
		vertices.addVertex(model, x, y, z).setColor(255, 255, 255, 255).setUv(u, v).setOverlay(0).setLight(15728880).setNormal(0.0F, 0.0F, 1.0F);
	}

	/** Each client tick while playing: its keys toggle stacking and move the hearts up or down, saying so on the action bar. */
	public static void tick() {
		if (!on() || !Mc263Features.gameplay()) {
			java.util.Arrays.fill(HELD, false);
			return;
		}
		try {
			ModuleState s = Mods.state(MODULE);
			if (pressed(s, HealthIndicators.STACKING_KEY, 0)) {
				boolean stacking = !s.bool(HealthIndicators.STACKING);
				change(s, () -> s.set(HealthIndicators.STACKING, stacking), "Heart Stacking " + (stacking ? "Enabled" : "Disabled"));
			}
			if (pressed(s, HealthIndicators.INCREASE_KEY, 1)) {
				int offset = HealthIndicators.OFFSET.clamp(s.integer(HealthIndicators.OFFSET) + 1);
				change(s, () -> s.set(HealthIndicators.OFFSET, offset), "Heart Offset: " + offset);
			}
			if (pressed(s, HealthIndicators.DECREASE_KEY, 2)) {
				int offset = HealthIndicators.OFFSET.clamp(s.integer(HealthIndicators.OFFSET) - 1);
				change(s, () -> s.set(HealthIndicators.OFFSET, offset), "Heart Offset: " + offset);
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	private static boolean pressed(ModuleState s, com.flintclient.client.control.model.KeySetting key, int index) {
		boolean down = Mc263Features.keyHeld(s.text(key));
		boolean fresh = down && !HELD[index];
		HELD[index] = down;
		return fresh;
	}

	private static void change(ModuleState s, Runnable set, String message) {
		set.run();
		ControlState.settings().recordChange(s.id());
		ControlState.saveAll();
		com.flintclient.client.compat.McCompat.hud().setOverlayMessage(Component.literal(message), false);
	}
}
