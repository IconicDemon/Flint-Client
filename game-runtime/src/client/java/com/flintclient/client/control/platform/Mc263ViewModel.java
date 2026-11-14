package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.HeldItems;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.HeldTransform;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.VisualMods.ViewModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.ShieldItem;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft's side of Item Customizer (the view_model module), called from {@code FirstPersonItemMixin}, {@code HeldEquipMixin},
 * the swing-duration hook and the dropped-item renderer, the same on every 26.x and 1.21.x line. A held item is moved in view
 * space where Minecraft starts placing its hand (the other hand mirrored) and turned and sized about itself just before it is
 * drawn; with Swing Scale its size is applied about its resting place instead, so its swing shrinks or grows with it. The empty
 * hand is moved, turned and sized about its resting place. Which place an item takes is looked up per item in a map kept until a
 * setting changes ({@link HeldItems.Lookup}). Fault-isolated; allocation-free per frame.
 */
public final class Mc263ViewModel {
	private static final int VIEW_MODEL = Mods.index(ViewModel.ID);
	private static final HookGuard GUARD = new HookGuard("item customizer",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	/** Where Minecraft rests a held item and the empty arm (right hand; X mirrored for the left), in view space. */
	private static final float ITEM_X = 0.56F;
	private static final float ITEM_Y = -0.52F;
	private static final float ITEM_Z = -0.72F;
	private static final float ARM_X = 0.64F;
	private static final float ARM_Y = -0.6F;
	private static final float ARM_Z = -0.72F;
	private static final HeldItems.Lookup LOOKUP = new HeldItems.Lookup(new HeldItems.Facts() {
		@Override
		public String id(Object item) {
			return Mc263Items.id((Item) item);
		}

		@Override
		public int categories(Object item) {
			return Mc263ViewModel.categories((Item) item);
		}
	});
	/** The place of the item being drawn (set where its hand's pose starts, read just before it is drawn), or null. */
	private static @Nullable HeldTransform current;
	/** Whether {@link #current}'s size was already applied about its resting place (Swing Scale). */
	private static boolean sizedAtRest;

	private Mc263ViewModel() {
	}

	private static boolean on() {
		return GUARD.on() && Mods.on(VIEW_MODEL);
	}

	/** Right after the hand's pose is pushed: the item's (or empty hand's) place, in view space. */
	public static void place(PoseStack pose, InteractionHand hand, ItemStack stack) {
		current = null;
		sizedAtRest = false;
		if (!on()) {
			return;
		}
		try {
			ModuleState s = Mods.state(VIEW_MODEL);
			float side = side(hand);
			if (stack.isEmpty()) {
				HeldTransform t = LOOKUP.hand(s);
				if (t != null) {
					pose.translate(t.x() * side, t.y(), t.z());
					aboutRest(pose, t, side, ARM_X, ARM_Y, ARM_Z, true);
				}
				return;
			}
			HeldTransform t = LOOKUP.place(s, stack.getItem());
			if (t == null) {
				return;
			}
			current = t;
			if (t.moves()) {
				pose.translate(t.x() * side, t.y(), t.z());
			}
			if (t.sizes() && s.bool(ViewModel.ANIMATIONS) && s.bool(ViewModel.SWING_SCALE)) {
				sizedAtRest = true;
				aboutRest(pose, t, side, ITEM_X, ITEM_Y, ITEM_Z, false);
			}
		} catch (RuntimeException | LinkageError e) {
			current = null;
			GUARD.fail(e);
		}
	}

	/** {@code t}'s turn ({@code turn}) and size about the resting place ({@code rx}, {@code ry}, {@code rz}), view axes. */
	private static void aboutRest(PoseStack pose, HeldTransform t, float side, float rx, float ry, float rz, boolean turn) {
		pose.translate(rx * side, ry, rz);
		if (turn) {
			rotate(pose, t, side);
		}
		size(pose, t);
		pose.translate(-rx * side, -ry, -rz);
	}

	/** Whether {@link #shape} has anything to do for the item being drawn (else it is drawn as it is). */
	public static boolean shapes() {
		HeldTransform t = current;
		return t != null && GUARD.on() && (t.turns() || t.sizes() && !sizedAtRest);
	}

	/** Just before the item is drawn: its turn and size, about itself. */
	public static void shape(PoseStack pose, InteractionHand hand) {
		HeldTransform t = current;
		if (t == null) {
			return;
		}
		try {
			rotate(pose, t, side(hand));
			if (!sizedAtRest) {
				size(pose, t);
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	private static void rotate(PoseStack pose, HeldTransform t, float side) {
		if (t.turns()) {
			com.flintclient.client.compat.McCompat.rotateDegrees(pose, Axis.XP, t.rotX());
			com.flintclient.client.compat.McCompat.rotateDegrees(pose, Axis.YP, t.rotY() * side);
			com.flintclient.client.compat.McCompat.rotateDegrees(pose, Axis.ZP, t.rotZ() * side);
		}
	}

	private static void size(PoseStack pose, HeldTransform t) {
		if (t.sizes()) {
			// A zero axis would flatten the item to nothing (and break its lighting): the least is a hundredth.
			pose.scale(Math.max(0.01F, t.scaleX()), Math.max(0.01F, t.scaleY()), Math.max(0.01F, t.scaleZ()));
		}
	}

	/** 1 for the hand on the right of the screen, -1 for the left. */
	private static float side(InteractionHand hand) {
		HumanoidArm main = Minecraft.getInstance().options.mainHand().get();
		HumanoidArm arm = hand == InteractionHand.MAIN_HAND ? main : main.getOpposite();
		return arm == HumanoidArm.RIGHT ? 1 : -1;
	}

	/** The categories {@code item} is in ({@link HeldItems.Category#bit}), from its tags and kind; asked once per item. */
	static int categories(Item item) {
		ItemStack stack = item.getDefaultInstance();
		int out = 0;
		if (stack.is(ItemTags.SWORDS)) {
			out |= HeldItems.Category.SWORDS.bit();
		}
		if (stack.is(ItemTags.AXES)) {
			out |= HeldItems.Category.AXES.bit();
		}
		if (stack.is(ItemTags.PICKAXES)) {
			out |= HeldItems.Category.PICKAXES.bit();
		}
		if (stack.is(ItemTags.SHOVELS)) {
			out |= HeldItems.Category.SHOVELS.bit();
		}
		if (stack.is(ItemTags.HOES)) {
			out |= HeldItems.Category.HOES.bit();
		}
		if (stack.is(Items.BOW) || stack.is(Items.CROSSBOW)) {
			out |= HeldItems.Category.BOWS.bit();
		}
		if (item instanceof ShieldItem) {
			out |= HeldItems.Category.SHIELDS.bit();
		}
		if (stack.has(DataComponents.FOOD)) {
			out |= HeldItems.Category.FOOD.bit();
		}
		if (item instanceof PotionItem) {
			out |= HeldItems.Category.POTIONS.bit();
		}
		if (HeldItems.THROWABLES.contains(Mc263Items.id(item))) {
			out |= HeldItems.Category.THROWABLES.bit();
		}
		if (item instanceof BlockItem) {
			out |= HeldItems.Category.BLOCKS.bit();
		}
		if (stack.is(ItemTags.HEAD_ARMOR) || stack.is(ItemTags.CHEST_ARMOR) || stack.is(ItemTags.LEG_ARMOR) || stack.is(ItemTags.FOOT_ARMOR)) {
			out |= HeldItems.Category.ARMOR.bit();
		}
		return out;
	}

	// ---------- Animations ----------

	/** How many ticks {@code entity}'s swing takes: yours with Item Customizer's swing speed, Haste and Mining Fatigue. */
	public static int swingTicks(LivingEntity entity, int ticks) {
		if (!on() || entity != Minecraft.getInstance().player) {
			return ticks;
		}
		try {
			int haste = MobEffectUtil.hasDigSpeed(entity) ? 1 + MobEffectUtil.getDigSpeedAmplification(entity) : 0;
			var fatigue = entity.getEffect(MobEffects.MINING_FATIGUE);
			return HeldItems.swingTicks(Mods.state(VIEW_MODEL), ticks, haste, fatigue == null ? 0 : 1 + fatigue.getAmplifier());
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return ticks;
		}
	}

	/** Whether food and drinks turn on their way to your mouth (not with Rotationless Eat & Drink). */
	public static boolean eatRotation() {
		if (!on()) {
			return true;
		}
		ModuleState s = Mods.state(VIEW_MODEL);
		return !(s.bool(ViewModel.ANIMATIONS) && s.bool(ViewModel.ROTATIONLESS_EAT));
	}

	/** Whether held items never dip out of view (Cancel Re-Equip). */
	public static boolean cancelReEquip() {
		if (!on()) {
			return false;
		}
		ModuleState s = Mods.state(VIEW_MODEL);
		return s.bool(ViewModel.ANIMATIONS) && s.bool(ViewModel.CANCEL_REEQUIP);
	}

	// ---------- Dropped Item Size ----------

	/** {@code stack}'s size on the ground from Dropped Item Size (1 when it's off). */
	public static float dropped(ItemStack stack) {
		if (!on() || stack.isEmpty()) {
			return 1;
		}
		try {
			return LOOKUP.dropped(Mods.state(VIEW_MODEL), stack.getItem());
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return 1;
		}
	}
}
