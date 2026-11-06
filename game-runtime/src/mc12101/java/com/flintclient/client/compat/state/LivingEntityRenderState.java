package com.flintclient.client.compat.state;

import com.flintclient.client.control.feature.ChatBubbles;
import com.flintclient.client.control.platform.Mc263HealthIndicators;
import com.flintclient.client.control.platform.MobFillState;
import com.flintclient.client.control.platform.NametagState;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A living thing's render state as Flint's features read it (see {@link EntityRenderState}), with the room the other lines
 * add to Minecraft's by mixin: Mob Overlay's fill, Nametags' chat bubbles, Health Indicators' hearts.
 */
public class LivingEntityRenderState extends EntityRenderState implements MobFillState, NametagState, Mc263HealthIndicators.Health,
	com.flintclient.client.control.platform.EntityRef {
	public float bodyRot;
	public float yRot;
	public float xRot;
	public boolean hasRedOverlay;
	public boolean isInvisibleToPlayer;
	public boolean isBaby;
	/** The walk animation's position and speed this frame (1.21.2's walkAnimationPos and walkAnimationSpeed). */
	public float walkAnimationPos;
	public float walkAnimationSpeed;
	/** The entity drawn with this state (weakly: the states are kept per entity, RenderStates). */
	private java.lang.ref.WeakReference<net.minecraft.world.entity.LivingEntity> entity = new java.lang.ref.WeakReference<>(null);

	@Override
	public net.minecraft.world.entity.@Nullable LivingEntity flint$entity() {
		return entity.get();
	}

	@Override
	public void flint$entity(net.minecraft.world.entity.@Nullable LivingEntity entity) {
		if (this.entity.get() != entity) {
			this.entity = new java.lang.ref.WeakReference<>(entity);
		}
	}
	public SkullBlock.@Nullable Type wornHeadType;
	public ItemStack headItem = ItemStack.EMPTY;

	private int fill;
	private List<ChatBubbles.Line> bubbles = List.of();
	private @Nullable Vec3 bubbleAt;
	private boolean healthShown;
	private float health;
	private float maxHealth;
	private float absorption;

	@Override
	public int flint$fill() {
		return fill;
	}

	@Override
	public void flint$fill(int argb) {
		fill = argb;
	}

	@Override
	public List<ChatBubbles.Line> flint$bubbles() {
		return bubbles;
	}

	@Override
	public @Nullable Vec3 flint$bubbleAt() {
		return bubbleAt;
	}

	@Override
	public void flint$bubbles(List<ChatBubbles.Line> lines, @Nullable Vec3 at) {
		bubbles = lines;
		bubbleAt = at;
	}

	@Override
	public boolean flint$healthShown() {
		return healthShown;
	}

	@Override
	public void flint$health(boolean shown, float health, float maxHealth, float absorption) {
		this.healthShown = shown;
		this.health = health;
		this.maxHealth = maxHealth;
		this.absorption = absorption;
	}

	@Override
	public float flint$healthValue() {
		return health;
	}

	@Override
	public float flint$maxHealth() {
		return maxHealth;
	}

	@Override
	public float flint$absorption() {
		return absorption;
	}
}
