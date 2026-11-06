package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.VisualMods;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Minecraft 26.3's side of View Model, called from {@code FirstPersonItemMixin}. It moves the held item in view space where Minecraft starts placing the hand (the
 * other hand mirrored), and rotates and scales it about itself just before it is drawn. Fault-isolated; allocation-free.
 */
public final class Mc263ViewModel {
	private static final int VIEW_MODEL = Mods.index(VisualMods.ViewModel.ID);
	private static final HookGuard GUARD = new HookGuard("view model",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));

	private Mc263ViewModel() {
	}

	/** Right after the hand's pose is pushed: the item's position (and a shield's height), in view space. */
	public static void place(PoseStack pose, InteractionHand hand, ItemStack stack) {
		if (!GUARD.on() || stack.isEmpty() || !Mods.on(VIEW_MODEL)) {
			return;
		}
		try {
			ModuleState view = Mods.state(VIEW_MODEL);
			float x = 0;
			float y = 0;
			float z = 0;
			if (view.bool(VisualMods.ViewModel.POSITION)) {
				x = view.decimal(VisualMods.ViewModel.POSITION_X) * side(hand);
				y = view.decimal(VisualMods.ViewModel.POSITION_Y);
				z = view.decimal(VisualMods.ViewModel.POSITION_Z);
			}
			if (stack.is(Items.SHIELD)) {
				y += view.decimal(VisualMods.ViewModel.SHIELD_HEIGHT);
			}
			if (x != 0 || y != 0 || z != 0) {
				pose.translate(x, y, z);
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** Whether {@link #shape} has anything to do (else the item is drawn as it is). */
	public static boolean shapes() {
		if (!GUARD.on() || !Mods.on(VIEW_MODEL)) {
			return false;
		}
		ModuleState view = Mods.state(VIEW_MODEL);
		return view.bool(VisualMods.ViewModel.SCALE) || view.bool(VisualMods.ViewModel.ROTATION);
	}

	/** Just before the item is drawn: its rotation and scale, about itself. */
	public static void shape(PoseStack pose, InteractionHand hand) {
		try {
			ModuleState view = Mods.state(VIEW_MODEL);
			if (view.bool(VisualMods.ViewModel.ROTATION)) {
				float side = side(hand);
				com.flintclient.client.compat.McCompat.rotateDegrees(pose, Axis.XP, view.decimal(VisualMods.ViewModel.ROTATION_X));
				com.flintclient.client.compat.McCompat.rotateDegrees(pose, Axis.YP, view.decimal(VisualMods.ViewModel.ROTATION_Y) * side);
				com.flintclient.client.compat.McCompat.rotateDegrees(pose, Axis.ZP, view.decimal(VisualMods.ViewModel.ROTATION_Z) * side);
			}
			if (view.bool(VisualMods.ViewModel.SCALE)) {
				// A zero axis would flatten the item to nothing (and break its lighting): the least is a hundredth.
				pose.scale(Math.max(0.01F, view.decimal(VisualMods.ViewModel.SCALE_X)), Math.max(0.01F, view.decimal(VisualMods.ViewModel.SCALE_Y)),
					Math.max(0.01F, view.decimal(VisualMods.ViewModel.SCALE_Z)));
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** 1 for the hand on the right of the screen, -1 for the left. */
	private static float side(InteractionHand hand) {
		HumanoidArm main = Minecraft.getInstance().options.mainHand().get();
		HumanoidArm arm = hand == InteractionHand.MAIN_HAND ? main : main.getOpposite();
		return arm == HumanoidArm.RIGHT ? 1 : -1;
	}
}
