package com.flintclient.legacy.mods;

import com.flintclient.client.control.feature.HeldItems;
import com.flintclient.client.control.model.HeldTransform;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.SettingDescriptor;
import com.flintclient.client.control.model.SettingSection;
import com.flintclient.client.control.model.mods.VisualMods.ViewModel;
import com.flintclient.legacy.module.Module;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.entity.living.LivingEntity;
import net.minecraft.entity.living.effect.StatusEffect;
import net.minecraft.entity.living.effect.StatusEffectInstance;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.AxeItem;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BowItem;
import net.minecraft.item.FoodItem;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.PotionItem;
import net.minecraft.item.ShovelItem;
import net.minecraft.item.SwordItem;
import org.jspecify.annotations.Nullable;

/**
 * Item Customizer on 1.8.9 (the shared view_model module): where your held item and your empty hand sit in first person (every
 * item's place, a category's or an item's own), your swing (speed, Haste, Mining Fatigue, Swing Scale), eating without the
 * turn, no re-equip dip, and dropped items' size. The same rules as every line ({@link HeldItems}); its page is the shared
 * panel. 1.8.9 has one hand and no shields, so there is no other hand to mirror and no Shields category. Drawn by
 * ItemInHandRendererMixin, ItemEntityRendererMixin and LivingEntityMixin; allocation-free per frame.
 */
public final class ItemCustomizer extends Module {
	/** Where 1.8.9 rests a held item and the empty arm, in view space. */
	private static final float ITEM_X = 0.56F;
	private static final float ITEM_Y = -0.52F;
	private static final float ITEM_Z = -0.72F;
	private static final float ARM_X = 0.64F;
	private static final float ARM_Y = -0.6F;
	private static final float ARM_Z = -0.72F;
	private final Map<Item, String> ids = new IdentityHashMap<Item, String>();
	private final HeldItems.Lookup lookup = new HeldItems.Lookup(new HeldItems.Facts() {
		@Override
		public String id(Object item) {
			return idOf((Item) item);
		}

		@Override
		public int categories(Object item) {
			return categoriesOf((Item) item);
		}
	});
	private @Nullable HeldTransform current;
	private boolean sizedAtRest;

	public ItemCustomizer() {
		super(ViewModel.ID, "itemcustomizer");
		// Its page is the shared panel: every setting is read straight from the module's state.
		for (SettingSection section : descriptor.sections()) {
			if (!section.title().equals("Mod")) {
				for (SettingDescriptor<?> s : section.settings()) {
					reads(s);
				}
			}
		}
	}

	private String idOf(Item item) {
		String id = ids.get(item);
		if (id == null) {
			id = String.valueOf(Item.REGISTRY.getKey(item));
			ids.put(item, id);
		}
		return id;
	}

	private int categoriesOf(Item item) {
		int out = 0;
		if (item instanceof SwordItem) {
			out |= HeldItems.Category.SWORDS.bit();
		}
		if (item instanceof AxeItem) {
			out |= HeldItems.Category.AXES.bit();
		}
		if (item instanceof PickaxeItem) {
			out |= HeldItems.Category.PICKAXES.bit();
		}
		if (item instanceof ShovelItem) {
			out |= HeldItems.Category.SHOVELS.bit();
		}
		if (item instanceof HoeItem) {
			out |= HeldItems.Category.HOES.bit();
		}
		if (item instanceof BowItem) {
			out |= HeldItems.Category.BOWS.bit();
		}
		if (item instanceof FoodItem) {
			out |= HeldItems.Category.FOOD.bit();
		}
		if (item instanceof PotionItem) {
			out |= HeldItems.Category.POTIONS.bit();
		}
		if (HeldItems.THROWABLES.contains(idOf(item))) {
			out |= HeldItems.Category.THROWABLES.bit();
		}
		if (item instanceof BlockItem) {
			out |= HeldItems.Category.BLOCKS.bit();
		}
		if (item instanceof ArmorItem) {
			out |= HeldItems.Category.ARMOR.bit();
		}
		return out;
	}

	// ---------- first person ----------

