package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Clumps;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft 1.21.1 (the value is a plain field, saves are compound tags). Clumps: XP orbs merge whatever their value and are taken at once. An orb keeps the orbs it is made of (value to how many),
 * so taking it repairs Mending items orb by orb as Minecraft would, and is worth exactly their sum: its value is that sum and
 * its count 1, so Minecraft's own pickup gives the same XP once the module is off. The parts are saved as Clumps saves them
 * ({@code clumpedMap}), so a world from Clumps keeps its orbs. Server side: in the game this client hosts.
 *
 * <p>Adapted from Clumps' {@code MixinExperienceOrb} (Jaredlll08, MIT; META-INF/licenses/MIT-Clumps.txt).
 */
@Mixin(value = ExperienceOrb.class, priority = 1001)
abstract class ExperienceOrbMixin extends Entity implements Mc263Clumps.Orb {
	@Shadow
	private int count;
	@Shadow
	private int age;
	/** The orbs this one is made of: value to how many; null until it merges or loads with parts. */
	@Unique
	private @Nullable Map<Integer, Integer> flint$parts;

	protected ExperienceOrbMixin(EntityType<?> type, Level level) {
		super(type, level);
	}

	@Shadow
	public abstract int getValue();

	@Shadow
	private int value;

	/** 1.21.1 has no setter for the value: the field itself. */
	@Unique
	private void setValue(int amount) {
		value = amount;
	}

	@Shadow
	protected abstract int repairPlayerItems(ServerPlayer player, int amount);

	/** Its parts as they stand: what it was made of if that still adds up, else {@code count} orbs of its value. */
	@Override
	public Map<Integer, Integer> flint$parts() {
		Map<Integer, Integer> parts = flint$parts;
		if (parts != null) {
			long sum = 0;
			for (Map.Entry<Integer, Integer> e : parts.entrySet()) {
				sum += (long) e.getKey() * e.getValue();
			}
			if (sum == (long) getValue() * count) {
				return parts;
			}
		}
		Map<Integer, Integer> plain = new HashMap<>();
		plain.put(getValue(), count);
		return plain;
	}

	/** Takes {@code parts} in: its value becomes the sum, one orb. */
	@Override
	public void flint$become(Map<Integer, Integer> parts) {
		long sum = 0;
		for (Map.Entry<Integer, Integer> e : parts.entrySet()) {
			sum += (long) e.getKey() * e.getValue();
		}
		flint$parts = parts;
		setValue((int) Math.min(Integer.MAX_VALUE, sum));
		count = 1;
	}

	@Override
	public int flint$age() {
		return age;
	}

	@Override
	public void flint$setAge(int value) {
		age = value;
	}

	@Unique
	private static Map<Integer, Integer> flint$sum(Map<Integer, Integer> a, Map<Integer, Integer> b) {
		Map<Integer, Integer> out = new HashMap<>(a);
		b.forEach((value, amount) -> out.merge(value, amount, Integer::sum));
		return out;
	}

	@Inject(method = "canMerge(Lnet/minecraft/world/entity/ExperienceOrb;)Z", at = @At("HEAD"), cancellable = true)
	private void flint$canMerge(ExperienceOrb other, CallbackInfoReturnable<Boolean> callback) {
		if (Mc263Clumps.on()) {
			callback.setReturnValue(other.isAlive() && !this.is(other));
		}
	}

	@Inject(method = "canMerge(Lnet/minecraft/world/entity/ExperienceOrb;II)Z", at = @At("HEAD"), cancellable = true)
	private static void flint$canMergeAny(ExperienceOrb orb, int id, int value, CallbackInfoReturnable<Boolean> callback) {
		if (Mc263Clumps.on()) {
			callback.setReturnValue(orb.isAlive());
		}
	}

	/** Two orbs that meet become one worth both. */
	@Inject(method = "merge", at = @At("HEAD"), cancellable = true)
	private void flint$merge(ExperienceOrb other, CallbackInfo callback) {
		if (!Mc263Clumps.on()) {
			return;
		}
		Mc263Clumps.Orb that = (Mc263Clumps.Orb) other;
		flint$become(flint$sum(flint$parts(), that.flint$parts()));
		age = Math.min(age, that.flint$age());
		other.discard();
		callback.cancel();
	}

	/** New XP joins any orb already there (Minecraft only joins one of the same value now and then). */
	@Inject(method = "tryMergeToExisting", at = @At("HEAD"), cancellable = true)
	private static void flint$mergeNew(ServerLevel level, Vec3 pos, int value, CallbackInfoReturnable<Boolean> callback) {
		if (!Mc263Clumps.on()) {
			return;
		}
		AABB box = AABB.ofSize(pos, 1.0, 1.0, 1.0);
		level.getRandom().nextInt(40);
		List<ExperienceOrb> orbs = level.getEntities(EntityTypeTest.forClass(ExperienceOrb.class), box, ExperienceOrb::isAlive);
		if (orbs.isEmpty()) {
			callback.setReturnValue(false);
			return;
		}
		Mc263Clumps.Orb orb = (Mc263Clumps.Orb) orbs.get(0);
		orb.flint$become(flint$sum(orb.flint$parts(), Map.of(value, 1)));
		orb.flint$setAge(0);
		callback.setReturnValue(true);
	}

	/** Touching an orb takes all of it at once, each part through Mending as its own orb would go. */
	@Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
	private void flint$take(Player player, CallbackInfo callback) {
		if (!(player instanceof ServerPlayer serverPlayer) || !Mc263Clumps.on()) {
			return;
		}
		serverPlayer.takeXpDelay = 0;
		serverPlayer.take(this, 1);
		if (getValue() != 0) {
			long give = 0;
			for (Map.Entry<Integer, Integer> part : flint$parts().entrySet()) {
				for (int i = 0; i < part.getValue(); i++) {
					int left = repairPlayerItems(serverPlayer, part.getKey());
					if (left > 0) {
						give += left;
					}
				}
			}
			if (give > 0) {
				serverPlayer.giveExperiencePoints((int) Math.min(Integer.MAX_VALUE, give));
			}
		}
		discard();
		callback.cancel();
	}

	@Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
	private void flint$save(CompoundTag tag, CallbackInfo callback) {
		if (flint$parts != null) {
			CompoundTag map = new CompoundTag();
			flint$parts().forEach((value, amount) -> map.putInt(String.valueOf(value), amount));
			tag.put("clumpedMap", map);
		}
	}

	@Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
	private void flint$load(CompoundTag tag, CallbackInfo callback) {
		if (!tag.contains("clumpedMap")) {
			return;
		}
		CompoundTag map = tag.getCompound("clumpedMap");
		Map<Integer, Integer> parts = new HashMap<>();
		for (String key : map.getAllKeys()) {
			try {
				parts.put(Integer.parseInt(key), map.getInt(key));
			} catch (NumberFormatException ignored) {
				// Not an orb value.
			}
		}
		if (!parts.isEmpty()) {
			flint$become(parts);
		}
	}
}
