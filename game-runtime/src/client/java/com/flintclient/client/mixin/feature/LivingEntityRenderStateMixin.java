package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.feature.ChatBubbles;
import com.flintclient.client.control.platform.MobFillState;
import com.flintclient.client.control.platform.NametagState;
import java.util.List;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Room on a living thing's render state for Mob Overlay's fill ({@link MobFillState}), Nametags' chat bubbles
 * ({@link NametagState}), Health Indicators' hearts and the entity itself for Animations.
 */
@Mixin(LivingEntityRenderState.class)
abstract class LivingEntityRenderStateMixin implements MobFillState, NametagState, com.flintclient.client.control.platform.Mc263HealthIndicators.Health,
	com.flintclient.client.control.platform.EntityRef {
	@Unique
	private net.minecraft.world.entity.@Nullable LivingEntity flint$entity;

	@Override
	public net.minecraft.world.entity.@Nullable LivingEntity flint$entity() {
		return flint$entity;
	}

	@Override
	public void flint$entity(net.minecraft.world.entity.@Nullable LivingEntity entity) {
		flint$entity = entity;
	}

	@Unique
	private boolean flint$healthShown;
	@Unique
	private float flint$health;
	@Unique
	private float flint$maxHealth;
	@Unique
	private float flint$absorption;

	@Override
	public boolean flint$healthShown() {
		return flint$healthShown;
	}

	@Override
	public void flint$health(boolean shown, float health, float maxHealth, float absorption) {
		flint$healthShown = shown;
		flint$health = health;
		flint$maxHealth = maxHealth;
		flint$absorption = absorption;
	}

	@Override
	public float flint$healthValue() {
		return flint$health;
	}

	@Override
	public float flint$maxHealth() {
		return flint$maxHealth;
	}

	@Override
	public float flint$absorption() {
		return flint$absorption;
	}

	@Unique
	private int flint$fill;
	@Unique
	private List<ChatBubbles.Line> flint$bubbles = List.of();
	@Unique
	private @Nullable Vec3 flint$bubbleAt;

	@Override
	public List<ChatBubbles.Line> flint$bubbles() {
		return flint$bubbles;
	}

	@Override
	public @Nullable Vec3 flint$bubbleAt() {
		return flint$bubbleAt;
	}

	@Override
	public void flint$bubbles(List<ChatBubbles.Line> lines, @Nullable Vec3 at) {
		flint$bubbles = lines;
		flint$bubbleAt = at;
	}

	@Override
	public int flint$fill() {
		return flint$fill;
	}

	@Override
	public void flint$fill(int argb) {
		flint$fill = argb;
	}
}
