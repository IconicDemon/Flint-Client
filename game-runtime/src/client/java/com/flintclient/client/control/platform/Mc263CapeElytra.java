package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.feature.CapeElytra;
import com.flintclient.client.control.model.GameOptions;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Minecraft's side of the cape-derived elytra ({@link CapeElytra}, mapping "cape-derived-elytra" v1, as Flint's websites
 * preview it): a player who wears a Flint Store cape and an elytra has wings made of that cape, its front outside, its lining
 * inside, cut to the stepped outline, instead of Minecraft's elytra.
 *
 * <p>Only the wing geometry and its texture change. The elytra layer still decides whether wings are drawn (worn, the player
 * visible, not in first person), and the elytra model's own two wing parts still place them: their pivots, Minecraft's own
 * smoothed wing angles every frame (standing, crouching 3 px lower, gliding, diving), the layer's offset behind the body. The
 * wing parts draw {@link CapeElytra#wingQuads} in their own space ({@link PartDrawing}, as Wavy Capes draws its cloth), with
 * the cape's texture (the one the cape layer draws, so an animated cape plays on the wings at the cape's own frame) in a
 * back-face-culled cut-out pass, and the glint when the elytra is enchanted. Which players: {@link CapeElytra#use}, decided
 * by the version's elytra layer hook ({@code mixin/feature/WingsLayerMixin}). Render thread; fault-isolated (a failure leaves
 * Minecraft's elytra for the session).
 */
public final class Mc263CapeElytra {
	private static final HookGuard GUARD = new HookGuard("cape design on elytra",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final Vector3f POS = new Vector3f();
	private static final Vector3f NORMAL = new Vector3f();
	private static final PartDrawing LEFT = (pose, buffer, light, overlay, color) -> draw(CapeElytra.wingQuads(false), pose, buffer, light, overlay,
		color);
	private static final PartDrawing RIGHT = (pose, buffer, light, overlay, color) -> draw(CapeElytra.wingQuads(true), pose, buffer, light, overlay,
		color);

	private Mc263CapeElytra() {
	}

	/** Whether the viewer has "Cape design on elytra" on (and the hook hasn't failed this session). */
	public static boolean enabled() {
		return GUARD.on() && ControlState.settings().gameBool(GameOptions.CAPE_ELYTRA);
	}

	/** Turns it off for the session after {@code error} (the elytra layer hook's own failure): Minecraft's elytras from then on. */
	public static void failed(Throwable error) {
		GUARD.fail(error);
	}

	/**
	 * Right after the elytra model is set up for a player (Minecraft drew its wing angles into the parts): {@code capeWings}, the
	 * wing parts draw the cape-derived wings; otherwise their boxes, as Minecraft's. The model is shared by every player, so it is
	 * set each time.
	 */
	public static void setup(ModelPart leftWing, ModelPart rightWing, boolean capeWings) {
		CustomPart left = (CustomPart) (Object) leftWing;
		CustomPart right = (CustomPart) (Object) rightWing;
		boolean on = capeWings && GUARD.on();
		left.flint$drawing(on ? LEFT : null);
		right.flint$drawing(on ? RIGHT : null);
	}

	/** One wing: its quads, transformed into the pose, lit and tinted as the wing's box would be. Allocation-free. */
	private static void draw(float[] quads, PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay, int color) {
		try {
			Matrix4f m = pose.pose();
			for (int i = 0; i < quads.length; i += CapeElytra.FLOATS_PER_VERTEX) {
				m.transformPosition(quads[i], quads[i + 1], quads[i + 2], POS);
				NORMAL.set(quads[i + 5], quads[i + 6], quads[i + 7]);
				pose.transformNormal(NORMAL, NORMAL);
				buffer.addVertex(POS.x(), POS.y(), POS.z(), color, quads[i + 3], quads[i + 4], overlay, light, NORMAL.x(), NORMAL.y(), NORMAL.z());
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}
}