	/** Right after the hand's matrix is pushed: the held item's (or the empty hand's) place, in view space. */
	public void place(@Nullable ItemStack stack) {
		current = null;
		sizedAtRest = false;
		if (!enabled()) {
			return;
		}
		ModuleState s = state();
		if (stack == null || stack.getItem() == null) {
			HeldTransform t = lookup.hand(s);
			if (t != null) {
				GlStateManager.translatef(t.x(), t.y(), t.z());
				GlStateManager.translatef(ARM_X, ARM_Y, ARM_Z);
				rotate(t);
				size(t);
				GlStateManager.translatef(-ARM_X, -ARM_Y, -ARM_Z);
			}
			return;
		}
		HeldTransform t = lookup.place(s, stack.getItem());
		if (t == null) {
			return;
		}
		current = t;
		if (t.moves()) {
			GlStateManager.translatef(t.x(), t.y(), t.z());
		}
		if (t.sizes() && s.bool(ViewModel.ANIMATIONS) && s.bool(ViewModel.SWING_SCALE)) {
			sizedAtRest = true;
			GlStateManager.translatef(ITEM_X, ITEM_Y, ITEM_Z);
			size(t);
			GlStateManager.translatef(-ITEM_X, -ITEM_Y, -ITEM_Z);
		}
	}

	/** Whether {@link #shape} has anything to do for the item being drawn. */
	public boolean shapes() {
		HeldTransform t = current;
		return t != null && (t.turns() || t.sizes() && !sizedAtRest);
	}

	/**
	 * Just before the item is drawn: its turn and size about itself, along the view's axes (1.8.9 holds an item turned 45° about
	 * the vertical, so the turn is made in front of that).
	 */
	public void shape() {
		HeldTransform t = current;
		if (t == null) {
			return;
		}
		GlStateManager.rotatef(-45.0F, 0.0F, 1.0F, 0.0F);
		rotate(t);
		if (!sizedAtRest) {
			size(t);
		}
		GlStateManager.rotatef(45.0F, 0.0F, 1.0F, 0.0F);
	}

	private static void rotate(HeldTransform t) {
		if (t.turns()) {
			GlStateManager.rotatef(t.rotX(), 1.0F, 0.0F, 0.0F);
			GlStateManager.rotatef(t.rotY(), 0.0F, 1.0F, 0.0F);
			GlStateManager.rotatef(t.rotZ(), 0.0F, 0.0F, 1.0F);
		}
	}

	private static void size(HeldTransform t) {
		if (t.sizes()) {
			GlStateManager.scalef(Math.max(0.01F, t.scaleX()), Math.max(0.01F, t.scaleY()), Math.max(0.01F, t.scaleZ()));
		}
	}

	// ---------- animations ----------

	/** How many ticks {@code entity}'s swing takes: yours with the swing speed, Haste and Mining Fatigue as chosen. */
	public int swingTicks(LivingEntity entity, int ticks) {
		if (!enabled() || entity != Minecraft.getInstance().player) {
			return ticks;
		}
		StatusEffectInstance haste = entity.hasStatusEffect(StatusEffect.HASTE) ? entity.getEffectInstance(StatusEffect.HASTE) : null;
		StatusEffectInstance fatigue = entity.hasStatusEffect(StatusEffect.MINING_FATIGUE) ? entity.getEffectInstance(StatusEffect.MINING_FATIGUE)
			: null;
		return HeldItems.swingTicks(state(), ticks, haste == null ? 0 : 1 + haste.getAmplifier(), fatigue == null ? 0 : 1 + fatigue.getAmplifier());
	}

	/** Whether food and drinks turn on their way to your mouth (not with Rotationless Eat & Drink). */
	public boolean eatRotation() {
		if (!enabled()) {
			return true;
		}
		ModuleState s = state();
		return !(s.bool(ViewModel.ANIMATIONS) && s.bool(ViewModel.ROTATIONLESS_EAT));
	}

	/** Whether the held item never dips out of view (Cancel Re-Equip). */
	public boolean cancelReEquip() {
		if (!enabled()) {
			return false;
		}
		ModuleState s = state();
		return s.bool(ViewModel.ANIMATIONS) && s.bool(ViewModel.CANCEL_REEQUIP);
	}

	// ---------- dropped items ----------

	/** {@code stack}'s size on the ground from Dropped Item Size (1 when it's off). */
	public float dropped(@Nullable ItemStack stack) {
		if (!enabled() || stack == null || stack.getItem() == null) {
			return 1;
		}
		return lookup.dropped(state(), stack.getItem());
	}
}
