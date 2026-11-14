package com.flintclient.client.compat.misc;

import net.minecraft.Util;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * Whose hit made an entity flash hurt, as far as Minecraft 1.19.2's client can know it. 1.19.4's damage events name the
 * attacker; 1.19.2's server only makes a hurt entity flash (entity events 2, 33, 36, 37, 44 and 57) and says nothing of who
 * or what hurt it. The flash of the entity you have just attacked is your hit landing (as Hit Color's note has it, a marker
 * shows when the entity you hit flashes hurt), so the damage event Flint's combat mods hear is made from the two:
 * {@code PlayerMixin} notes your attack and {@code LivingEntityMixin} the flash. A hit that wasn't an attack of yours (an
 * arrow, a sweep's other targets) isn't known to be yours. Render thread.
 */
public final class HitConfirm {
	/** How long after your attack the server's flash still confirms it (the reach's confirmation window, CombatTracker.CONFIRM_MS). */
	private static final long WINDOW_MS = 1000;
	private static int target = Integer.MIN_VALUE;
	private static long attackedAt;

	private HitConfirm() {
	}

	/** You attacked {@code entity} (Player.attack on your client). */
	public static void attacked(Entity entity) {
		target = entity.getId();
		attackedAt = Util.getMillis();
	}

	/** Whether entity event {@code id} is a living entity's hurt flash (any kind of damage). */
	public static boolean hurtFlash(byte id) {
		return id == 2 || id == 33 || id == 36 || id == 37 || id == 44 || id == 57;
	}

	/**
	 * The damage {@code entity}'s hurt flash stands for when it is the entity {@code player} attacked within the window (your
	 * attack), else null. Each attack is confirmed once: a later flash (a burn after Fire Aspect, someone else's hit) isn't yours.
	 */
	public static @Nullable DamageSource landed(Entity entity, @Nullable Player player) {
		if (player == null || entity.getId() != target || Util.getMillis() - attackedAt > WINDOW_MS) {
			return null;
		}
		target = Integer.MIN_VALUE;
		return DamageSource.playerAttack(player);
	}
}
