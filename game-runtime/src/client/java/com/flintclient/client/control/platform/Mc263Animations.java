package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.compat.McCompat;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.ToolMods.Animations;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ArmedModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.HumanoidModel.ArmPose;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Animations' player animations, after Not Enough Animations (tr7zw): Flint's own code, written from how that mod behaves
 * (its licence doesn't let its code be reused). Each frame a player's model is set up, every animation that applies bids for
 * the body parts it moves; the highest bid wins each part. Arms and legs then ease towards their new pose, and the body can
 * follow the head. Held maps show their picture, lanterns swing, a bow hides the other hand's item. Fault-isolated.
 */
public final class Mc263Animations {
	private static final HookGuard GUARD = new HookGuard("player animations",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final int ANIMATIONS = Mods.index(Animations.ID);
	static final int LEFT_ARM = 0;
	static final int RIGHT_ARM = 1;
	static final int LEFT_LEG = 2;
	static final int RIGHT_LEG = 3;
	static final int BODY = 4;
	static final int HEAD = 5;
	private static final int PARTS = 6;
	private static final int ALL = 0b111111;
	private static final Identifier MAP_BACKGROUND = Identifier.withDefaultNamespace("textures/map/map_background.png");
	private static final Identifier MAP_CHECKERBOARD = Identifier.withDefaultNamespace("textures/map/map_background_checkerboard.png");
	private static final int FULL_BRIGHT = 15728880;

	/** Implemented by every player (PlayerMixin): its animation memory. */
	public interface Holder {
		Data flint$animations();
	}

	/** What a player's animations remember between frames. */
	public static final class Data {
		int armsUpdated;
		final float[] last = new float[45];
		@Nullable
		ItemStack lastMain;
		@Nullable
		ItemStack lastOff;
		int swapTimer;
		int lastSwapTick = -1;
		boolean disableBodyRotation;
		boolean rotateBodyToHead;
		boolean falling;
		double fallLastY;
		float fallSpeed;
		boolean lantern;
		int lanternLastTick;
		Vec3 lanternPos = Vec3.ZERO;
		Vec3 lanternVelocity = Vec3.ZERO;
		Vec3 lastLanternVelocity = Vec3.ZERO;
		@Nullable
		Vec3 smoothedHandPos;

		/** Each player tick: the body-to-head request lasts one tick. */
		public void tick() {
			rotateBodyToHead = false;
		}

		/** Head turning skips a tick while the body is snapped to a ladder. */
		public boolean consumeBodyLock() {
			if (disableBodyRotation) {
				disableBodyRotation = false;
				return true;
			}
			return false;
		}
	}

	private static int tickId;
	private static int parsed = -1;
	private static Set<Item> holding = Set.of();
	private static Set<Item> lanterns = Set.of();
	private static Set<Item> maps = Set.of();
	private static Set<Item> bows = Set.of();
	/** While a crouching climber is drawn standing, the animations see it standing too. */
	private static boolean standingOnLadder;

	private Mc263Animations() {
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	/** Every client tick the world ticks: the animations' clock (arms ease once per tick). */
	public static void tick() {
		Minecraft mc = mc();
		if (mc.level != null && !mc.isPaused()) {
			tickId++;
		}
	}

	private static @Nullable AbstractClientPlayer player(AvatarRenderState state) {
		return ((EntityRef) state).flint$entity() instanceof AbstractClientPlayer p ? p : null;
	}

	private static Data data(Player player) {
		return ((Holder) player).flint$animations();
	}

	private static void lists(ModuleState s) {
		if (parsed == s.revision()) {
			return;
		}
		parsed = s.revision();
		holding = items(s.ids(Animations.HOLDING_ITEMS));
		lanterns = items(s.ids(Animations.LANTERN_ITEMS));
		maps = items(s.ids(Animations.MAP_ITEMS));
		bows = items(s.ids(Animations.BOW_ITEMS));
	}

	/** The items a list names that exist here (an unknown id is left out). */
	private static Set<Item> items(List<String> ids) {
		Set<Item> out = new HashSet<>();
		for (String id : ids) {
			Identifier key = Identifier.tryParse(id);
			if (key != null) {
				BuiltInRegistries.ITEM.getOptional(key).filter(item -> item != Items.AIR).ifPresent(out::add);
			}
		}
		return out;
	}

	// ---------- the model hooks ----------

	/** Before a player's model is set up: a crouching climber, and a glider, are drawn standing. */
	public static void before(AvatarRenderState state) {
		standingOnLadder = false;
		if (!GUARD.on() || !Mods.on(ANIMATIONS)) {
			return;
		}
		try {
			AbstractClientPlayer player = player(state);
			if (player == null || mc().level == null) {
				return;
			}
			ModuleState s = Mods.state(ANIMATIONS);
			if (s.bool(Animations.LADDER) && player.isCrouching() && LADDER.valid(player, data(player), s, state)) {
				standingOnLadder = true;
				state.isCrouching = false;
			}
			if (player.getPose() == Pose.FALL_FLYING) {
				state.isCrouching = false;
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** After a player's model is set up: the animations, the smoothing and the body following the head. */
	public static void after(AvatarRenderState state, PlayerModel model) {
		if (!GUARD.on() || !Mods.on(ANIMATIONS)) {
			standingOnLadder = false;
			return;
		}
		try {
			AbstractClientPlayer player = player(state);
			if (player == null || mc().level == null) {
				return;
			}
			ModuleState s = Mods.state(ANIMATIONS);
			lists(s);
			Data d = data(player);
			float delta = mc().getDeltaTracker().getGameTimeDeltaPartialTick(false);
			animate(player, d, model, state, delta, state.walkAnimationPos, s);
			int passed = Math.abs(tickId - d.armsUpdated);
			boolean newTick = passed != 0;
			float timePassed = passed * 50.0F;
			if (s.bool(Animations.SMOOTHING)) {
				float speed = s.decimal(Animations.SMOOTHING_SPEED);
				ease(model.leftArm, d.last, 0, timePassed, newTick, speed, delta);
				ease(model.rightArm, d.last, 9, timePassed, newTick, speed, delta);
				if (!s.bool(Animations.NO_LEG_SMOOTHING)) {
					ease(model.leftLeg, d.last, 18, timePassed, newTick, speed, delta);
					ease(model.rightLeg, d.last, 27, timePassed, newTick, speed, delta);
				}
			}
			lockRotation(player, d, s, timePassed, newTick);
			d.armsUpdated = tickId;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		} finally {
			standingOnLadder = false;
		}
	}

	/** Each part goes to the highest-priority animation that wants it. */
	private static void animate(AbstractClientPlayer player, Data d, PlayerModel model, AvatarRenderState state, float delta, float swing, ModuleState s) {
		int[] priorities = new int[PARTS];
		Anim[] winners = new Anim[PARTS];
		for (Anim anim : ANIMS) {
			if (!anim.enabled(s) || !anim.valid(player, d, s, state)) {
				continue;
			}
			int priority = anim.priority();
			if (priority <= 0) {
				continue;
			}
			int parts = anim.parts(player, d, s);
			for (int part = 0; part < PARTS; part++) {
				if ((parts & 1 << part) != 0 && priority > priorities[part]) {
					priorities[part] = priority;
					winners[part] = anim;
				}
			}
		}
		for (int part = 0; part < PARTS; part++) {
			Anim anim = winners[part];
			if (anim != null) {
				if (!anim.prepared) {
					anim.prepare(player, d, model, state, delta, swing);
					anim.prepared = true;
				}
				anim.apply(player, d, model, part, delta, swing, s);
			}
		}
		for (Anim anim : winners) {
			if (anim != null) {
				anim.prepared = false;
			}
		}
	}

	/** Arms and legs ease towards the pose each tick, and are drawn between ticks (Not Enough Animations' smoothing). */
	private static void ease(ModelPart part, float[] last, int at, float timePassed, boolean newTick, float speed, float delta) {
		if (timePassed > 50.0F) {
			last[at] = last[at + 3] = last[at + 6] = part.xRot;
			last[at + 1] = last[at + 4] = last[at + 7] = part.yRot;
			last[at + 2] = last[at + 5] = last[at + 8] = part.zRot;
			clean(last, at);
		} else if (!newTick) {
			last[at + 6] = lerpAngle(delta, last[at + 3], last[at]);
			last[at + 7] = lerpAngle(delta, last[at + 4], last[at + 1]);
			last[at + 8] = lerpAngle(delta, last[at + 5], last[at + 2]);
			part.xRot = last[at + 6];
			part.yRot = last[at + 7];
			part.zRot = last[at + 8];
		} else {
			last[at + 3] = last[at] = last[at + 6];
			last[at + 4] = last[at + 1] = last[at + 7];
			last[at + 5] = last[at + 2] = last[at + 8];
			float amount = Math.clamp(speed, 0.0F, 1.0F);
			last[at] = interpolateRotation(part.xRot, last[at], amount);
			last[at + 1] = interpolateRotation(part.yRot, last[at + 1], amount);
			last[at + 2] = interpolateRotation(part.zRot, last[at + 2], amount);
			clean(last, at);
			last[at + 6] = lerpAngle(delta, last[at + 3], last[at]);
			last[at + 7] = lerpAngle(delta, last[at + 4], last[at + 1]);
			last[at + 8] = lerpAngle(delta, last[at + 5], last[at + 2]);
			part.xRot = last[at + 6];
			part.yRot = last[at + 7];
			part.zRot = last[at + 8];
		}
	}

	private static void clean(float[] data, int at) {
		for (int i = at; i < at + 3; i++) {
			if (Float.isNaN(data[i])) {
				data[i] = 0.0F;
			}
		}
	}

	/** Rotation lock: your body follows your head (always, smoothly, or while eating, drinking or blocking). */
	private static void lockRotation(AbstractClientPlayer player, Data d, ModuleState s, float timePassed, boolean newTick) {
		boolean camera = player == mc().getCameraEntity();
		if (!camera && !s.bool(Animations.LOCK_EVERYONE) || d.disableBodyRotation) {
			return;
		}
		if (camera && s.bool(Animations.LOCK_FIRST_PERSON) && mc().options.getCameraType() != CameraType.FIRST_PERSON) {
			return;
		}
		int lock = s.option(Animations.ROTATION_LOCK);
		float[] last = d.last;
		if ((lock == 2 || lock == 0 && d.rotateBodyToHead) && player.getVehicle() == null) {
			if (!newTick) {
				player.yBodyRot = last[36];
				player.yBodyRotO = last[37];
			} else if (timePassed > 50.0F) {
				last[36] = player.yHeadRot;
			} else {
				float speed = 0.5F;
				if (Math.abs(wrapDegrees2(player.yHeadRot - last[36])) > 90.0F) {
					speed *= 0.9F;
				}
				last[37] = last[36];
				player.yBodyRotO = last[36];
				last[36] = interpolateRotation2(last[36], player.yHeadRot, Math.min(speed, 1.0F));
				player.yBodyRot = last[36];
			}
		} else if (lock == 1 && player.getVehicle() == null && newTick) {
			player.yBodyRot = player.yHeadRot;
			player.yBodyRotO = player.yHeadRotO;
		} else if (newTick) {
			last[36] = player.yBodyRot;
			last[37] = player.yBodyRotO;
		}
	}

	/** How far a player's head turns before the body follows ({@code null}: Minecraft's own). */
	public static @Nullable Float maxHeadRotation(Player player) {
		if (!GUARD.on() || !Mods.on(ANIMATIONS) || !player.level().isClientSide()) {
			return null;
		}
		ModuleState s = Mods.state(ANIMATIONS);
		return player.isBlocking() ? s.decimal(Animations.MAX_BLOCKING_ANGLE) : s.decimal(Animations.MAX_NORMAL_ANGLE);
	}

	// ---------- held items ----------

	/**
	 * Before a held item is drawn: a sleeping holder holds nothing, a map shows its picture, a lantern swings, a bow hides the
	 * other hand's item. True when Flint drew it (or nothing should be drawn) instead of Minecraft.
	 */
	public static boolean heldItem(ArmedEntityRenderState state, EntityModel<?> model, HumanoidArm arm, PoseStack pose, SubmitNodeCollector collector,
		int light) {
		if (!GUARD.on() || !Mods.on(ANIMATIONS)) {
			return false;
		}
		try {
			LivingEntity entity = ((EntityRef) state).flint$entity();
			if (entity == null) {
				return false;
			}
			ItemStack stack = entity.getItemHeldByArm(arm);
			ModuleState s = Mods.state(ANIMATIONS);
			lists(s);
			if (entity.isSleeping()) {
				return s.bool(Animations.NO_SLEEP_ITEMS);
			}
			if (stack.has(DataComponents.CUSTOM_MODEL_DATA)) {
				return false;
			}
			if (model instanceof ArmedModel<?> && model instanceof HumanoidModel<?> humanoid
				&& (arm == HumanoidArm.RIGHT && humanoid.rightArm.visible || arm == HumanoidArm.LEFT && humanoid.leftArm.visible)) {
				@SuppressWarnings("unchecked")
				ArmedModel<ArmedEntityRenderState> armed = (ArmedModel<ArmedEntityRenderState>) model;
				if (s.bool(Animations.MAP)) {
					boolean left = arm == HumanoidArm.LEFT;
					if (arm == entity.getMainArm() && entity.getMainHandItem().is(Items.FILLED_MAP)) {
						pose.pushPose();
						armed.translateToHand(state, arm, pose);
						McCompat.rotateDegrees(pose, Axis.XP, -90.0F);
						McCompat.rotateDegrees(pose, Axis.YP, 205.0F);
						McCompat.rotateDegrees(pose, Axis.ZP, 10.0F);
						pose.translate((left ? -1 : 1) / 16.0F, 0.09 + (entity.getOffhandItem().isEmpty() ? 0.15 : 0.0), -0.625);
						map(pose, collector, light, stack, !entity.getOffhandItem().isEmpty(), entity.getMainArm() == HumanoidArm.LEFT);
						pose.popPose();
						return true;
					}
					if (arm != entity.getMainArm() && entity.getOffhandItem().is(Items.FILLED_MAP)) {
						pose.pushPose();
						armed.translateToHand(state, arm, pose);
						McCompat.rotateDegrees(pose, Axis.XP, -90.0F);
						McCompat.rotateDegrees(pose, Axis.YP, 200.0F);
						pose.translate((left ? -1 : 1) / 16.0F, 0.125, -0.625);
						map(pose, collector, light, stack, true, false);
						pose.popPose();
						return true;
					}
				}
				if (s.bool(Animations.LANTERNS) && entity instanceof Player player && lanterns.contains(stack.getItem())) {
					lantern(player, stack, arm, pose, collector, state, armed);
					return true;
				}
			}
			return s.bool(Animations.OFFHAND_HIDING) && entity instanceof AbstractClientPlayer player && hideForBow(player, arm);
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return false;
		}
	}

	/** While a bow or crossbow is drawn (or a crossbow charged), the other hand's item is hidden. */
	private static boolean hideForBow(AbstractClientPlayer player, HumanoidArm arm) {
		ItemStack main = player.getMainHandItem();
		ItemStack off = player.getOffhandItem();
		if (main.getItem() instanceof ShieldItem || hasUsePriority(main)) {
			return false;
		}
		boolean mainBow = main.getItem() instanceof ProjectileWeaponItem || bows.contains(main.getItem());
		boolean offBow = off.getItem() instanceof ProjectileWeaponItem || bows.contains(off.getItem());
		boolean bowEquipped = mainBow || offBow;
		boolean mainCharged = CrossbowItem.isCharged(main);
		boolean offCharged = CrossbowItem.isCharged(off);
		boolean using = player.isUsingItem();
		if (!mainCharged && using) {
			mainCharged = (float) (main.getUseDuration(player) - player.getUseItemRemainingTicks()) / CrossbowItem.getChargeDuration(main, player) >= 1.0F;
		}
		if (!offCharged && using) {
			offCharged = (float) (off.getUseDuration(player) - player.getUseItemRemainingTicks()) / CrossbowItem.getChargeDuration(off, player) >= 1.0F;
		}
		ArmPose mainPose = armPose(player, InteractionHand.MAIN_HAND);
		ArmPose offPose = armPose(player, InteractionHand.OFF_HAND);
		if (!bothHands(mainPose) && !bothHands(offPose) && (!bowEquipped || !mainCharged && !offCharged && !using)) {
			return false;
		}
		if (mainPose.isTwoHanded()) {
			offPose = off.isEmpty() ? ArmPose.EMPTY : ArmPose.ITEM;
		}
		HumanoidArm mainArm = player.getMainArm() == HumanoidArm.LEFT ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
		HumanoidArm offArm = mainArm.getOpposite();
		if (arm == mainArm && bothHands(offPose) || arm == offArm && bothHands(mainPose)) {
			return true;
		}
		if (bowEquipped && (mainCharged || offCharged || using) && (!mainCharged && !offCharged || !using) && !bothHands(mainPose) && !bothHands(offPose)) {
			return arm == mainArm && offBow && !mainBow || arm == offArm && mainBow;
		}
		return false;
	}

	/** A held map's picture in the world, as Minecraft draws it in first person. */
	private static void map(PoseStack pose, SubmitNodeCollector collector, int light, ItemStack stack, boolean small, boolean leftHanded) {
		Minecraft mc = mc();
		if (small) {
			McCompat.rotateDegrees(pose, Axis.YP, 160.0F);
			McCompat.rotateDegrees(pose, Axis.ZP, 180.0F);
			pose.scale(0.38F, 0.38F, 0.38F);
			pose.translate(-0.1, -1.2, 0.0);
			pose.scale(0.0098125F, 0.0098125F, 0.0098125F);
		} else {
			if (leftHanded) {
				McCompat.rotateDegrees(pose, Axis.YP, 154.5F);
				McCompat.rotateDegrees(pose, Axis.ZP, 166.5F);
				pose.scale(0.38F, 0.38F, 0.38F);
				pose.translate(0.585, -1.225, 0.15);
			} else {
				McCompat.rotateDegrees(pose, Axis.YP, 155.0F);
				McCompat.rotateDegrees(pose, Axis.ZP, 213.5F);
				pose.scale(0.38F, 0.38F, 0.38F);
				pose.translate(-0.955, -1.8, 0.0);
			}
			pose.scale(0.0138125F, 0.0138125F, 0.0138125F);
		}
		MapId id = stack.get(DataComponents.MAP_ID);
		MapItemSavedData data = MapItem.getSavedData(stack, mc.level);
		RenderType background = RenderTypes.text(data == null ? MAP_BACKGROUND : MAP_CHECKERBOARD);
		collector.submitCustomGeometry(pose, background, (p, vc) -> {
			vc.addVertex(p, -7.0F, 135.0F, 0.0F).setColor(-1).setUv(0.0F, 1.0F).setLight(light);
			vc.addVertex(p, 135.0F, 135.0F, 0.0F).setColor(-1).setUv(1.0F, 1.0F).setLight(light);
			vc.addVertex(p, 135.0F, -7.0F, 0.0F).setColor(-1).setUv(1.0F, 0.0F).setLight(light);
			vc.addVertex(p, -7.0F, -7.0F, 0.0F).setColor(-1).setUv(0.0F, 0.0F).setLight(light);
		});
		collector.submitCustomGeometry(pose, RenderTypes.text(MAP_BACKGROUND), (p, vc) -> {
			vc.addVertex(p, -7.0F, -7.0F, 0.0F).setColor(-1).setUv(0.0F, 0.0F).setLight(light);
			vc.addVertex(p, 135.0F, -7.0F, 0.0F).setColor(-1).setUv(1.0F, 0.0F).setLight(light);
			vc.addVertex(p, 135.0F, 135.0F, 0.0F).setColor(-1).setUv(1.0F, 1.0F).setLight(light);
			vc.addVertex(p, -7.0F, 135.0F, 0.0F).setColor(-1).setUv(0.0F, 1.0F).setLight(light);
		});
		if (data != null && id != null) {
			MapRenderState render = new MapRenderState();
			mc.getMapRenderer().extractRenderState(id, data, render);
			mc.getMapRenderer().render(render, pose, collector, false, light);
		}
	}

	/** A held lantern hangs from the hand and swings on a spring as the player moves. */
	private static void lantern(Player player, ItemStack stack, HumanoidArm arm, PoseStack pose, SubmitNodeCollector collector,
		ArmedEntityRenderState state, ArmedModel<ArmedEntityRenderState> armed) {
		Data d = data(player);
		pose.pushPose();
		armed.translateToHand(state, arm, pose);
		pose.scale(0.6F, 0.6F, 0.6F);
		if (!d.lantern) {
			d.lantern = true;
			d.lanternLastTick = player.tickCount;
			d.lanternPos = new Vec3(player.getX(), player.getY(), player.getZ());
		}
		float chain = 0.5F;
		float chainY = 0.6F;
		pose.translate(arm == HumanoidArm.LEFT ? -0.45 : -0.5, 0.4, -0.3);
		pose.translate(chain, chainY, chain);
		Vector4f origin = new Vector4f(0.0F, 0.0F, 0.0F, 1.0F).mul(pose.last().pose());
		Vec3 raw = McCompat.mainCamera().position().add(origin.x, origin.y, origin.z);
		if (d.smoothedHandPos == null) {
			d.smoothedHandPos = raw;
		}
		if (!(d.smoothedHandPos.distanceToSqr(raw) > 4.0)) {
			double alpha = 0.3;
			d.smoothedHandPos = new Vec3(Mth.lerp(alpha, d.smoothedHandPos.x, raw.x), Mth.lerp(alpha, d.smoothedHandPos.y, raw.y),
				Mth.lerp(alpha, d.smoothedHandPos.z, raw.z));
		}
		Vec3 hand = d.smoothedHandPos;
		McCompat.rotateDegrees(pose, Axis.XP, -90.0F);
		float yaw = player.getYRot() * (float) (Math.PI / 180.0);
		float pitch = player.getXRot() * (float) (Math.PI / 180.0);
		Vec3 forward = new Vec3(-Mth.sin(yaw), 0.0, Mth.cos(yaw)).normalize();
		Vec3 right = new Vec3(forward.z, 0.0, -forward.x);
		float delta = mc().getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Vec3 velocity = d.lastLanternVelocity.add(d.lanternVelocity.subtract(d.lastLanternVelocity).multiply(delta, delta, delta));
		double forwardVel = velocity.dot(forward) - velocity.y * Mth.cos(pitch) * 0.6;
		double rightVel = velocity.dot(right);
		float swingX = Mth.clamp((float) forwardVel * 90.0F, -90.0F, 90.0F) - player.getXRot() * 0.25F;
		float swingZ = Mth.clamp((float) -rightVel * 90.0F, -90.0F, 90.0F);
		Vec3 displacement = d.lanternPos.subtract(hand);
		Vec3 acceleration = displacement.scale(-0.15);
		if (player.tickCount != d.lanternLastTick) {
			d.lanternLastTick = player.tickCount;
			d.lastLanternVelocity = d.lanternVelocity;
			if (displacement.lengthSqr() > 100.0) {
				d.lanternVelocity = acceleration;
				d.lanternPos = hand;
			} else {
				d.lanternVelocity = d.lanternVelocity.add(acceleration).scale(0.95);
				d.lanternPos = d.lanternPos.add(d.lanternVelocity);
			}
		}
		McCompat.rotateDegrees(pose, Axis.XP, swingX);
		McCompat.rotateDegrees(pose, Axis.ZP, swingZ);
		pose.translate(-chain, -chainY, -chain);
		McCompat.submitBlock(pose, collector, Block.byItem(stack.getItem()).defaultBlockState(), FULL_BRIGHT);
		pose.popPose();
	}

	// ---------- helpers (Not Enough Animations' maths) ----------

	static boolean swinging(LivingEntity player) {
		return McCompat.swinging(player);
	}

	static InteractionHand swingHand(LivingEntity player) {
		return McCompat.swingHand(player);
	}

	/** Whether {@code player} swings the arm that is {@code part}. */
	static boolean swingingArm(AbstractClientPlayer player, int part) {
		if (!swinging(player)) {
			return false;
		}
		return armOf(player, swingHand(player)) == part;
	}

	/** The body part (arm) holding {@code hand}. */
	static int armOf(LivingEntity player, InteractionHand hand) {
		boolean rightHanded = player.getMainArm() == HumanoidArm.RIGHT;
		return hand == InteractionHand.MAIN_HAND == rightHanded ? RIGHT_ARM : LEFT_ARM;
	}

	static ItemStack inArm(LivingEntity player, HumanoidArm arm) {
		return player.getItemInHand(player.getMainArm() == arm ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
	}

	static boolean bothHands(ArmPose pose) {
		return pose == ArmPose.BOW_AND_ARROW || pose == ArmPose.CROSSBOW_CHARGE || pose == ArmPose.CROSSBOW_HOLD;
	}

	static boolean hasUsePriority(ItemStack stack) {
		ItemUseAnimation use = stack.getUseAnimation();
		return use == ItemUseAnimation.TRIDENT || McCompat.spearUse(use);
	}

	/** The pose the arm holding {@code hand} takes for its item (Minecraft's own rules). */
	static ArmPose armPose(AbstractClientPlayer player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (stack.isEmpty()) {
			return ArmPose.EMPTY;
		}
		if (player.getUsedItemHand() == hand && player.getUseItemRemainingTicks() > 0) {
			ItemUseAnimation use = stack.getUseAnimation();
			if (use == ItemUseAnimation.BLOCK) {
				return ArmPose.BLOCK;
			}
			if (use == ItemUseAnimation.BOW) {
				return ArmPose.BOW_AND_ARROW;
			}
			if (use == ItemUseAnimation.TRIDENT) {
				return ArmPose.THROW_TRIDENT;
			}
			ArmPose spear = McCompat.spearPose(use);
			if (spear != null) {
				return spear;
			}
			if (use == ItemUseAnimation.SPYGLASS) {
				return ArmPose.SPYGLASS;
			}
			if (use == ItemUseAnimation.CROSSBOW) {
				return ArmPose.CROSSBOW_CHARGE;
			}
		} else if (!swinging(player) && stack.is(Items.CROSSBOW) && CrossbowItem.isCharged(stack)) {
			return ArmPose.CROSSBOW_HOLD;
		}
		return ArmPose.ITEM;
	}

	static void armTransforms(PlayerModel model, HumanoidArm arm, float pitch, float yaw, float roll) {
		ModelPart part = arm == HumanoidArm.RIGHT ? model.rightArm : model.leftArm;
		boolean mirror = arm == HumanoidArm.LEFT;
		part.xRot = pitch;
		part.yRot = mirror ? -yaw : yaw;
		part.zRot = mirror ? -roll : roll;
	}

	static void transforms(PlayerModel model, int bodyPart, float pitch, float yaw, float roll) {
		ModelPart part;
		boolean mirror = bodyPart == LEFT_ARM || bodyPart == LEFT_LEG;
		switch (bodyPart) {
			case LEFT_ARM -> part = model.leftArm;
			case RIGHT_ARM -> part = model.rightArm;
			case LEFT_LEG -> part = model.leftLeg;
			case RIGHT_LEG -> part = model.rightLeg;
			default -> {
				return;
			}
		}
		part.xRot = pitch;
		part.yRot = mirror ? -yaw : yaw;
		part.zRot = mirror ? -roll : roll;
	}

	static HumanoidArm armSide(int part) {
		return part == LEFT_ARM ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
	}

	static void minMaxHeadRotation(PlayerModel model) {
		float value = legacyWrap(model.head.yRot);
		float min = legacyWrap(model.body.yRot - (float) (Math.PI / 2));
		float max = legacyWrap(model.body.yRot + (float) (Math.PI / 2));
		model.head.yRot = Math.max(Math.min(value, max), min);
	}

	static float interpolateRotation(float start, float end, float amount) {
		float a = wrapRadians(start);
		float b = wrapRadians(end);
		float diff = b - a;
		if (diff > (float) Math.PI) {
			b -= (float) (Math.PI * 2);
		} else if (diff < (float) -Math.PI) {
			b += (float) (Math.PI * 2);
		}
		return wrapRadians(a + (b - a) * amount);
	}

	static float interpolateRotation2(float start, float end, float amount) {
		float a = wrapDegrees2(start);
		float b = wrapDegrees2(end);
		float diff = b - a;
		if (diff > 180.0F) {
			b -= 360.0F;
		} else if (diff < -180.0F) {
			b += 360.0F;
		}
		return wrapDegrees2(a + (b - a) * amount);
	}

	static float lerpAngle(float delta, float start, float end) {
		float a = wrapRadians(start);
		float b = wrapRadians(end);
		float shortest = (b - a + (float) Math.PI) % (float) (Math.PI * 2) - (float) Math.PI;
		return wrapRadians(a + shortest * delta);
	}

	static float wrapRadians(float angle) {
		return (angle + (float) Math.PI) % (float) (Math.PI * 2) - (float) Math.PI;
	}

	static float wrapDegrees2(float angle) {
		float wrapped = (angle + 180.0F) % 360.0F;
		if (wrapped < 0.0F) {
			wrapped += 360.0F;
		}
		return wrapped - 180.0F;
	}

	static float legacyWrap(float f) {
		float g = f % 6.283185F;
		if (g >= 3.1415925F) {
			g -= 6.283185F;
		}
		if (g < -3.1415925F) {
			g += 6.283185F;
		}
		return g;
	}

	private static boolean crouching(Player player) {
		return player.isCrouching() && !standingOnLadder;
	}

	/** What the player looks at within a block (petting and hugging). */
	private static @Nullable Entity lookedAt(AbstractClientPlayer player) {
		double reach = 1.0;
		Vec3 eye = player.getEyePosition(0.0F);
		Vec3 view = player.getViewVector(1.0F);
		Vec3 end = eye.add(view.x * reach, view.y * reach, view.z * reach);
		AABB box = player.getBoundingBox().expandTowards(view.scale(reach)).inflate(1.0, 1.0, 1.0);
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, end, box, e -> !e.isSpectator(), reach);
		return hit == null ? null : hit.getEntity();
	}

	// ---------- the animations ----------

	/** One animation: whether it applies, which parts it wants and how badly, and what it does to them. */
	private abstract static class Anim {
		boolean prepared;

		abstract boolean enabled(ModuleState s);

		abstract boolean valid(AbstractClientPlayer player, Data d, ModuleState s, AvatarRenderState state);

		abstract int parts(AbstractClientPlayer player, Data d, ModuleState s);

		abstract int priority();

		void prepare(AbstractClientPlayer player, Data d, PlayerModel model, AvatarRenderState state, float delta, float swing) {
		}

		abstract void apply(AbstractClientPlayer player, Data d, PlayerModel model, int part, float delta, float swing, ModuleState s);
	}

	/** Holds parts still without moving them: vanilla poses that must win (sleeping, swimming, using an item…). */
	private abstract static class Keep extends Anim {
		@Override
		boolean enabled(ModuleState s) {
			return true;
		}

		@Override
		void apply(AbstractClientPlayer player, Data d, PlayerModel model, int part, float delta, float swing, ModuleState s) {
		}
	}

	private static final class Crawling extends Anim {
		private float swimAmount;
		private float step;
		private float step2;
		private float m;
		private float n;

		@Override
		boolean enabled(ModuleState s) {
			return s.bool(Animations.CRAWLING);
		}

		@Override
		boolean valid(AbstractClientPlayer player, Data d, ModuleState s, AvatarRenderState state) {
			return player.getPose() == Pose.SWIMMING && !player.isInWater();
		}

		@Override
		int parts(AbstractClientPlayer player, Data d, ModuleState s) {
			return 1 << LEFT_ARM | 1 << RIGHT_ARM | 1 << LEFT_LEG | 1 << RIGHT_LEG;
		}

		@Override
		int priority() {
			return 350;
		}

		@Override
		void prepare(AbstractClientPlayer player, Data d, PlayerModel model, AvatarRenderState state, float delta, float swing) {
			swimAmount = state.swimAmount;
			// As in Not Enough Animations, the arm stroke moves on only while an arm swings (between swings the arms keep
			// the stroke they were at; before the first, Minecraft's own pose).
			if (swimAmount > 0.0F && swinging(player)) {
				float attack = McCompat.attackTime(state);
				step = swing * 2.5F % 26.0F;
				step2 = (step + 13.0F) % 26.0F;
				HumanoidArm attackArm = swingHand(player) == InteractionHand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
				m = attackArm == HumanoidArm.RIGHT && attack > 0.0F ? 0.0F : swimAmount;
				n = attackArm == HumanoidArm.LEFT && attack > 0.0F ? 0.0F : swimAmount;
			}
		}

		@Override
		void apply(AbstractClientPlayer player, Data d, PlayerModel model, int part, float delta, float swing, ModuleState s) {
			if (swimAmount > 0.0F) {
				if (part == RIGHT_ARM) {
					ModelPart arm = model.rightArm;
					if (step < 14.0F) {
						arm.xRot = Mth.lerp(m, arm.xRot, 0.0F);
						arm.yRot = Mth.lerp(m, arm.yRot, (float) Math.PI);
						arm.zRot = Mth.lerp(m, arm.zRot, (float) Math.PI - 1.8707964F * quadratic(step) / quadratic(14.0F));
					} else if (step < 24.0F) {
						float o = (step - 14.0F) / 10.0F;
						arm.xRot = Mth.lerp(m, arm.xRot, -0.3707964F * o);
						arm.yRot = Mth.lerp(m, arm.yRot, (float) Math.PI);
						arm.zRot = Mth.lerp(m, arm.zRot, 1.2707963F + 1.8707964F * o);
					} else {
						float p = (step - 24.0F) / 2.0F;
						arm.xRot = Mth.lerp(m, arm.xRot, -0.3707964F + 0.3707964F * p);
						arm.yRot = Mth.lerp(m, arm.yRot, (float) Math.PI);
						arm.zRot = Mth.lerp(m, arm.zRot, (float) Math.PI);
					}
				}
				if (part == LEFT_ARM) {
					ModelPart arm = model.leftArm;
					if (step2 < 14.0F) {
						arm.xRot = rotlerpRad(n, arm.xRot, 0.0F);
						arm.yRot = rotlerpRad(n, arm.yRot, (float) Math.PI);
						arm.zRot = rotlerpRad(n, arm.zRot, (float) Math.PI + 1.8707964F * quadratic(step2) / quadratic(14.0F));
					} else if (step2 < 24.0F) {
						float o = (step2 - 14.0F) / 10.0F;
						arm.xRot = rotlerpRad(n, arm.xRot, -0.3707964F * o);
						arm.yRot = rotlerpRad(n, arm.yRot, (float) Math.PI);
						arm.zRot = rotlerpRad(n, arm.zRot, 5.012389F - 1.8707964F * o);
					} else {
						float p = (step2 - 24.0F) / 2.0F;
						arm.xRot = rotlerpRad(n, arm.xRot, -0.3707964F + 0.3707964F * p);
						arm.yRot = rotlerpRad(n, arm.yRot, (float) Math.PI);
						arm.zRot = rotlerpRad(n, arm.zRot, (float) Math.PI);
					}
				}
			}
			float t = swing * 2.5F;
			if (part == LEFT_LEG) {
				model.leftLeg.xRot = Mth.lerp(swimAmount, model.leftLeg.xRot, 0.15F * Mth.cos(t * 0.33333334F + (float) Math.PI));
				model.leftLeg.zRot = -0.1507964F;
			}
			if (part == RIGHT_LEG) {
				model.rightLeg.xRot = Mth.lerp(swimAmount, model.rightLeg.xRot, 0.15F * Mth.cos(t * 0.33333334F));
				model.rightLeg.zRot = 0.1507964F;
			}
		}

		private static float rotlerpRad(float f, float g, float h) {
			float i = (h - g) % (float) (Math.PI * 2);
			if (i < (float) -Math.PI) {
				i += (float) (Math.PI * 2);
			}
			if (i >= (float) Math.PI) {
				i -= (float) (Math.PI * 2);
			}
			return g + f * i;
		}

		private static float quadratic(float f) {
			return -65.0F * f + f * f;
		}
	}

	/** Spyglass, trident and spear aiming keep Minecraft's pose for that arm (and the body). */
	private static final class VanillaSingleHanded extends Keep {
		private int target;

		@Override
		boolean valid(AbstractClientPlayer player, Data d, ModuleState s, AvatarRenderState state) {
			ArmPose right = armPose(player, player.getMainArm() == HumanoidArm.LEFT ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
			ArmPose left = armPose(player, player.getMainArm() == HumanoidArm.RIGHT ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
			target = single(left) ? 1 << LEFT_ARM | 1 << BODY : single(right) ? 1 << RIGHT_ARM | 1 << BODY : 0;
			return target != 0;
		}

		private static boolean single(ArmPose pose) {
			return pose == ArmPose.SPYGLASS || pose == ArmPose.THROW_TRIDENT || McCompat.spearArmPose(pose);
		}

		@Override
		int parts(AbstractClientPlayer player, Data d, ModuleState s) {
			return target;
		}

		@Override
		int priority() {
			return 3100;
		}
	}

	/** Drawing a bow and holding or charging a crossbow keep Minecraft's two-handed pose. */
	private static final class VanillaTwoHanded extends Keep {
		@Override
		boolean valid(AbstractClientPlayer player, Data d, ModuleState s, AvatarRenderState state) {
			return projectile(player, false);
		}

		@Override
		int parts(AbstractClientPlayer player, Data d, ModuleState s) {
			return 1 << LEFT_ARM | 1 << RIGHT_ARM | 1 << BODY;
		}

		@Override
		int priority() {
			return 3100;
		}
	}

	/** Whether either arm draws a bow ({@code bowOnly}) or any two-handed projectile pose. */
	private static boolean projectile(AbstractClientPlayer player, boolean bowOnly) {
		ArmPose right = armPose(player, player.getMainArm() == HumanoidArm.LEFT ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
		ArmPose left = armPose(player, player.getMainArm() == HumanoidArm.RIGHT ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
		return bowOnly ? right == ArmPose.BOW_AND_ARROW || left == ArmPose.BOW_AND_ARROW : bothHands(right) || bothHands(left);
	}

	private static boolean crossbowPose(AbstractClientPlayer player) {
		ArmPose right = armPose(player, player.getMainArm() == HumanoidArm.LEFT ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
		ArmPose left = armPose(player, player.getMainArm() == HumanoidArm.RIGHT ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
		return right == ArmPose.CROSSBOW_HOLD || right == ArmPose.CROSSBOW_CHARGE || left == ArmPose.CROSSBOW_HOLD || left == ArmPose.CROSSBOW_CHARGE;
	}

	/** Swapping the hands' items swings both arms in and out. */
	private static final class ItemSwap extends Anim {
		@Override
		boolean enabled(ModuleState s) {
			return s.bool(Animations.ITEM_SWAP);
		}

		@Override
		boolean valid(AbstractClientPlayer player, Data d, ModuleState s, AvatarRenderState state) {
			if (d.lastSwapTick != player.tickCount) {
				d.lastSwapTick = player.tickCount;
				ItemStack main = player.getMainHandItem();
				ItemStack off = player.getOffhandItem();
				if (d.lastMain == null || d.lastOff == null) {
					d.lastMain = main;
					d.lastOff = off;
				}
				if ((!main.isEmpty() || !off.isEmpty()) && d.lastMain.getItem() != d.lastOff.getItem() && d.lastMain.getItem() == off.getItem()
					&& d.lastOff.getItem() == main.getItem()) {
					d.swapTimer = 10;
				}
				d.lastMain = main;
				d.lastOff = off;
				if (d.swapTimer > 0) {
					d.swapTimer--;
				}
			}
			return d.swapTimer > 0;
		}

		@Override
		int parts(AbstractClientPlayer player, Data d, ModuleState s) {
			return 1 << LEFT_ARM | 1 << RIGHT_ARM;
		}

		@Override
		int priority() {
			return 3500;
		}

		@Override
		void apply(AbstractClientPlayer player, Data d, PlayerModel model, int part, float delta, float swing, ModuleState s) {
			int tick = d.swapTimer;
			float position = Mth.lerp(delta, (tick + 1) / 10.0F * -1.0F, tick / 10.0F * -1.0F);
			if (part == LEFT_ARM) {
				armTransforms(model, HumanoidArm.LEFT, -0.5F, 0.2F, position);
			}
			if (part == RIGHT_ARM) {
				armTransforms(model, HumanoidArm.RIGHT, -0.5F, 0.2F, position);
			}
		}
	}

	/** Holding up items (torches, clocks, compasses…) in front of you, pointed where you look. */
	private static final class LookAtItem extends Anim {
		private int target;

		@Override
		boolean enabled(ModuleState s) {
			return s.option(Animations.HOLD_UP_MODE) != 2 && !holding.isEmpty();
		}

		@Override
		boolean valid(AbstractClientPlayer player, Data d, ModuleState s, AvatarRenderState state) {
			if (s.bool(Animations.HOLD_UP_SELF) && player != mc().player) {
				return false;
			}
			int mode = s.option(Animations.HOLD_UP_MODE);
			boolean all = mode == 1;
			ItemStack right = inArm(player, HumanoidArm.RIGHT);
			ItemStack left = inArm(player, HumanoidArm.LEFT);
			boolean swingingNow = swinging(player);
			boolean rightArm = mode == 0 && holding.contains(right.getItem()) || mode == 3 && !holding.contains(right.getItem())
				|| all && !right.isEmpty() && (!swingingNow || player.getMainArm() != HumanoidArm.RIGHT);
			boolean leftArm = mode == 0 && holding.contains(left.getItem()) || mode == 3 && !holding.contains(left.getItem())
				|| all && !left.isEmpty() && (!swingingNow || player.getMainArm() != HumanoidArm.LEFT);
			InteractionHand rightHand = player.getMainArm() == HumanoidArm.LEFT ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
			InteractionHand leftHand = player.getMainArm() == HumanoidArm.RIGHT ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
			if (rightArm && leftArm && !swingingNow) {
				target = 1 << LEFT_ARM | 1 << RIGHT_ARM;
				return true;
			}
			if (rightArm && !(swingingNow && swingHand(player) == rightHand)) {
				target = 1 << RIGHT_ARM;
				return true;
			}
			if (leftArm && !(swingingNow && swingHand(player) == leftHand)) {
				target = 1 << LEFT_ARM;
				return true;
			}
			return false;
		}

		@Override
		int parts(AbstractClientPlayer player, Data d, ModuleState s) {
			return target;
		}

		@Override
		int priority() {
			return 300;
		}

		@Override
		void apply(AbstractClientPlayer player, Data d, PlayerModel model, int part, float delta, float swing, ModuleState s) {
			HumanoidArm arm = armSide(part);
			if (s.option(Animations.HOLD_UP_TARGET) == 0) {
				armTransforms(model, arm, -s.decimal(Animations.HOLD_UP_OFFSET) - Mth.lerp(-1.0F * (player.getXRot() - 90.0F) / 180.0F, 1.0F, 1.5F),
					-0.2F, 0.3F);
			} else {
				float invert = part == LEFT_ARM ? -1.0F : 1.0F;
				float offset = s.decimal(Animations.HOLD_UP_CAMERA_OFFSET);
				armTransforms(model, arm, Mth.clamp((float) (-Math.PI / 2) + model.head.xRot, -2.5F, 0.0F),
					Mth.clamp(offset + model.head.yRot * invert, -0.2F, Math.max(0.2F, offset)), 0.1F);
			}
		}
	}

	/** Sleeping: Minecraft's pose, the arms lying still with No sleep movement. */
	private static final class Sleep extends Anim {
		@Override
		boolean enabled(ModuleState s) {
			return true;
		}

		@Override
		boolean valid(AbstractClientPlayer player, Data d, ModuleState s, AvatarRenderState state) {
			return player.isSleeping();
		}

		@Override
		int parts(AbstractClientPlayer player, Data d, ModuleState s) {
			return ALL;
		}

		@Override
		int priority() {
			return 3600;
		}

		@Override
		void apply(AbstractClientPlayer player, Data d, PlayerModel model, int part, float delta, float swing, ModuleState s) {
			if (s.bool(Animations.NO_SLEEP_MOVEMENT)) {
				armTransforms(model, armSide(part), 0.0F, 0.0F, 0.0F);
			}
		}
	}

	/** Holding a map: both hands for a map held alone, one hand otherwise, raised with your pitch. */
	private static final class MapHolding extends Anim {
		private int target;

		@Override
		boolean enabled(ModuleState s) {
			return s.bool(Animations.MAP) || !maps.isEmpty();
		}

		@Override
		boolean valid(AbstractClientPlayer player, Data d, ModuleState s, AvatarRenderState state) {
			ItemStack main = player.getMainHandItem();
			ItemStack off = player.getOffhandItem();
			boolean rightHanded = player.getMainArm() == HumanoidArm.RIGHT;
			if (maps.contains(main.getItem()) && off.isEmpty()) {
				target = 1 << LEFT_ARM | 1 << RIGHT_ARM;
				return !main.has(DataComponents.CUSTOM_MODEL_DATA);
			}
			if (maps.contains(main.getItem())) {
				target = 1 << (rightHanded ? RIGHT_ARM : LEFT_ARM);
				return !main.has(DataComponents.CUSTOM_MODEL_DATA);
			}
			if (maps.contains(off.getItem()) && !off.isEmpty()) {
				target = 1 << (rightHanded ? LEFT_ARM : RIGHT_ARM);
				return !off.has(DataComponents.CUSTOM_MODEL_DATA);
			}
			return false;
		}

		@Override
		int parts(AbstractClientPlayer player, Data d, ModuleState s) {
			return target;
		}

		@Override
		int priority() {
			return 300;
		}

		@Override
		void apply(AbstractClientPlayer player, Data d, PlayerModel model, int part, float delta, float swing, ModuleState s) {
			HumanoidArm arm = armSide(part);
			float pitch = -1.0F * (player.getXRot() - 90.0F) / 180.0F;
			if (target == (1 << LEFT_ARM | 1 << RIGHT_ARM)) {
				armTransforms(model, arm, -Mth.lerp(pitch, 0.7F, 0.9F), Mth.lerp(pitch, -0.3F, -0.2F), 0.3F);
			} else {
				armTransforms(model, arm, -Mth.lerp(pitch, 0.5F, 1.5F), 0.0F, 0.3F);
			}
		}
	}

	/** Rowing: the first passenger's arms pull the oars in time with them. */
	private static final class Boat extends Anim {
		@Override
		boolean enabled(ModuleState s) {
			return s.bool(Animations.BOAT);
		}

		@Override
		boolean valid(AbstractClientPlayer player, Data d, ModuleState s, AvatarRenderState state) {
			return player.isPassenger() && player.getVehicle() instanceof AbstractBoat;
		}

		@Override
		int parts(AbstractClientPlayer player, Data d, ModuleState s) {
			return 1 << LEFT_ARM | 1 << RIGHT_ARM;
		}

		@Override
		int priority() {
			return 1500;
		}

		@Override
		void apply(AbstractClientPlayer player, Data d, PlayerModel model, int part, float delta, float swing, ModuleState s) {
			if (part == BODY || swingingArm(player, part) || !(player.getVehicle() instanceof AbstractBoat boat)) {
				return;
			}
			HumanoidArm arm = armSide(part);
			if (boat.getPassengers().indexOf(player) == 0) {
				float paddle = boat.getRowingTime(arm == HumanoidArm.LEFT ? 0 : 1, delta);
				armTransforms(model, arm, -1.1F - Mth.sin(paddle) * 0.3F, 0.2F, 0.3F);
			}
		}
	}

	/** Riding a saddled horse: the arms hold the reins (and, with the leg animation, the legs grip). */
	private static final class Horse extends Anim {
		@Override
		boolean enabled(ModuleState s) {
			return s.bool(Animations.HORSE);
		}

		@Override
		boolean valid(AbstractClientPlayer player, Data d, ModuleState s, AvatarRenderState state) {
			return player.isPassenger() && player.getVehicle() instanceof AbstractHorse horse && horse.isSaddled();
		}

		@Override
		int parts(AbstractClientPlayer player, Data d, ModuleState s) {
			int arms = 1 << LEFT_ARM | 1 << RIGHT_ARM | 1 << BODY;
			return s.bool(Animations.HORSE_LEGS) ? arms | 1 << LEFT_LEG | 1 << RIGHT_LEG : arms;
		}

		@Override
		int priority() {
			return 1500;
		}

		@Override
		void apply(AbstractClientPlayer player, Data d, PlayerModel model, int part, float delta, float swing, ModuleState s) {
			if (part == BODY || (part == LEFT_ARM || part == RIGHT_ARM) && swingingArm(player, part)
				|| !(player.getVehicle() instanceof AbstractHorse horse)) {
				return;
			}
			if (horse.getPassengers().indexOf(player) != 0) {
				return;
			}
			if (part == LEFT_LEG || part == RIGHT_LEG) {
				transforms(model, part, horse.isStanding() ? -0.8F : -0.2F, 0.0F, 0.8F);
			} else {
				float rotation = (float) (-Mth.cos(horse.walkAnimation.position() * 0.3F) * 0.1);
				armTransforms(model, armSide(part), -1.1F - rotation, -0.2F, 0.3F);
			}
		}
	}

	/** Climbing a ladder or trapdoor: hands and feet climb in time with your height; the body faces the ladder. */
	private static final class Ladder extends Anim {
		@Override
		boolean enabled(ModuleState s) {
			return s.bool(Animations.LADDER);
		}

		@Override
		boolean valid(AbstractClientPlayer player, Data d, ModuleState s, AvatarRenderState state) {
			if (!player.onClimbable() || player.onGround() || player.getLastClimbablePos().isEmpty()) {
				return false;
			}
			BlockPos pos = player.getLastClimbablePos().get();
			Block block = player.level().getBlockState(pos).getBlock();
			return block instanceof LadderBlock || block instanceof TrapDoorBlock;
		}

		@Override
		int parts(AbstractClientPlayer player, Data d, ModuleState s) {
			// A crouching climber is drawn standing, so every part climbs.
			return ALL;
		}

		@Override
		int priority() {
			return 1400;
		}

		@Override
		void apply(AbstractClientPlayer player, Data d, PlayerModel model, int part, float delta, float swing, ModuleState s) {
			if (part == HEAD) {
				return;
			}
			if (part == BODY) {
				if (!s.bool(Animations.LADDER_ROTATE)) {
					return;
				}
				BlockState block = player.getInBlockState();
				if (block.hasProperty(HorizontalDirectionalBlock.FACING)) {
					Direction facing = block.getValue(HorizontalDirectionalBlock.FACING);
					d.disableBodyRotation = true;
					float yaw = switch (facing) {
						case NORTH -> 0.0F;
						case EAST -> 90.0F;
						case SOUTH -> 180.0F;
						case WEST -> 270.0F;
						default -> player.yBodyRot;
					};
					player.setYBodyRot(yaw);
					player.yBodyRotO = yaw;
					minMaxHeadRotation(model);
				}
				return;
			}
			float rotation = -Mth.cos((float) (player.getY() * s.decimal(Animations.LADDER_SPEED))) * s.decimal(Animations.LADDER_AMPLIFIER);
			if (part == LEFT_ARM || part == LEFT_LEG) {
				rotation *= -1.0F;
			}
			if (part == LEFT_ARM || part == RIGHT_ARM) {
				transforms(model, part, -s.decimal(Animations.LADDER_HEIGHT) - rotation, -0.2F, 0.3F);
			} else {
				transforms(model, part, -1.0F - rotation, -0.2F, 0.3F);
			}
		}
	}

	private static final Ladder LADDER = new Ladder();

	/** Eating and drinking: the hand lifts the food to the mouth and bobs as you chew. */
	private static final class EatDrink extends Anim {
		private int target;

		@Override
		boolean enabled(ModuleState s) {
			return s.bool(Animations.EAT_DRINK);
		}

		@Override
		boolean valid(AbstractClientPlayer player, Data d, ModuleState s, AvatarRenderState state) {
			if (player.getUseItemRemainingTicks() <= 0) {
				return false;
			}
			ItemUseAnimation use = player.getUseItem().getUseAnimation();
			if (use != ItemUseAnimation.EAT && use != ItemUseAnimation.DRINK) {
				return false;
			}
			target = 1 << armOf(player, player.getUsedItemHand());
			return true;
		}

		@Override
		int parts(AbstractClientPlayer player, Data d, ModuleState s) {
			return target;
		}

		@Override
		int priority() {
			return 2500;
		}

		@Override
		void apply(AbstractClientPlayer player, Data d, PlayerModel model, int part, float delta, float swing, ModuleState s) {
			float g = player.getUseItemRemainingTicks() - delta + 1.0F;
			armTransforms(model, armSide(part),
				-Mth.lerp(-1.0F * (player.getXRot() - 90.0F) / 180.0F, 1.0F, 2.0F) + Mth.abs(Mth.cos(g / 4.0F * (float) Math.PI) * 0.2F), -0.3F, 0.3F);
		}
	}

	/** Blocking with a shield keeps Minecraft's pose for that arm. */
	private static final class VanillaShield extends Keep {
		private int target;

		@Override
		boolean valid(AbstractClientPlayer player, Data d, ModuleState s, AvatarRenderState state) {
			ArmPose right = armPose(player, player.getMainArm() == HumanoidArm.LEFT ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
			ArmPose left = armPose(player, player.getMainArm() == HumanoidArm.RIGHT ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
			target = left == ArmPose.BLOCK ? 1 << LEFT_ARM : right == ArmPose.BLOCK ? 1 << RIGHT_ARM : 0;
			return left == ArmPose.BLOCK || right == ArmPose.BLOCK;
		}

		@Override
		int parts(AbstractClientPlayer player, Data d, ModuleState s) {
			return target;
		}

		@Override
		int priority() {
			return 3100;
		}
	}

	/** Riding anything: the body keeps Minecraft's sitting pose. */
	private static final class Passenger extends Keep {
		@Override
		boolean valid(AbstractClientPlayer player, Data d, ModuleState s, AvatarRenderState state) {
			return player.isPassenger();
		}

		@Override
		int parts(AbstractClientPlayer player, Data d, ModuleState s) {
			return 1 << BODY;
		}

		@Override
		int priority() {
			return 3500;
		}
	}

	/** Riptide, dying and swimming keep Minecraft's whole-body pose. */
	private static final class WholeBody extends Keep {
		private final int kind;

		WholeBody(int kind) {
			this.kind = kind;
		}

		@Override
		boolean valid(AbstractClientPlayer player, Data d, ModuleState s, AvatarRenderState state) {
			return switch (kind) {
				case 0 -> player.isAutoSpinAttack();
				case 1 -> player.getPose() == Pose.DYING;
				default -> player.getPose() == Pose.SWIMMING && (player.isInWater() || !s.bool(Animations.CRAWLING));
			};
		}

		@Override
		int parts(AbstractClientPlayer player, Data d, ModuleState s) {
			return ALL;
		}

		@Override
		int priority() {
			return 3600;
		}
	}

	/** Gliding: arms and legs sway, less the faster you fly (with Tweak elytra flying). */
	private static final class Elytra extends Anim {
		@Override
		boolean enabled(ModuleState s) {
			return true;
		}

		@Override
		boolean valid(AbstractClientPlayer player, Data d, ModuleState s, AvatarRenderState state) {
			return player.getPose() == Pose.FALL_FLYING;
		}

		@Override
		int parts(AbstractClientPlayer player, Data d, ModuleState s) {
			return ALL;
		}

		@Override
		int priority() {
			return 3000;
		}

		@Override
		void apply(AbstractClientPlayer player, Data d, PlayerModel model, int part, float delta, float swing, ModuleState s) {
			if (!s.bool(Animations.ELYTRA)) {
				return;
			}
			float k = (float) player.getDeltaMovement().lengthSqr() / 0.2F;
			k *= k * k;
			if (k < 1.0F) {
				k = 1.0F;
			}
			float moveOut = Math.clamp(0.1507964F / k, 0.1F, 0.25F);
			switch (part) {
				case LEFT_ARM -> {
					model.leftArm.xRot = Mth.cos(swing * 0.6662F) * 0.5F / k;
					model.leftArm.zRot = -moveOut;
				}
				case RIGHT_ARM -> {
					model.rightArm.xRot = Mth.cos(swing * 0.6662F + (float) Math.PI) * 0.5F / k;
					model.rightArm.zRot = moveOut;
				}
				case LEFT_LEG -> {
					model.leftLeg.xRot = Mth.cos(swing * 0.6662F + (float) Math.PI) * 0.7F / k;
					model.leftLeg.zRot = -moveOut;
				}
				case RIGHT_LEG -> {
					model.rightLeg.xRot = Mth.cos(swing * 0.6662F) * 0.7F / k;
					model.rightLeg.zRot = moveOut;
				}
				default -> {
				}
			}
		}
	}

	/** Petting: crouch while looking at a wolf or cat close by; now and then it shows hearts. */
	private static final class Pet extends Anim {
		private @Nullable Entity pet;

		@Override
		boolean enabled(ModuleState s) {
			return s.bool(Animations.PET);
		}

		@Override
		boolean valid(AbstractClientPlayer player, Data d, ModuleState s, AvatarRenderState state) {
			pet = null;
			if (!crouching(player)) {
				return false;
			}
			Entity target = lookedAt(player);
			if (target instanceof TamableAnimal animal) {
				String type = BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType()).toString();
				if (("minecraft:wolf".equals(type) || "minecraft:cat".equals(type)) && Math.abs(animal.getY() - player.getY()) < 0.6) {
					pet = animal;
					return true;
				}
			}
			return false;
		}

		@Override
		int parts(AbstractClientPlayer player, Data d, ModuleState s) {
			return player.getMainArm() == HumanoidArm.RIGHT ? 1 << RIGHT_ARM : 1 << LEFT_ARM;
		}

		@Override
		int priority() {
			return 2100;
		}

		@Override
		void apply(AbstractClientPlayer player, Data d, PlayerModel model, int part, float delta, float swing, ModuleState s) {
			if (pet != null && Math.random() < 0.005) {
				pet.handleEntityEvent((byte) 18);
			}
			armTransforms(model, armSide(part), -Mth.lerp(-1.0F * (player.getXRot() - 90.0F) / 180.0F, 1.0F, 2.0F), -0.6F,
				0.3F + Mth.sin((float) (System.currentTimeMillis() % 20000L) / 60.0F) * 0.2F);
			pet = null;
		}
	}

	/** Falling far: arms and legs flail, more the faster you fall. */
	private static final class Falling extends Anim {
		@Override
		boolean enabled(ModuleState s) {
			return s.bool(Animations.FALLING);
		}

		@Override
		boolean valid(AbstractClientPlayer player, Data d, ModuleState s, AvatarRenderState state) {
			if (player.isFallFlying() || player.onGround() || player.onClimbable() || player.getAbilities().flying || player.isSwimming()) {
				return false;
			}
			if (!d.falling) {
				d.falling = true;
				d.fallLastY = player.getY();
			}
			if (player == mc().player) {
				d.fallSpeed = (float) (player.getDeltaMovement().lengthSqr() / 11.0);
				return player.fallDistance > 3.0;
			}
			if (player.getY() != d.fallLastY) {
				d.fallSpeed = (float) (d.fallLastY - player.getY()) / 3.5F;
				d.fallLastY = player.getY();
			}
			return d.fallSpeed > 0.14285715F;
		}

		@Override
		int parts(AbstractClientPlayer player, Data d, ModuleState s) {
			return ALL;
		}

		@Override
		int priority() {
			return 400;
		}

		@Override
		void apply(AbstractClientPlayer player, Data d, PlayerModel model, int part, float delta, float swing, ModuleState s) {
			float speed = d.fallSpeed;
			float arms = 1.9F * Math.min(1.0F, speed * 2.0F);
			speed = Math.min(1.0F, speed);
			float legs = 0.6F * speed;
			float t = player.tickCount + delta;
			switch (part) {
				case LEFT_ARM -> {
					model.leftArm.xRot = Mth.cos(t * 0.6662F) * speed;
					model.leftArm.zRot = -arms;
				}
				case RIGHT_ARM -> {
					model.rightArm.xRot = Mth.cos(t * 0.6662F + (float) Math.PI) * speed;
					model.rightArm.zRot = arms;
				}
				case LEFT_LEG -> {
					model.leftLeg.xRot = Mth.cos(t * 0.6662F + (float) Math.PI) * 1.4F * speed;
					model.leftLeg.zRot = -legs;
				}
				case RIGHT_LEG -> {
					model.rightLeg.xRot = Mth.cos(t * 0.6662F) * 1.4F * speed;
					model.rightLeg.zRot = legs;
				}
				default -> {
				}
			}
		}
	}

	/** Hugging: crouch facing another crouching player at your height. */
	private static final class Hug extends Anim {
		@Override
		boolean enabled(ModuleState s) {
			return s.bool(Animations.HUGGING);
		}

		@Override
		boolean valid(AbstractClientPlayer player, Data d, ModuleState s, AvatarRenderState state) {
			if (!crouching(player)) {
				return false;
			}
			return lookedAt(player) instanceof Player other && other.isCrouching() && Math.abs(other.getY() - player.getY()) < 0.3;
		}

		@Override
		int parts(AbstractClientPlayer player, Data d, ModuleState s) {
			return 1 << LEFT_ARM | 1 << RIGHT_ARM;
		}

		@Override
		int priority() {
			return 2100;
		}

		@Override
		void apply(AbstractClientPlayer player, Data d, PlayerModel model, int part, float delta, float swing, ModuleState s) {
			if (part == LEFT_ARM) {
				armTransforms(model, HumanoidArm.LEFT, -1.0F, -0.2F, 0.3F);
			}
			if (part == RIGHT_ARM) {
				armTransforms(model, HumanoidArm.RIGHT, -1.5F, -0.2F, 0.3F);
			}
		}
	}

	/** Sprinting with the arms trailing behind. */
	private static final class Naruto extends Anim {
		@Override
		boolean enabled(ModuleState s) {
			return s.bool(Animations.NARUTO);
		}

		@Override
		boolean valid(AbstractClientPlayer player, Data d, ModuleState s, AvatarRenderState state) {
			return player.isSprinting();
		}

		@Override
		int parts(AbstractClientPlayer player, Data d, ModuleState s) {
			return 1 << LEFT_ARM | 1 << RIGHT_ARM;
		}

		@Override
		int priority() {
			return 500;
		}

		@Override
		void apply(AbstractClientPlayer player, Data d, PlayerModel model, int part, float delta, float swing, ModuleState s) {
			if (part == LEFT_ARM && !swingingArm(player, part)) {
				armTransforms(model, HumanoidArm.LEFT, 1.0F, -0.2F, 0.3F);
			}
			if (part == RIGHT_ARM && !swingingArm(player, part)) {
				armTransforms(model, HumanoidArm.RIGHT, 1.0F, -0.2F, 0.3F);
			}
		}
	}

	/** The arm holding the bow (or crossbow) and the other arm, with the bow in the left hand mirrored. */
	private abstract static class TwoHandedWeapon extends Anim {
		@Override
		int parts(AbstractClientPlayer player, Data d, ModuleState s) {
			return 1 << LEFT_ARM | 1 << RIGHT_ARM | 1 << BODY;
		}

		@Override
		int priority() {
			return 3200;
		}
	}

	/** Custom V1 bow: both arms aim the bow along your view; optionally the body turns side-on. */
	private static final class CustomBow extends TwoHandedWeapon {
		@Override
		boolean enabled(ModuleState s) {
			return s.option(Animations.BOW) == 1;
		}

		@Override
		boolean valid(AbstractClientPlayer player, Data d, ModuleState s, AvatarRenderState state) {
			return projectile(player, true);
		}

		@Override
		void apply(AbstractClientPlayer player, Data d, PlayerModel model, int part, float delta, float swing, ModuleState s) {
			boolean left = player.getMainArm() == HumanoidArm.RIGHT && player.getUsedItemHand() == InteractionHand.OFF_HAND
				|| player.getMainArm() == HumanoidArm.LEFT && player.getUsedItemHand() == InteractionHand.MAIN_HAND;
			ModelPart main = left ? model.leftArm : model.rightArm;
			ModelPart off = left ? model.rightArm : model.leftArm;
			int mainPart = left ? LEFT_ARM : RIGHT_ARM;
			int offPart = left ? RIGHT_ARM : LEFT_ARM;
			int invert = left ? -1 : 1;
			if (part == mainPart) {
				main.yRot = invert * Mth.clamp(-0.1F + wrapRadians(-model.head.xRot), -1.25F, 0.5F);
				main.xRot = Mth.clamp((float) (-Math.PI / 2) + invert * wrapRadians(model.head.yRot), -2.0F, 0.0F);
				main.zRot += invert * 1.5F;
			}
			if (part == offPart) {
				off.yRot = invert * Mth.clamp(0.1F + wrapRadians(-model.head.xRot), -1.05F, 0.7F);
				off.xRot = Mth.clamp((float) (-Math.PI / 2) + invert * wrapRadians(model.head.yRot) + 0.8F, -1.05F, -0.65F);
				off.zRot += invert * 1.5F;
			}
			if (part == BODY && s.bool(Animations.BOW_LOCK)) {
				float turn = left ? 40.0F : -40.0F;
				player.yBodyRot = player.getYRot() + turn;
				player.yBodyRotO = player.yRotO + turn;
			}
		}
	}

	/** Crossbows: the arms don't swing past straight ahead. */
	private static final class ClampCrossbow extends TwoHandedWeapon {
		@Override
		boolean enabled(ModuleState s) {
			return s.bool(Animations.CROSSBOW_CLAMP);
		}

		@Override
		boolean valid(AbstractClientPlayer player, Data d, ModuleState s, AvatarRenderState state) {
			return crossbowPose(player);
		}

		@Override
		void apply(AbstractClientPlayer player, Data d, PlayerModel model, int part, float delta, float swing, ModuleState s) {
			boolean right = player.getMainArm() == HumanoidArm.RIGHT;
			boolean left = right && player.getUsedItemHand() == InteractionHand.OFF_HAND || !right && player.getUsedItemHand() == InteractionHand.MAIN_HAND
				|| right && CrossbowItem.isCharged(player.getOffhandItem()) || !right && CrossbowItem.isCharged(player.getMainHandItem());
			ModelPart main = left ? model.leftArm : model.rightArm;
			ModelPart off = left ? model.rightArm : model.leftArm;
			if (part == (left ? LEFT_ARM : RIGHT_ARM)) {
				main.xRot = Mth.clamp(wrapRadians(main.xRot), -1.75F, 0.0F);
			}
			if (part == (left ? RIGHT_ARM : LEFT_ARM)) {
				off.xRot = Mth.clamp(wrapRadians(off.xRot), -1.75F, 0.0F);
			}
		}
	}

	/** Eating, drinking or blocking turns the body with the head (with Animation rotation locking). */
	private static final class ActionRotationLock extends Anim {
		@Override
		boolean enabled(ModuleState s) {
			return s.bool(Animations.ROTATION_LOCKING);
		}

		@Override
		boolean valid(AbstractClientPlayer player, Data d, ModuleState s, AvatarRenderState state) {
			if (player.getUseItemRemainingTicks() <= 0) {
				return false;
			}
			ItemUseAnimation use = player.getUseItem().getUseAnimation();
			return use == ItemUseAnimation.EAT || use == ItemUseAnimation.DRINK || use == ItemUseAnimation.BLOCK;
		}

		@Override
		int parts(AbstractClientPlayer player, Data d, ModuleState s) {
			return 1 << BODY;
		}

		@Override
		int priority() {
			return 1250;
		}

		@Override
		void apply(AbstractClientPlayer player, Data d, PlayerModel model, int part, float delta, float swing, ModuleState s) {
			d.rotateBodyToHead = true;
		}
	}

	/** Burning (without fire resistance): arms flail overhead and the head shakes. */
	private static final class Burning extends Anim {
		@Override
		boolean enabled(ModuleState s) {
			return s.bool(Animations.BURNING);
		}

		@Override
		boolean valid(AbstractClientPlayer player, Data d, ModuleState s, AvatarRenderState state) {
			return player.isOnFire() && !player.hasEffect(MobEffects.FIRE_RESISTANCE);
		}

		@Override
		int parts(AbstractClientPlayer player, Data d, ModuleState s) {
			return 1 << LEFT_ARM | 1 << RIGHT_ARM | 1 << HEAD;
		}

		@Override
		int priority() {
			return 400;
		}

		@Override
		void apply(AbstractClientPlayer player, Data d, PlayerModel model, int part, float delta, float swing, ModuleState s) {
			if (part == HEAD) {
				model.head.yRot = model.head.yRot + Mth.sin(player.tickCount) * 0.1F;
			} else if (!swinging(player) || armOf(player, swingHand(player)) != part) {
				float height = Mth.sin(player.tickCount) * 0.1F;
				if (part == LEFT_ARM) {
					height *= -1.0F;
				}
				armTransforms(model, armSide(part), -2.6F + height, -0.2F, -0.3F);
			}
		}
	}

	/** In powder snow: the arms wrap around you and shiver. */
	private static final class Freezing extends Anim {
		@Override
		boolean enabled(ModuleState s) {
			return s.bool(Animations.FREEZING);
		}

		@Override
		boolean valid(AbstractClientPlayer player, Data d, ModuleState s, AvatarRenderState state) {
			return player.canFreeze() && player.level().getBlockStatesIfLoaded(player.getBoundingBox().deflate(1.0E-6))
				.anyMatch(block -> block.is(Blocks.POWDER_SNOW) || block.is(Blocks.POWDER_SNOW_CAULDRON));
		}

		@Override
		int parts(AbstractClientPlayer player, Data d, ModuleState s) {
			return 1 << LEFT_ARM | 1 << RIGHT_ARM;
		}

		@Override
		int priority() {
			return 400;
		}

		@Override
		void apply(AbstractClientPlayer player, Data d, PlayerModel model, int part, float delta, float swing, ModuleState s) {
			if (swinging(player) && armOf(player, swingHand(player)) == part) {
				return;
			}
			if (part == LEFT_ARM) {
				armTransforms(model, HumanoidArm.LEFT, -0.6F, 0.2F, (float) (Math.random() / 10.0 + -1.3F));
			}
			if (part == RIGHT_ARM) {
				armTransforms(model, HumanoidArm.RIGHT, -0.5F, 0.2F, (float) (Math.random() / 10.0 + -1.0));
			}
		}
	}

	/** Not Enough Animations' animations, in its load order (the first of equal priorities wins). */
	private static final Anim[] ANIMS = {new Crawling(), new VanillaSingleHanded(), new VanillaTwoHanded(), new ItemSwap(), new LookAtItem(),
		new Sleep(), new MapHolding(), new Boat(), new Horse(), LADDER, new EatDrink(), new VanillaShield(), new Passenger(), new WholeBody(0),
		new WholeBody(1), new Elytra(), new WholeBody(2), new Pet(), new Falling(), new Hug(), new Naruto(), new CustomBow(), new ActionRotationLock(),
		new Burning(), new ClampCrossbow(), new Freezing()};
}
