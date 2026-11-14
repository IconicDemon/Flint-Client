package com.flintclient.qa;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Block Overlay and Block Indicator in a tour: {@code call com.flintclient.qa.QaOverlay.target} logs Minecraft's own target (the
 * block it outlines, and how far away the hit is) and what Block Indicator shows now (its name, or none). Reflection into the
 * runtime, so the QA mod doesn't build against it. Dev-only.
 */
public final class QaOverlay {
	private QaOverlay() {
	}

	public static String target() throws ReflectiveOperationException {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return "no world";
		}
		HitResult hit = mc.hitResult;
		String minecraft;
		if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
			minecraft = "outlines " + mc.level.getBlockState(block.getBlockPos()).getBlock().getName().getString() + " at "
				+ block.getBlockPos().toShortString() + String.format(java.util.Locale.ROOT, ", %.2f blocks from the eye",
				Math.sqrt(mc.player.getEyePosition().distanceToSqr(hit.getLocation())));
		} else {
			minecraft = "outlines nothing (" + (hit == null ? "no hit" : hit.getType()) + ")";
		}
		Object look = Class.forName("com.flintclient.client.platform.HudData").getMethod("block").invoke(null);
		String shown = look == null ? "none" : String.valueOf(look.getClass().getField("name").get(look));
		return "Minecraft " + minecraft + "; Block Indicator shows " + shown + "; creative " + mc.player.isCreative();
	}
}
