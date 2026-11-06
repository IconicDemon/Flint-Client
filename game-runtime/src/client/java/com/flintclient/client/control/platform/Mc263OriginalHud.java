/*
 * Adapted from: Player Reach Display by KikiJiji (MIT, META-INF/licenses/MIT-PlayerReachDisplay.txt), PotionCounter by uku (MIT,
 * META-INF/licenses/MIT-PotionCounter.txt), ptdisplay by JuiceyBeans (LGPL-3.0, META-INF/licenses/LGPL-3.0-ptdisplay.txt) and
 * Pack Displayer by Rxeign (CC0-1.0).
 */
package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.hud.render.HudLooks;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.HudMods;
import com.flintclient.client.control.model.mods.MoreHudMods;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.stats.StatFormatter;
import net.minecraft.stats.Stats;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import org.jspecify.annotations.Nullable;

/**
 * The game data behind the HUD modules drawn as their original mods draw them: Reach Display's last hit (Player Reach
 * Display), Playtime's statistic (ptdisplay), Potion Counter's potions (uku's PotionCounter), Pack Display's pack (Pack
 * Displayer) and whether an action bar message is up (Day Counter yields to it). Reads only; the statistic is asked of the
 * server once a second while Playtime shows. Fault-isolated.
 */
public final class Mc263OriginalHud {
	private static final HookGuard GUARD = new HookGuard("original HUD mods",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final int REACH = Mods.index(HudMods.ReachDisplay.ID);
	private static final int PLAYTIME = Mods.index(HudMods.Playtime.ID);
	private static final int PACKS = Mods.index(MoreHudMods.PackDisplay.ID);

	private Mc263OriginalHud() {
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	// ---------- Reach Display ----------

	private static double lastHitDistance;
	private static long lastHitMillis;
	private static String filterFrom = "\0";
	private static final Set<String> FILTER = new HashSet<>();

	/** You attacked {@code target} {@code distance} blocks away (eyes to the nearest point of its hitbox). */
	public static void attacked(Entity target, double distance) {
		if (!GUARD.on() || !Mods.on(REACH)) {
			return;
		}
		try {
			if (tracked(Mods.state(REACH), target)) {
				lastHitDistance = distance;
				lastHitMillis = System.currentTimeMillis();
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** The entity filter: off, everything counts; on, a whitelist names what counts (empty: all), a blacklist what doesn't. */
	private static boolean tracked(ModuleState s, Entity target) {
		if (!s.bool(HudMods.ReachDisplay.FILTER)) {
			return true;
		}
		Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
		String id = key == null ? "" : key.toString();
		if (id.isEmpty()) {
			return false;
		}
		boolean whitelist = s.option(HudMods.ReachDisplay.FILTER_MODE) == 0;
		Set<String> list = ids(s.text(whitelist ? HudMods.ReachDisplay.WHITELIST : HudMods.ReachDisplay.BLACKLIST));
		if (list.isEmpty()) {
			return true;
		}
		return whitelist == list.contains(id);
	}

	/** Entity ids from a comma-separated list, lower case, with "minecraft:" where none is given. */
	private static Set<String> ids(String text) {
		if (!text.equals(filterFrom)) {
			filterFrom = text;
			FILTER.clear();
			for (String raw : text.split(",")) {
				String id = raw.strip().toLowerCase(Locale.ROOT);
				if (!id.isEmpty()) {
					FILTER.add(id.contains(":") ? id : "minecraft:" + id);
				}
			}
		}
		return FILTER;
	}

	/** The distance to show: 0 before any hit, the last one (kept, or until Reset after has passed since it). */
	public static double reachShown(ModuleState s) {
		if (lastHitMillis == 0L) {
			return 0.0;
		}
		if (s.bool(HudMods.ReachDisplay.KEEP_LAST)) {
			return lastHitDistance;
		}
		double elapsed = (System.currentTimeMillis() - lastHitMillis) / 1000.0;
		return elapsed > s.decimal(HudMods.ReachDisplay.RESET_AFTER) ? 0.0 : lastHitDistance;
	}

	// ---------- Playtime ----------

	/** Your Play Time statistic in ticks, as the server last sent it; -1 without a player. */
	public static int playTimeTicks() {
		LocalPlayer player = mc().player;
		return player == null ? -1 : player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME, StatFormatter.TIME));
	}

	/** {@code ticks} in Minecraft's statistics format (like "1.25 h"). */
	public static String statTime(int ticks) {
		return StatFormatter.TIME.format(ticks);
	}

	// ---------- Potion Counter ----------

	/**
	 * Your splash potions as uku's PotionCounter lists them: grouped by their first effect (or, with Show potion upgrades, by
	 * the exact potion), in the order they first appear in your inventory, leaving out effects switched off.
	 */
	public static List<HudLooks.PotionRow> potions(ModuleState s) {
		LocalPlayer player = mc().player;
		List<HudLooks.PotionRow> out = new ArrayList<>();
		if (player == null) {
			return out;
		}
		boolean upgrades = s.bool(HudMods.PotionCounter.SHOW_UPGRADES);
		List<Object> keys = new ArrayList<>();
		List<int[]> counts = new ArrayList<>();
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			if (!stack.is(Items.SPLASH_POTION)) {
				continue;
			}
			PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
			if (contents == null || contents.potion().isEmpty()) {
				continue;
			}
			Holder<Potion> potion = contents.potion().get();
			Object key;
			if (upgrades) {
				key = potion;
			} else {
				List<MobEffectInstance> effects = potion.value().getEffects();
				if (effects.isEmpty()) {
					continue;
				}
				key = effects.getFirst().getEffect();
			}
			int at = keys.indexOf(key);
			if (at < 0) {
				keys.add(key);
				counts.add(new int[] {1});
			} else {
				counts.get(at)[0]++;
			}
		}
		for (int i = 0; i < keys.size(); i++) {
			Object key = keys.get(i);
			int count = counts.get(i)[0];
			if (upgrades) {
				@SuppressWarnings("unchecked")
				Holder<Potion> potion = (Holder<Potion>) key;
				boolean shown = true;
				for (MobEffectInstance effect : potion.value().getEffects()) {
					shown &= shown(s, effect.getEffect());
				}
				if (!shown) {
					continue;
				}
				String name = potion.getRegisteredName();
				ItemStack icon = PotionContents.createItemStack(Items.SPLASH_POTION, potion);
				icon.setCount(count);
				out.add(new HudLooks.PotionRow(icon, count, name.contains("long"), name.contains("strong")));
			} else {
				@SuppressWarnings("unchecked")
				Holder<MobEffect> effect = (Holder<MobEffect>) key;
				if (!shown(s, effect)) {
					continue;
				}
				ItemStack icon = new ItemStack(Items.SPLASH_POTION, count);
				icon.set(DataComponents.POTION_CONTENTS, PotionContents.EMPTY.withEffectAdded(new MobEffectInstance(effect)));
				out.add(new HudLooks.PotionRow(icon, count, false, false));
			}
		}
		return out;
	}

	/** Whether "Toggle individual potions" leaves {@code effect} on (an effect it doesn't list always is). */
	private static boolean shown(ModuleState s, Holder<MobEffect> effect) {
		String path = effect.unwrapKey().map(key -> key.identifier().getPath()).orElse("");
		for (int i = 0; i < HudMods.PotionCounter.EFFECTS.length; i++) {
			if (HudMods.PotionCounter.EFFECTS[i].equals(path)) {
				return s.bool(HudMods.PotionCounter.SHOWN[i]);
			}
		}
		return true;
	}

	private static @Nullable ItemStack redstone;
	private static @Nullable ItemStack glowstone;

	/** The redstone drawn by longer potions (made once items exist). */
	public static ItemStack redstone() {
		if (redstone == null) {
			redstone = new ItemStack(Items.REDSTONE);
		}
		return redstone;
	}

	/** The glowstone drawn by stronger potions. */
	public static ItemStack glowstone() {
		if (glowstone == null) {
			glowstone = new ItemStack(Items.GLOWSTONE_DUST);
		}
		return glowstone;
	}

	// ---------- Day Counter ----------

	/** Whether Minecraft's action bar is showing a message right now (the day counter makes way for it). */
	public static boolean actionBarShowing() {
		return com.flintclient.client.compat.McCompat.hud() instanceof ActionBar bar && bar.flint$actionBarShowing();
	}

	/** Minecraft's HUD, by {@code HudFeatureMixin}: whether its action bar shows a message. */
	public interface ActionBar {
		boolean flint$actionBarShowing();
	}

	// ---------- the tick ----------

	private static boolean hadPack;

	/** Each client tick: Playtime's statistic asked for once a second; Pack Display switches off when your last pack goes. */
	public static void tick() {
		if (!GUARD.on()) {
			return;
		}
		try {
			Minecraft mc = mc();
			if (Mods.on(PLAYTIME) && mc.getConnection() != null && mc.player != null && mc.player.tickCount % 20 == 0) {
				mc.getConnection().send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS));
			}
			tickPacks(mc);
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	private static void tickPacks(Minecraft mc) {
		if (mc.player == null) {
			return;
		}
		boolean has = Mc263HudExtras.topPack() != null;
		ModuleState packs = Mods.state(PACKS);
		if (!has && hadPack && packs.enabled()) {
			if (packs.setEnabled(false)) {
				ControlState.settings().recordChange(packs.id());
				ControlState.saveAll();
			}
			com.flintclient.client.compat.McCompat.hud().getChat()
				.addClientSystemMessage(Component.literal("[Pack Displayer] No active texture pack detected, disabling overlay."));
		}
		hadPack = has;
	}
}
