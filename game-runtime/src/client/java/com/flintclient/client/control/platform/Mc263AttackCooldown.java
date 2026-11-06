package com.flintclient.client.control.platform;

import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.mods.PlayerMods.AttackIndicator;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;

/**
 * Attack Indicator's Accurate cooldown, Accurate Attack Indicator's behaviour (MC-118740), in Flint's own code: every time
 * your own player swings (the client's {@code LivingEntity.swing}, whether the swing animation starts or not), the attack
 * cooldown restarts, as the server restarts it on every punch, item use, block use and drop. Minecraft's client only
 * restarts it when you attack, so its indicator showed "ready" while the server's cooldown ran. The hand's lowering
 * after an item swap is left alone (only the attack ticker is reset). One comparison per swing.
 */
public final class Mc263AttackCooldown {
	private static final int ATTACK = Mods.index(AttackIndicator.ID);

	private Mc263AttackCooldown() {
	}

	/** From the start of {@code LivingEntity.swing}: whether {@code entity} is you and its attack ticker should restart. */
	public static boolean resetOnSwing(LivingEntity entity) {
		return entity == Minecraft.getInstance().player && Mods.on(ATTACK) && Mods.state(ATTACK).bool(AttackIndicator.ACCURATE);
	}
}
